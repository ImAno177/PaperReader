@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.paperreader.logic.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.time.Instant


const val METADATA_BACKUP_SCHEMA_VERSION: Int = 2
const val METADATA_BACKUP_DATABASE_VERSION: Int = 4
const val MAX_METADATA_BACKUP_ARCHIVE_BYTES: Int = 10 * 1024 * 1024
const val MAX_METADATA_BACKUP_PAYLOAD_BYTES: Int = 8 * 1024 * 1024
const val METADATA_BACKUP_ENTRY_NAME: String = "metadata.pb"

data class MetadataBackupSummary(
    val works: Int,
    val collections: Int,
    val manifestations: Int,
    val readingStates: Int,
    val bookmarks: Int,
    val annotations: Int,
    val history: Int,
    val savedSearches: Int = 0,
    val savedSearchHits: Int = 0,
)

data class MetadataBackupExport(
    val archiveBytes: ByteArray,
    val summary: MetadataBackupSummary,
    val createdAt: Instant,
    val suggestedFileName: String = "paper-reader-metadata.paperreader.backup",
)

sealed interface MetadataBackupExportResult {
    data class Success(val export: MetadataBackupExport) : MetadataBackupExportResult
    data class Rejected(val error: MetadataBackupError.Rejected) : MetadataBackupExportResult
}

data class MetadataRestoreIssue(val code: String, val detail: String)

data class MetadataRestorePreview(
    val summary: MetadataBackupSummary,
    val createdAt: Instant,
    val newWorks: Int,
    val mergedWorks: Int,
    val skippedWorks: Int,
    val conflicts: List<MetadataRestoreIssue>,
    val missingProviders: Set<String>,
    val dormantReadingStates: Int,
    val dormantBookmarks: Int,
    val dormantAnnotations: Int,
    /** Non-work records intentionally omitted because their work or annotation identity conflicts. */
    val skippedRecords: Int = 0,
)

sealed interface MetadataBackupError {
    data class Rejected(val code: String, val detail: String) : MetadataBackupError
}

sealed interface MetadataRestorePreviewResult {
    data class Ready(val preview: MetadataRestorePreview) : MetadataRestorePreviewResult
    data class Rejected(val error: MetadataBackupError.Rejected) : MetadataRestorePreviewResult
}

sealed interface MetadataRestoreResult {
    data class Applied(
        val preview: MetadataRestorePreview,
        val appliedWorks: Int,
        val skippedWorks: Int,
        val appliedBookmarks: Int,
        val appliedAnnotations: Int,
        val skippedRecords: Int,
        val appliedSavedSearches: Int = 0,
        val appliedSavedSearchHits: Int = 0,
    ) : MetadataRestoreResult

    data class Rejected(val error: MetadataBackupError.Rejected) : MetadataRestoreResult
}

@Serializable
internal data class MetadataBackupProto(
    @ProtoNumber(1) val formatMarker: String,
    @ProtoNumber(2) val schemaVersion: Int,
    @ProtoNumber(3) val databaseVersion: Int,
    @ProtoNumber(4) val createdAtEpochMillis: Long,
    @ProtoNumber(5) val works: List<BackupWorkProto> = emptyList(),
    @ProtoNumber(6) val collections: List<BackupCollectionProto> = emptyList(),
    @ProtoNumber(7) val memberships: List<BackupMembershipProto> = emptyList(),
    @ProtoNumber(8) val readingStates: List<BackupReadingStateProto> = emptyList(),
    @ProtoNumber(9) val histories: List<BackupHistoryProto> = emptyList(),
    @ProtoNumber(10) val bookmarks: List<BackupBookmarkProto> = emptyList(),
    @ProtoNumber(11) val annotations: List<BackupAnnotationProto> = emptyList(),
    @ProtoNumber(12) val savedSearches: List<BackupSavedSearchProto> = emptyList(),
)

@Serializable
internal data class BackupWorkProto(
    @ProtoNumber(1) val sourceId: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val abstractText: String? = null,
    @ProtoNumber(4) val subjects: List<String> = emptyList(),
    @ProtoNumber(5) val publishedDateEpochDay: Long? = null,
    @ProtoNumber(6) val createdAtEpochMillis: Long,
    @ProtoNumber(7) val updatedAtEpochMillis: Long,
    @ProtoNumber(8) val authors: List<BackupAuthorProto> = emptyList(),
    @ProtoNumber(9) val identifiers: List<BackupIdentifierProto> = emptyList(),
    @ProtoNumber(10) val manifestations: List<BackupManifestationProto> = emptyList(),
)

@Serializable
internal data class BackupAuthorProto(
    @ProtoNumber(1) val position: Int,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val givenName: String? = null,
    @ProtoNumber(4) val familyName: String? = null,
    @ProtoNumber(5) val orcid: String? = null,
)

