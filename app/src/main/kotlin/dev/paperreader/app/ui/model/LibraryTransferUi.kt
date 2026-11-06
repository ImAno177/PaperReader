package dev.paperreader.app.ui.model

import dev.paperreader.logic.interop.BibliographyErrorCode
import dev.paperreader.logic.interop.BibliographyFormat
import java.time.Instant

data class LibraryTransferConflictUi(val code: String, val detail: String)

data class LibraryArchivePreviewUi(
    val createdAt: Instant,
    val works: Int,
    val collections: Int,
    val manifestations: Int,
    val readingStates: Int,
    val bookmarks: Int,
    val annotations: Int,
    val historyEntries: Int,
    val savedSearches: Int,
    val savedSearchHits: Int,
    val newWorks: Int,
    val mergedWorks: Int,
    val skippedWorks: Int,
    val conflicts: List<LibraryTransferConflictUi>,
    val missingProviders: List<String>,
    val dormantReadingStates: Int,
    val dormantBookmarks: Int,
    val dormantAnnotations: Int,
    val skippedRecords: Int,
    val documents: Int,
    val documentBytes: Long,
    val conflictingDocuments: Int,
)

data class LibraryArchiveRestoreReportUi(
    val appliedWorks: Int,
    val skippedWorks: Int,
    val appliedBookmarks: Int,
    val appliedAnnotations: Int,
    val appliedSavedSearches: Int,
    val appliedSavedSearchHits: Int,
    val skippedRecords: Int,
    val restoredDocuments: Int,
    val skippedDocuments: Int,
)

data class BibliographyPreviewUi(
    val format: BibliographyFormat,
    val records: Int,
    val newRecords: Int,
    val exactMatches: Int,
    val ambiguousRecords: Int,
    val ambiguousExamples: List<String>,
    val partialDatePrecisionLoss: Int,
    val error: BibliographyErrorCode? = null,
)

data class BibliographyImportReportUi(
    val format: BibliographyFormat,
    val imported: Int,
    val merged: Int,
    val skipped: Int,
    val partialDatePrecisionLoss: Int,
)

enum class LibraryTransferOperation {
    LIBRARY_EXPORT,
    LIBRARY_IMPORT,
    LIBRARY_RESTORE,
    BIBLIOGRAPHY_EXPORT,
    BIBLIOGRAPHY_IMPORT,
    NOTES_EXPORT,
}

enum class LibraryTransferFailure {
    FILE_TOO_LARGE,
    FILE_UNAVAILABLE,
    INCOMPLETE_FILE_MAY_REMAIN,
    INVALID_OR_UNSUPPORTED,
    EXPORT_FAILED,
    RESTORE_FAILED,
    IMPORT_FAILED,
    IMPORT_FAILED_MAY_BE_PARTIAL,
}

sealed interface LibraryTransferUiState {
    data object Recovering : LibraryTransferUiState
    data object Idle : LibraryTransferUiState
    data object ExportingLibrary : LibraryTransferUiState
    data class LibraryExported(val papers: Int, val documents: Int, val documentBytes: Long) : LibraryTransferUiState
    data object InspectingLibrary : LibraryTransferUiState
    data object DiscardingLibraryPreview : LibraryTransferUiState
    data class LibraryPreview(
        val value: LibraryArchivePreviewUi,
        val restoreFailure: LibraryTransferFailure? = null,
        val discardFailed: Boolean = false,
    ) : LibraryTransferUiState
    data class RestoringLibrary(val value: LibraryArchivePreviewUi) : LibraryTransferUiState
    data class LibraryRestored(val value: LibraryArchiveRestoreReportUi) : LibraryTransferUiState
    data class ExportingBibliography(val format: BibliographyFormat) : LibraryTransferUiState
    data class BibliographyExported(val format: BibliographyFormat) : LibraryTransferUiState
    data class InspectingBibliography(val format: BibliographyFormat) : LibraryTransferUiState
    data class BibliographyPreview(
        val value: BibliographyPreviewUi,
        val importFailure: LibraryTransferFailure? = null,
    ) : LibraryTransferUiState
    data class ImportingBibliography(val value: BibliographyPreviewUi) : LibraryTransferUiState
    data class BibliographyImported(val value: BibliographyImportReportUi) : LibraryTransferUiState
    data object ExportingNotes : LibraryTransferUiState
    data object NotesExported : LibraryTransferUiState
    data class Failed(
        val operation: LibraryTransferOperation,
        val reason: LibraryTransferFailure,
    ) : LibraryTransferUiState
}

val LibraryTransferUiState.isBusy: Boolean
    get() = when (this) {
        LibraryTransferUiState.Recovering,
        LibraryTransferUiState.ExportingLibrary,
        LibraryTransferUiState.InspectingLibrary,
        LibraryTransferUiState.DiscardingLibraryPreview,
        is LibraryTransferUiState.RestoringLibrary,
        is LibraryTransferUiState.ExportingBibliography,
        is LibraryTransferUiState.InspectingBibliography,
        is LibraryTransferUiState.ImportingBibliography,
        LibraryTransferUiState.ExportingNotes,
        -> true

        else -> false
    }

val LibraryTransferUiState.canStartOperation: Boolean
    get() = when (this) {
        LibraryTransferUiState.Idle,
        is LibraryTransferUiState.LibraryExported,
        is LibraryTransferUiState.LibraryRestored,
        is LibraryTransferUiState.BibliographyExported,
        is LibraryTransferUiState.BibliographyImported,
        LibraryTransferUiState.NotesExported,
        is LibraryTransferUiState.Failed,
        -> true

        else -> false
    }
