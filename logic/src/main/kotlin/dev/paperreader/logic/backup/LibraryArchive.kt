package dev.paperreader.logic.backup

import java.time.Instant
import kotlinx.serialization.Serializable

data class PreparedLibraryArchive(
    val token: String,
    val summary: MetadataBackupSummary,
    val documents: Int,
    val documentBytes: Long,
    val createdAt: Instant,
    val suggestedFileName: String = "paper-reader-library.paperreader.zip",
    val skippedDocuments: Int = 0,
)

data class LibraryRestoreCandidate(
    val token: String,
    val metadata: MetadataRestorePreview,
    val documents: Int,
    val documentBytes: Long,
    val conflictingDocuments: Int,
)

data class LibraryRestoreReport(
    val metadata: MetadataRestoreResult.Applied,
    val restoredDocuments: Int,
    val skippedDocuments: Int,
)

enum class LibraryArchiveFailure { INVALID_ARCHIVE, LIMIT_EXCEEDED, FILE_UNAVAILABLE, EXPORT_FAILED, RESTORE_FAILED }

class LibraryArchiveException(val reason: LibraryArchiveFailure) : Exception(reason.name)

@Serializable
internal data class LibraryArchiveManifest(
    val formatMarker: String = "paperreader-library",
    val version: Int = 1,
    val createdAtEpochMillis: Long,
    val documents: List<ArchiveDocument> = emptyList(),
)

@Serializable
internal data class ArchiveDocument(
    val workSourceId: String,
    val manifestationSourceId: String,
    val sha256: String,
    val byteLength: Long,
    val entryName: String,
)