@Serializable
internal data class BackupIdentifierProto(
    @ProtoNumber(1) val type: String,
    @ProtoNumber(2) val value: String,
    @ProtoNumber(3) val authority: String = "",
)

@Serializable
internal data class BackupManifestationProto(
    @ProtoNumber(1) val sourceId: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val sourceProvider: String,
    @ProtoNumber(4) val sourceRecordId: String,
    @ProtoNumber(5) val version: String? = null,
    @ProtoNumber(6) val landingPageUrl: String? = null,
    @ProtoNumber(7) val pdfUrl: String? = null,
    @ProtoNumber(8) val license: String? = null,
    @ProtoNumber(9) val publishedDateEpochDay: Long? = null,
    @ProtoNumber(10) val updatedAtEpochMillis: Long,
)

@Serializable
internal data class BackupCollectionProto(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val sortOrder: Int,
    @ProtoNumber(3) val createdAtEpochMillis: Long,
    @ProtoNumber(4) val updatedAtEpochMillis: Long,
)

@Serializable
internal data class BackupMembershipProto(
    @ProtoNumber(1) val workSourceId: String,
    @ProtoNumber(2) val collectionName: String,
)

@Serializable
internal data class BackupReadingStateProto(
    @ProtoNumber(1) val workSourceId: String,
    @ProtoNumber(2) val manifestationSourceId: String? = null,
    @ProtoNumber(3) val documentSha256: String? = null,
    @ProtoNumber(4) val blockId: String? = null,
    @ProtoNumber(5) val characterOffset: Int,
    @ProtoNumber(6) val pageIndex: Int? = null,
    @ProtoNumber(7) val progression: Double,
    @ProtoNumber(8) val status: String,
    @ProtoNumber(9) val updatedAtEpochMillis: Long,
)

@Serializable
internal data class BackupHistoryProto(
    @ProtoNumber(1) val workSourceId: String,
    @ProtoNumber(2) val lastReadAtEpochMillis: Long,
    @ProtoNumber(3) val totalReadDurationMillis: Long,
    @ProtoNumber(4) val sessionCount: Int,
)

@Serializable
internal data class BackupBookmarkProto(
    @ProtoNumber(1) val workSourceId: String,
    @ProtoNumber(2) val manifestationSourceId: String,
    @ProtoNumber(3) val documentSha256: String,
    @ProtoNumber(4) val pageIndex: Int,
    @ProtoNumber(5) val createdAtEpochMillis: Long,
)

@Serializable
internal data class BackupAnnotationProto(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val workSourceId: String,
    @ProtoNumber(3) val documentSha256: String,
    @ProtoNumber(4) val blockId: String,
    @ProtoNumber(5) val startOffset: Int,
    @ProtoNumber(6) val endOffset: Int,
    @ProtoNumber(7) val quotePrefix: String,
    @ProtoNumber(8) val quoteExact: String,
    @ProtoNumber(9) val quoteSuffix: String,
    @ProtoNumber(10) val pageIndex: Int? = null,
    @ProtoNumber(11) val note: String? = null,
    @ProtoNumber(12) val color: String? = null,
    @ProtoNumber(13) val createdAtEpochMillis: Long,
    @ProtoNumber(14) val updatedAtEpochMillis: Long,
)

@Serializable
internal data class BackupSavedSearchProto(
    @ProtoNumber(1) val queryText: String,
    @ProtoNumber(2) val createdAtEpochMillis: Long,
    @ProtoNumber(3) val sources: List<BackupSavedSearchSourceProto> = emptyList(),
    @ProtoNumber(4) val hits: List<BackupSavedSearchHitProto> = emptyList(),
)

@Serializable
internal data class BackupSavedSearchSourceProto(
    @ProtoNumber(1) val providerId: String,
    @ProtoNumber(2) val lastCheckedAtEpochMillis: Long? = null,
    @ProtoNumber(3) val lastSuccessAtEpochMillis: Long? = null,
    @ProtoNumber(4) val failureKind: String? = null,
    @ProtoNumber(5) val retryAfterEpochMillis: Long? = null,
)

@Serializable
internal data class BackupSavedSearchHitProto(
    @ProtoNumber(1) val providerId: String,
    @ProtoNumber(2) val providerRecordId: String,
    @ProtoNumber(3) val fingerprint: String,
    @ProtoNumber(4) val recordPayload: String,
    @ProtoNumber(5) val linkedWorkSourceId: String? = null,
    @ProtoNumber(6) val providerUpdatedAtEpochMillis: Long? = null,
    @ProtoNumber(7) val firstSeenAtEpochMillis: Long,
    @ProtoNumber(8) val lastSeenAtEpochMillis: Long,
    @ProtoNumber(9) val unread: Boolean,
)
