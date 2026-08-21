package dev.paperreader.app.reader

import android.text.InputFilter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

/** Owns the readable-reader annotation lifecycle and its single mobile editor. */
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
                    showCreateEditor(result.selection)
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

    fun openAnnotation(id: String) {
        val annotation = currentAnnotations.firstOrNull { it.id == id }
        if (annotation == null) toast(R.string.readable_reader_annotation_missing, Toast.LENGTH_LONG)
        else showEditEditor(annotation)
    }

    fun showAnnotations() {
        val listed = currentAnnotations.toList()
        if (listed.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.readable_reader_annotations_empty_title)
                .setMessage(R.string.readable_reader_annotations_empty_body)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.readable_reader_annotations_count, listed.size))
            .setItems(listed.map { readableAnnotationLabel(it.quoteExact, it.note) }.toTypedArray()) { dialog, index ->
                val annotation = listed[index]
                dialog.dismiss()
                webView.scrollToAnnotation(annotation.id) { found ->
                    if (found) openAnnotation(annotation.id)
                    else toast(R.string.readable_reader_annotation_anchor_missing, Toast.LENGTH_LONG)
                }
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

    private fun showCreateEditor(selection: ReadableTextSelection) {
        val paperDocument = document() ?: return
        val editor = createEditor(
            title = activity.getString(R.string.readable_reader_add_highlight),
            quote = selection.quoteExact,
            note = null,
            canDelete = false,
            saveLabel = activity.getString(R.string.readable_reader_annotation_save_highlight),
        )
        editor.save.setOnClickListener {
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
                editor.dialog.dismiss()
                return@setOnClickListener
            }
            setEditorBusy(editor, true)
            activity.lifecycleScope.launch {
                val result = try {
                    withContext(Dispatchers.IO) {
                        logic().useCases.saveAnnotation.await(workId(), anchor, editor.note.text?.toString())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    toast(R.string.readable_reader_annotation_save_failed, Toast.LENGTH_LONG)
                    setEditorBusy(editor, false)
                    return@launch
                }
                when (result) {
                    is SaveAnnotationResult.Saved -> {
                        toast(
                            if (result.created) R.string.readable_reader_annotation_saved
                            else R.string.readable_reader_annotation_updated,
                            Toast.LENGTH_SHORT,
                        )
                        editor.dialog.dismiss()
                    }
                    SaveAnnotationResult.OverlapsExisting -> {
                        toast(R.string.readable_reader_annotation_overlap, Toast.LENGTH_LONG)
                        setEditorBusy(editor, false)
                    }
                    SaveAnnotationResult.InvalidNote -> showInvalidNote(editor)
                    SaveAnnotationResult.PaperNotFound,
                    SaveAnnotationResult.DocumentNotCurrent,
                    -> {
                        toast(R.string.readable_reader_annotation_stale_document, Toast.LENGTH_LONG)
                        editor.dialog.dismiss()
                    }
                }
            }
        }
        editor.dialog.showExpanded()
    }

    private fun showEditEditor(annotation: Annotation) {
        val editor = createEditor(
            title = activity.getString(R.string.readable_reader_annotation_editor_title),
            quote = annotation.quoteExact,
            note = annotation.note,
            canDelete = true,
            saveLabel = activity.getString(R.string.readable_reader_annotation_save_note),
        )
        editor.save.setOnClickListener {
            setEditorBusy(editor, true)
            activity.lifecycleScope.launch {
                val result = try {
                    withContext(Dispatchers.IO) {
                        logic().useCases.updateAnnotationNote.await(annotation.id, editor.note.text?.toString())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    toast(R.string.readable_reader_annotation_save_failed, Toast.LENGTH_LONG)
                    setEditorBusy(editor, false)
                    return@launch
                }
                when (result) {
                    is UpdateAnnotationNoteResult.Updated -> {
                        toast(R.string.readable_reader_annotation_note_saved, Toast.LENGTH_SHORT)
                        editor.dialog.dismiss()
                    }
                    UpdateAnnotationNoteResult.InvalidNote -> showInvalidNote(editor)
                    UpdateAnnotationNoteResult.NotFound -> {
                        toast(R.string.readable_reader_annotation_missing, Toast.LENGTH_LONG)
                        editor.dialog.dismiss()
                    }
                }
            }
        }
        editor.delete.setOnClickListener { confirmDelete(annotation) { editor.dialog.dismiss() } }
        editor.dialog.showExpanded()
    }

    private fun createEditor(
        title: String,
        quote: String,
        note: String?,
        canDelete: Boolean,
        saveLabel: String,
    ): AnnotationEditor {
        val content = activity.layoutInflater.inflate(R.layout.sheet_readable_annotation, null)
        content.findViewById<TextView>(R.id.readable_annotation_title).text = title
        content.findViewById<TextView>(R.id.readable_annotation_quote).text = quote
        val noteInput = content.findViewById<TextInputEditText>(R.id.readable_annotation_note).apply {
            filters = arrayOf(InputFilter.LengthFilter(MAX_ANNOTATION_NOTE_LENGTH))
            setText(note.orEmpty())
            setSelection(text?.length ?: 0)
        }
        val dialog = BottomSheetDialog(activity).apply { setContentView(content) }
        val close = content.findViewById<MaterialButton>(R.id.readable_annotation_close).apply {
            setOnClickListener { dialog.dismiss() }
        }
        return AnnotationEditor(
            dialog = dialog,
            noteContainer = content.findViewById(R.id.readable_annotation_note_container),
            note = noteInput,
            save = content.findViewById<MaterialButton>(R.id.readable_annotation_save).apply { text = saveLabel },
            delete = content.findViewById<MaterialButton>(R.id.readable_annotation_delete).apply {
                visibility = if (canDelete) View.VISIBLE else View.GONE
            },
            close = close,
        )
    }

    private fun confirmDelete(annotation: Annotation, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.readable_reader_annotation_delete_title)
            .setMessage(R.string.readable_reader_annotation_delete_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                activity.lifecycleScope.launch {
                    val result = try {
                        withContext(Dispatchers.IO) { logic().useCases.removeAnnotation.await(annotation.id) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toast(R.string.readable_reader_annotation_delete_failed, Toast.LENGTH_LONG)
                        return@launch
                    }
                    toast(
                        if (result == RemoveAnnotationResult.Removed) R.string.readable_reader_annotation_deleted
                        else R.string.readable_reader_annotation_missing,
                        Toast.LENGTH_SHORT,
                    )
                    onDeleted()
                }
            }
            .show()
    }

    private fun showInvalidNote(editor: AnnotationEditor) {
        editor.noteContainer.error = activity.getString(R.string.readable_reader_annotation_note_too_long)
        setEditorBusy(editor, false)
    }

    private fun setEditorBusy(editor: AnnotationEditor, busy: Boolean) {
        editor.note.isEnabled = !busy
        editor.save.isEnabled = !busy
        editor.delete.isEnabled = !busy
        editor.close.isEnabled = !busy
    }

    private fun BottomSheetDialog.showExpanded() {
        setOnShowListener {
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        show()
    }

    private fun toast(message: Int, duration: Int) {
        Toast.makeText(activity, message, duration).show()
    }
}

private data class AnnotationEditor(
    val dialog: BottomSheetDialog,
    val noteContainer: TextInputLayout,
    val note: TextInputEditText,
    val save: MaterialButton,
    val delete: MaterialButton,
    val close: MaterialButton,
)

internal fun readableAnnotationLabel(quoteExact: String, note: String?): String {
    val quote = quoteExact.replace(Regex("\\s+"), " ").trim().take(72)
    val notePreview = note?.replace(Regex("\\s+"), " ")?.trim()?.take(72).orEmpty()
    return if (notePreview.isBlank()) quote else "$quote\n$notePreview"
}
