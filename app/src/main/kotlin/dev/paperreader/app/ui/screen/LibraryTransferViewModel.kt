package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.ui.model.BibliographyImportReportUi
import dev.paperreader.app.ui.model.BibliographyPreviewUi
import dev.paperreader.app.ui.model.LibraryArchivePreviewUi
import dev.paperreader.app.ui.model.LibraryArchiveRestoreReportUi
import dev.paperreader.app.ui.model.LibraryTransferConflictUi
import dev.paperreader.app.ui.model.LibraryTransferFailure
import dev.paperreader.app.ui.model.LibraryTransferOperation
import dev.paperreader.app.ui.model.LibraryTransferUiState
import dev.paperreader.app.ui.model.canStartOperation
import dev.paperreader.app.ui.model.isBusy
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.backup.LibraryArchiveException
import dev.paperreader.logic.backup.LibraryArchiveFailure
import dev.paperreader.logic.backup.LibraryRestoreCandidate
import dev.paperreader.logic.backup.LibraryRestoreReport
import dev.paperreader.logic.interop.BibliographyErrorCode
import dev.paperreader.logic.interop.BibliographyFormat
import dev.paperreader.logic.interop.MAX_BIBLIOGRAPHY_INPUT_BYTES
import dev.paperreader.logic.usecase.BibliographyImportDisposition
import dev.paperreader.logic.usecase.BibliographyImportPreview
import dev.paperreader.logic.usecase.BibliographyImportReport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryTransferViewModel(
    private val logic: PaperReaderLogic,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<LibraryTransferUiState>(LibraryTransferUiState.Recovering)
    val uiState: StateFlow<LibraryTransferUiState> = mutableUiState.asStateFlow()

    private val operationLock = Any()
    private var operationRunning = false
    private var pendingRestoreToken: String? = null
    private var pendingBibliographyPreview: BibliographyImportPreview? = null

    init {
        synchronized(operationLock) {
            operationRunning = true
            mutableUiState.value = LibraryTransferUiState.Recovering
        }
        viewModelScope.launch {
            try {
                val candidate = logic.archives.recoverPendingRestore()
                pendingRestoreToken = candidate?.token
                mutableUiState.value = candidate?.toUiState() ?: LibraryTransferUiState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.value = LibraryTransferUiState.Failed(
                    LibraryTransferOperation.LIBRARY_IMPORT,
                    mapFailure(failure, LibraryTransferFailure.INVALID_OR_UNSUPPORTED),
                )
            } finally {
                finishOperation()
            }
        }
    }
    fun exportLibrary(open: () -> OutputStream) {
        startOperation(
            initialState = LibraryTransferUiState.ExportingLibrary,
            fallbackOperation = LibraryTransferOperation.LIBRARY_EXPORT,
            fallbackReason = LibraryTransferFailure.EXPORT_FAILED,
        ) {
            var prepared: dev.paperreader.logic.backup.PreparedLibraryArchive? = null
            try {
                val archive = logic.archives.prepareExport()
                prepared = archive
                try {
                    withContext(Dispatchers.IO) {
                        open().use { output -> logic.archives.writeExport(archive.token, output) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw IncompleteFileWriteException(failure)
                }
                LibraryTransferUiState.LibraryExported(
                    papers = archive.summary.works,
                    documents = archive.documents,
                    documentBytes = archive.documentBytes,
                )
            } finally {
                prepared?.let { archive ->
                    withContext(NonCancellable) {
                        runCatching { logic.archives.discard(archive.token) }
                    }
                }
            }
        }
    }
    fun exportBibliography(format: BibliographyFormat, open: () -> OutputStream) {
        startOperation(
            initialState = LibraryTransferUiState.ExportingBibliography(format),
            fallbackOperation = LibraryTransferOperation.BIBLIOGRAPHY_EXPORT,
            fallbackReason = LibraryTransferFailure.EXPORT_FAILED,
        ) {
            val text = logic.useCases.exportBibliography.export(format)
            try {
                withContext(Dispatchers.IO) {
                    OutputStreamWriter(open(), StandardCharsets.UTF_8).use { it.write(text) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IncompleteFileWriteException(failure)
            }
            LibraryTransferUiState.BibliographyExported(format)
        }
    }

    fun exportNotes(open: () -> OutputStream) {
        startOperation(
            initialState = LibraryTransferUiState.ExportingNotes,
            fallbackOperation = LibraryTransferOperation.NOTES_EXPORT,
            fallbackReason = LibraryTransferFailure.EXPORT_FAILED,
        ) {
            val markdown = logic.useCases.exportNotesMarkdown()
            try {
                withContext(Dispatchers.IO) {
                    OutputStreamWriter(open(), StandardCharsets.UTF_8).use { it.write(markdown) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IncompleteFileWriteException(failure)
            }
            LibraryTransferUiState.NotesExported
        }
    }

    fun inspectLibrary(open: () -> InputStream) {
        startOperation(
            initialState = LibraryTransferUiState.InspectingLibrary,
            fallbackOperation = LibraryTransferOperation.LIBRARY_IMPORT,
            fallbackReason = LibraryTransferFailure.INVALID_OR_UNSUPPORTED,
        ) {
            val candidate = withContext(Dispatchers.IO) {
                open().use { input -> logic.archives.prepareRestore(input) }
            }
            pendingRestoreToken = candidate.token
            candidate.toUiState()
        }
    }

    fun inspectBibliography(format: BibliographyFormat, open: () -> InputStream) {
        startOperation(
            initialState = LibraryTransferUiState.InspectingBibliography(format),
            fallbackOperation = LibraryTransferOperation.BIBLIOGRAPHY_IMPORT,
            fallbackReason = LibraryTransferFailure.INVALID_OR_UNSUPPORTED,
        ) {
            val bytes = withContext(Dispatchers.IO) {
                open().use { input -> readBibliographyBounded(input) }
            }
            val preview = logic.useCases.previewBibliographyImport.preview(format, bytes)
            pendingBibliographyPreview = preview
            preview.toUiState()
        }
    }

    fun confirm() {
        when (val state = mutableUiState.value) {
            is LibraryTransferUiState.LibraryPreview -> confirmRestore(state)
            is LibraryTransferUiState.BibliographyPreview -> confirmBibliographyImport(state)
            else -> Unit
        }
    }

    fun dismiss() {
        when (val state = mutableUiState.value) {
            is LibraryTransferUiState.LibraryPreview -> discardRestorePreview(state)
            is LibraryTransferUiState.BibliographyPreview -> {
                if (tryStartFromPreview(state)) {
                    pendingBibliographyPreview = null
                    mutableUiState.value = LibraryTransferUiState.Idle
                    finishOperation()
                }
            }
            else -> {
                synchronized(operationLock) {
                    if (operationRunning || mutableUiState.value.isBusy) return
                    pendingBibliographyPreview = null
                    mutableUiState.value = LibraryTransferUiState.Idle
                }
            }
        }
    }

    private fun confirmRestore(state: LibraryTransferUiState.LibraryPreview) {
        val token = pendingRestoreToken ?: return
        if (!tryStartFromPreview(state)) return
        mutableUiState.value = LibraryTransferUiState.RestoringLibrary(state.value)
        viewModelScope.launch {
            try {
                val report = logic.archives.restore(token)
                pendingRestoreToken = null
                mutableUiState.value = LibraryTransferUiState.LibraryRestored(report.toUi())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.value = state.copy(restoreFailure = mapFailure(failure, LibraryTransferFailure.RESTORE_FAILED))
            } finally {
                finishOperation()
            }
        }
    }

    private fun confirmBibliographyImport(state: LibraryTransferUiState.BibliographyPreview) {
        val preview = pendingBibliographyPreview ?: return
        if (state.value.error != null || state.value.records == 0 || !tryStartFromPreview(state)) return
        mutableUiState.value = LibraryTransferUiState.ImportingBibliography(state.value)
        viewModelScope.launch {
            try {
                val report = logic.useCases.importBibliography.import(preview)
                if (report.error != null) {
                    mutableUiState.value = state.copy(importFailure = LibraryTransferFailure.IMPORT_FAILED)
                } else {
                    pendingBibliographyPreview = null
                    mutableUiState.value = LibraryTransferUiState.BibliographyImported(report.toUi())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = state.copy(importFailure = LibraryTransferFailure.IMPORT_FAILED_MAY_BE_PARTIAL)
            } finally {
                finishOperation()
            }
        }
    }

    private fun discardRestorePreview(state: LibraryTransferUiState.LibraryPreview) {
        val token = pendingRestoreToken
        if (token == null || !tryStartFromPreview(state)) return
        mutableUiState.value = LibraryTransferUiState.DiscardingLibraryPreview
        viewModelScope.launch {
            try {
                logic.archives.discard(token)
                pendingRestoreToken = null
                mutableUiState.value = LibraryTransferUiState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = state.copy(discardFailed = true)
            } finally {
                finishOperation()
            }
        }
    }

    private fun startOperation(
        initialState: LibraryTransferUiState,
        fallbackOperation: LibraryTransferOperation,
        fallbackReason: LibraryTransferFailure,
        operation: suspend () -> LibraryTransferUiState,
    ) {
        synchronized(operationLock) {
            if (operationRunning || mutableUiState.value.isBusy || !mutableUiState.value.canStartOperation) return
            operationRunning = true
            mutableUiState.value = initialState
        }
        viewModelScope.launch {
            try {
                mutableUiState.value = operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableUiState.value = LibraryTransferUiState.Failed(
                    fallbackOperation,
                    mapFailure(failure, fallbackReason),
                )
            } finally {
                finishOperation()
            }
        }
    }

    private fun tryStartFromPreview(expected: LibraryTransferUiState): Boolean = synchronized(operationLock) {
        if (operationRunning || mutableUiState.value != expected) return@synchronized false
        operationRunning = true
        true
    }

    private fun finishOperation() {
        synchronized(operationLock) { operationRunning = false }
    }

    private fun mapFailure(failure: Exception, fallback: LibraryTransferFailure): LibraryTransferFailure = when (failure) {
        is BibliographyInputTooLargeException -> LibraryTransferFailure.FILE_TOO_LARGE
        is IncompleteFileWriteException -> LibraryTransferFailure.INCOMPLETE_FILE_MAY_REMAIN
        is LibraryArchiveException -> when (failure.reason) {
            LibraryArchiveFailure.LIMIT_EXCEEDED -> LibraryTransferFailure.FILE_TOO_LARGE
            LibraryArchiveFailure.FILE_UNAVAILABLE -> LibraryTransferFailure.FILE_UNAVAILABLE
            LibraryArchiveFailure.INVALID_ARCHIVE -> LibraryTransferFailure.INVALID_OR_UNSUPPORTED
            LibraryArchiveFailure.EXPORT_FAILED -> LibraryTransferFailure.EXPORT_FAILED
            LibraryArchiveFailure.RESTORE_FAILED -> LibraryTransferFailure.RESTORE_FAILED
        }
        is IOException -> LibraryTransferFailure.FILE_UNAVAILABLE
        is IllegalArgumentException -> if (failure.message?.contains("size limit", ignoreCase = true) == true) {
            LibraryTransferFailure.FILE_TOO_LARGE
        } else {
            fallback
        }
        else -> fallback
    }

    private fun LibraryRestoreCandidate.toUiState() = LibraryTransferUiState.LibraryPreview(
        value = LibraryArchivePreviewUi(
            createdAt = metadata.createdAt,
            works = metadata.summary.works,
            collections = metadata.summary.collections,
            manifestations = metadata.summary.manifestations,
            readingStates = metadata.summary.readingStates,
            bookmarks = metadata.summary.bookmarks,
            annotations = metadata.summary.annotations,
            historyEntries = metadata.summary.history,
            savedSearches = metadata.summary.savedSearches,
            savedSearchHits = metadata.summary.savedSearchHits,
            newWorks = metadata.newWorks,
            mergedWorks = metadata.mergedWorks,
            skippedWorks = metadata.skippedWorks,
            conflicts = metadata.conflicts.map { LibraryTransferConflictUi(it.code, it.detail) },
            missingProviders = metadata.missingProviders.sorted(),
            dormantReadingStates = metadata.dormantReadingStates,
            dormantBookmarks = metadata.dormantBookmarks,
            dormantAnnotations = metadata.dormantAnnotations,
            skippedRecords = metadata.skippedRecords,
            documents = documents,
            documentBytes = documentBytes,
            conflictingDocuments = conflictingDocuments,
        ),
    )

    private fun LibraryRestoreReport.toUi() = LibraryArchiveRestoreReportUi(
        appliedWorks = metadata.appliedWorks,
        skippedWorks = metadata.skippedWorks,
        appliedBookmarks = metadata.appliedBookmarks,
        appliedAnnotations = metadata.appliedAnnotations,
        appliedSavedSearches = metadata.appliedSavedSearches,
        appliedSavedSearchHits = metadata.appliedSavedSearchHits,
        skippedRecords = metadata.skippedRecords,
        restoredDocuments = restoredDocuments,
        skippedDocuments = skippedDocuments,
    )

    private fun BibliographyImportPreview.toUiState() = LibraryTransferUiState.BibliographyPreview(
        value = BibliographyPreviewUi(
            format = format,
            records = candidates.size,
            newRecords = candidates.count { it.disposition == BibliographyImportDisposition.NEW },
            exactMatches = candidates.count { it.disposition == BibliographyImportDisposition.EXACT_MATCH },
            ambiguousRecords = candidates.count { it.disposition == BibliographyImportDisposition.AMBIGUOUS },
            ambiguousExamples = candidates.asSequence()
                .filter { it.disposition == BibliographyImportDisposition.AMBIGUOUS }
                .map { candidate ->
                    val title = candidate.entry.title
                    if (title.length <= BIBLIOGRAPHY_TITLE_PREVIEW_LIMIT) title
                    else title.take(BIBLIOGRAPHY_TITLE_PREVIEW_LIMIT - 1) + "…"
                }
                .take(3)
                .toList(),
            partialDatePrecisionLoss = partialDatePrecisionLossIndices.size,
            error = error?.code,
        ),
    )

    private fun BibliographyImportReport.toUi() = BibliographyImportReportUi(
        format = format,
        imported = importedCount,
        merged = mergedCount,
        skipped = skippedCount,
        partialDatePrecisionLoss = partialDatePrecisionLossIndices.size,
    )

    private class IncompleteFileWriteException(cause: Exception) : IOException(cause)

    private class BibliographyInputTooLargeException : IOException("Bibliography input exceeds its size limit")

    private companion object {

        const val BIBLIOGRAPHY_TITLE_PREVIEW_LIMIT = 180

        suspend fun readBibliographyBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, MAX_BIBLIOGRAPHY_INPUT_BYTES))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) throw IOException("The selected bibliography returned an empty read")
                if (total > MAX_BIBLIOGRAPHY_INPUT_BYTES - read) throw BibliographyInputTooLargeException()
                output.write(buffer, 0, read)
                total += read
            }
            return output.toByteArray()
        }
    }
}
