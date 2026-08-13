package dev.paperreader.app.reader

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.MAX_ANNOTATION_NOTE_LENGTH
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import dev.paperreader.logic.reader.ReadablePaperDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the readable-reader annotation lifecycle and all annotation dialogs/actions. */
internal class ReadablePaperAnnotationController(
    private val activity: AppCompatActivity,
    private val toolbar: Toolbar,
    private val webView: ReadablePaperWebView,
    private val workId: () -> WorkId,
    private val document: () -> ReadablePaperDocument?,
    private val documentLoaded: () -> Boolean,
    private val logic: () -> PaperReaderLogic = {
        (activity.application as PaperReaderApplication).logic
    },
) {
    private var annotationJob: Job? = null
    private var currentAnnotations: List<Annotation> = emptyList()

    fun annotations(): List<Annotation> = currentAnnotations

    suspend fun loadInitial(document: ReadablePaperDocument): List<Annotation> {
        currentAnnotations = try {
            withContext(Dispatchers.IO) {
                logic().useCases.observeAnnotations.subscribe(workId(), document.documentSha256).first()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            toast(R.string.readable_reader_annotation_load_failed, Toast.LENGTH_LONG)
            emptyList()
        }
        updateMenu()
        return currentAnnotations
    }

    fun reset() {
        annotationJob?.cancel()
        annotationJob = null
        currentAnnotations = emptyList()
        updateMenu()
    }

    fun cancel() {
        annotationJob?.cancel()
        annotationJob = null
    }

    fun observe(document: ReadablePaperDocument) {
        annotationJob?.cancel()
        annotationJob = activity.lifecycleScope.launch {
            try {
                logic().useCases.observeAnnotations
                    .subscribe(workId(), document.documentSha256)
                    .collectLatest { annotations ->
                        if (this@ReadablePaperAnnotationController.document()?.documentSha256 != document.documentSha256) {
                            return@collectLatest
                        }
                        val anchorsChanged = !currentAnnotations.hasSameRenderedAnchors(annotations)
                        currentAnnotations = annotations
                        updateMenu()
                        if (documentLoaded() && anchorsChanged) webView.applyAnnotations(annotations)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.readable_reader_annotation_load_failed, Toast.LENGTH_LONG)
            }
        }
    }

    fun captureSelection() {
        if (!documentLoaded()) return
        webView.captureTextSelection { result ->
            when (result) {
                is ReadableSelectionResult.Ready -> {
                    webView.clearTextSelection()
                    showCreateDialog(result.selection)
                }
                is ReadableSelectionResult.Unavailable -> toast(
                    when (result.reason) {
                        ReadableSelectionFailure.EMPTY -> R.string.readable_reader_annotation_select_text
                        ReadableSelectionFailure.CROSS_BLOCK -> R.string.readable_reader_annotation_single_block
                        ReadableSelectionFailure.TOO_LONG -> R.string.readable_reader_annotation_too_long
                        ReadableSelectionFailure.INVALID -> R.string.readable_reader_annotation_invalid_selection
                    },
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    fun showAnnotations() {
        if (currentAnnotations.isEmpty()) {
            toast(R.string.readable_reader_annotations_empty, Toast.LENGTH_SHORT)
            return
        }
        val labels = currentAnnotations.map { annotation ->
            readableAnnotationLabel(annotation.quoteExact, annotation.note) { quote ->
                activity.getString(R.string.readable_reader_annotation_with_note, quote)
            }
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.readable_reader_annotations_count, currentAnnotations.size))
            .setItems(labels) { dialog, index ->
                showAnnotationActions(currentAnnotations[index])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun updateMenu() {
        val item = toolbar.menu.findItem(R.id.action_readable_annotations) ?: return
        item.isEnabled = document() != null
        item.title = if (currentAnnotations.isEmpty()) {
            activity.getString(R.string.readable_reader_annotations)
        } else {
            activity.getString(R.string.readable_reader_annotations_count, currentAnnotations.size)
        }
    }

    private fun showCreateDialog(selection: ReadableTextSelection) {
        val paperDocument = document() ?: return
        val noteInput = annotationNoteInput()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.readable_reader_add_highlight)
            .setMessage(activity.getString(R.string.readable_reader_selected_quote, selection.quoteExact))
            .setView(noteInput)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val anchor = runCatching {
                    AnnotationSelection(
                        documentSha256 = paperDocument.documentSha256,
                        blockId = selection.blockId,
                        startOffset = selection.startOffset,
                        endOffset = selection.endOffset,
                        quotePrefix = selection.quotePrefix,
                        quoteExact = selection.quoteExact,
                        quoteSuffix = selection.quoteSuffix,
                    )
                }.getOrNull()
                if (anchor == null) {
                    toast(R.string.readable_reader_annotation_invalid_selection, Toast.LENGTH_LONG)
                    dialog.dismiss()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activity.lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) {
                            logic().useCases.saveAnnotation.await(workId(), anchor, noteInput.text.toString())
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast(R.string.readable_reader_annotation_save_failed, Toast.LENGTH_LONG)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        return@launch
                    }
                    when (result) {
                        is SaveAnnotationResult.Saved -> {
                            toast(
                                if (result.created) R.string.readable_reader_annotation_saved
                                else R.string.readable_reader_annotation_updated,
                                Toast.LENGTH_SHORT,
                            )
                            dialog.dismiss()
                        }
                        SaveAnnotationResult.OverlapsExisting -> {
                            toast(R.string.readable_reader_annotation_overlap, Toast.LENGTH_LONG)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                        SaveAnnotationResult.InvalidNote -> {
                            noteInput.error = activity.getString(R.string.readable_reader_annotation_note_too_long)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                        SaveAnnotationResult.PaperNotFound,
                        SaveAnnotationResult.DocumentNotCurrent,
                        -> {
                            toast(R.string.readable_reader_annotation_stale_document, Toast.LENGTH_LONG)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showAnnotationActions(annotation: Annotation) {
        val actions = arrayOf(
            activity.getString(R.string.readable_reader_annotation_jump),
            activity.getString(R.string.readable_reader_annotation_edit_note),
            activity.getString(R.string.readable_reader_annotation_delete),
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.readable_reader_selected_quote, annotation.quoteExact))
            .setMessage(annotation.note ?: activity.getString(R.string.readable_reader_annotation_no_note))
            .setItems(actions) { dialog, which ->
                when (which) {
                    0 -> webView.scrollToAnnotation(annotation.id) { found ->
                        if (!found) toast(R.string.readable_reader_annotation_anchor_missing, Toast.LENGTH_LONG)
                    }
                    1 -> showEditNoteDialog(annotation)
                    2 -> confirmDelete(annotation)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showEditNoteDialog(annotation: Annotation) {
        val noteInput = annotationNoteInput(annotation.note)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.readable_reader_annotation_edit_note)
            .setView(noteInput)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activity.lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) {
                            logic().useCases.updateAnnotationNote.await(annotation.id, noteInput.text.toString())
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast(R.string.readable_reader_annotation_save_failed, Toast.LENGTH_LONG)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        return@launch
                    }
                    when (result) {
                        is UpdateAnnotationNoteResult.Updated -> dialog.dismiss()
                        UpdateAnnotationNoteResult.InvalidNote -> {
                            noteInput.error = activity.getString(R.string.readable_reader_annotation_note_too_long)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }
                        UpdateAnnotationNoteResult.NotFound -> {
                            toast(R.string.readable_reader_annotation_missing, Toast.LENGTH_LONG)
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(annotation: Annotation) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.readable_reader_annotation_delete_title)
            .setMessage(R.string.readable_reader_annotation_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) {
                            logic().useCases.removeAnnotation.await(annotation.id)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast(R.string.readable_reader_annotation_delete_failed, Toast.LENGTH_LONG)
                        return@launch
                    }
                    if (result == RemoveAnnotationResult.NotFound) {
                        toast(R.string.readable_reader_annotation_missing, Toast.LENGTH_LONG)
                    }
                }
            }
            .show()
    }

    private fun annotationNoteInput(existing: String? = null) = EditText(activity).apply {
        hint = activity.getString(R.string.readable_reader_annotation_note_hint)
        contentDescription = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        maxLines = 8
        filters = arrayOf(InputFilter.LengthFilter(MAX_ANNOTATION_NOTE_LENGTH))
        setText(existing.orEmpty())
        setSelection(text.length)
    }

    private fun toast(message: Int, duration: Int) {
        Toast.makeText(activity, message, duration).show()
    }
}

internal fun readableAnnotationLabel(
    quoteExact: String,
    note: String?,
    withNote: (String) -> String = { quote -> quote },
): String {
    val quote = quoteExact.replace(Regex("\\s+"), " ").trim().take(72)
    return if (note.isNullOrBlank()) quote else withNote(quote)
}
