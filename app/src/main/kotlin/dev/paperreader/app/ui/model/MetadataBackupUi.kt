package dev.paperreader.app.ui.model

import java.time.Instant

data class MetadataBackupSummaryUi(
    val works: Int,
    val collections: Int,
    val manifestations: Int,
    val readingStates: Int,
    val bookmarks: Int,
    val annotations: Int,
    val historyEntries: Int,
    val savedSearches: Int = 0,
    val savedSearchHits: Int = 0,
)

data class MetadataRestorePreviewUi(
    val createdAt: Instant,
    val summary: MetadataBackupSummaryUi,
    val newWorks: Int,
    val mergedWorks: Int,
    val skippedWorks: Int,
    val conflicts: List<MetadataRestoreIssueUi>,
    val missingProviders: List<String>,
    val dormantReadingStates: Int,
    val dormantBookmarks: Int,
    val dormantAnnotations: Int,
    val skippedRecords: Int,
)

data class MetadataRestoreIssueUi(
    val code: String,
    val detail: String,
)

data class MetadataRestoreReportUi(
    val summary: MetadataBackupSummaryUi,
    val appliedWorks: Int,
    val skippedWorks: Int,
    val appliedBookmarks: Int,
    val appliedAnnotations: Int,
    val skippedRecords: Int,
    val appliedSavedSearches: Int = 0,
    val appliedSavedSearchHits: Int = 0,
)

enum class MetadataBackupOperation {
    EXPORT,
    PREVIEW,
    RESTORE,
}

sealed interface MetadataBackupUiState {
    data object Idle : MetadataBackupUiState
    data object Exporting : MetadataBackupUiState
    data class Exported(val summary: MetadataBackupSummaryUi) : MetadataBackupUiState
    data object Inspecting : MetadataBackupUiState
    data class Preview(val value: MetadataRestorePreviewUi) : MetadataBackupUiState
    data class Restoring(val preview: MetadataRestorePreviewUi) : MetadataBackupUiState
    data class Restored(val report: MetadataRestoreReportUi) : MetadataBackupUiState
    data class Failed(
        val operation: MetadataBackupOperation,
        val reason: MetadataBackupFailure,
    ) : MetadataBackupUiState
}

enum class MetadataBackupFailure {
    FILE_TOO_LARGE,
    FILE_UNAVAILABLE,
    INCOMPLETE_FILE_MAY_REMAIN,
    INVALID_OR_UNSUPPORTED,
    LIBRARY_TOO_LARGE,
    EXPORT_FAILED,
    RESTORE_FAILED,
}
