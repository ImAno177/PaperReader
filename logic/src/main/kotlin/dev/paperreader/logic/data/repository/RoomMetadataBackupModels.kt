package dev.paperreader.logic.data.repository

import dev.paperreader.logic.backup.BackupAnnotationProto
import dev.paperreader.logic.backup.BackupAuthorProto
import dev.paperreader.logic.backup.BackupManifestationProto
import dev.paperreader.logic.backup.BackupWorkProto
import dev.paperreader.logic.backup.MetadataBackupError
import dev.paperreader.logic.backup.MetadataBackupProto
import dev.paperreader.logic.backup.MetadataBackupSummary
import dev.paperreader.logic.backup.MetadataRestoreIssue
import dev.paperreader.logic.data.AnnotationEntity
import dev.paperreader.logic.data.AuthorEntity
import dev.paperreader.logic.data.IdentifierEntity
import dev.paperreader.logic.data.LocalDocumentAnchorRow
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.SavedSearchEntity
import dev.paperreader.logic.data.SavedSearchHitEntity
import dev.paperreader.logic.data.SavedSearchSourceEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import java.security.MessageDigest
import java.util.Locale

internal data class AliasKey(val type: String, val value: String, val authority: String)

internal data class ManifestationKey(
    val provider: String,
    val record: String,
    val type: String,
    val version: String?,
)

internal data class ManifestationRef(val workSourceId: String, val manifestationSourceId: String)

internal data class LocalAnchor(val manifestationId: String, val documentSha256: String)

internal data class WorkDocumentHash(val workId: String, val documentSha256: String)

internal data class BookmarkKey(
    val workId: String,
    val manifestationId: String,
    val documentSha256: String,
    val pageIndex: Int,
)

internal data class SavedSearchHitKey(val providerId: String, val providerRecordId: String)

internal data class ManifestationPlan(
    val source: BackupManifestationProto,
    val targetId: ManifestationId,
    val existing: ManifestationEntity?,
)

internal data class WorkPlan(
    val source: BackupWorkProto,
    val identifiers: List<PaperIdentifier>,
    val targetId: WorkId?,
    val existing: WorkEntity?,
    val hasExistingAuthors: Boolean,
    val manifestations: List<ManifestationPlan>,
    val conflict: MetadataRestoreIssue?,
) {
    val eligible: Boolean get() = conflict == null && targetId != null
}

internal data class SavedSearchPlan(
    val search: SavedSearchEntity,
    val sources: List<SavedSearchSourceEntity>,
    val hits: List<SavedSearchHitEntity>,
)

internal data class RestorePlan(
    val proto: MetadataBackupProto,
    val preview: dev.paperreader.logic.backup.MetadataRestorePreview,
    val works: List<WorkPlan>,
    val memberships: List<dev.paperreader.logic.backup.BackupMembershipProto>,
    val readingStates: List<dev.paperreader.logic.data.ReadingStateEntity>,
    val histories: List<dev.paperreader.logic.data.ReadingHistoryEntity>,
    val bookmarks: List<dev.paperreader.logic.data.ReadingBookmarkEntity>,
    val annotations: List<AnnotationEntity>,
    val savedSearches: List<SavedSearchPlan>,
)

internal fun maxNullable(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

internal fun canonicalIdentifiers(work: BackupWorkProto): List<PaperIdentifier> = work.identifiers
    .map { identifier ->
        IdentifierNormalizer.canonical(
            PaperIdentifier(
                type = dev.paperreader.logic.domain.IdentifierType.valueOf(identifier.type),
                value = identifier.value,
                authority = identifier.authority.takeIf(String::isNotBlank),
            ),
        )
    }
    .distinctBy { IdentityResolver.exactKeys(listOf(it)).single() }

internal fun BackupWorkProto.toEntity(workId: WorkId) = WorkEntity(
    id = workId.value,
    title = title,
    abstractText = abstractText,
    subjects = subjects.filter(String::isNotBlank).toSortedSet().joinToString(SUBJECT_SEPARATOR),
    publishedDateEpochDay = publishedDateEpochDay,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun BackupAuthorProto.toEntity(workId: WorkId) = AuthorEntity(
    workId = workId.value,
    position = position,
    displayName = displayName,
    givenName = givenName,
    familyName = familyName,
    orcid = orcid,
)

internal fun PaperIdentifier.toEntity(workId: WorkId) = IdentifierEntity(
    workId = workId.value,
    type = type.name,
    value = value,
    authority = authority.orEmpty(),
)

internal fun canonicalIdentifier(identifier: IdentifierEntity): PaperIdentifier =
    IdentifierNormalizer.canonical(
        PaperIdentifier(
            type = dev.paperreader.logic.domain.IdentifierType.valueOf(identifier.type),
            value = identifier.value,
            authority = identifier.authority.takeIf(String::isNotBlank),
        ),
    )

internal fun PaperIdentifier.toAliasKey() = AliasKey(type.name, value, authority.orEmpty())

internal fun ManifestationPlan.toEntity(workId: WorkId): ManifestationEntity {
    val incoming = ManifestationEntity(
        id = targetId.value,
        workId = workId.value,
        type = source.type,
        sourceProvider = source.sourceProvider,
        sourceRecordId = source.sourceRecordId,
        version = source.version,
        landingPageUrl = source.landingPageUrl,
        pdfUrl = source.pdfUrl,
        license = source.license,
        publishedDateEpochDay = source.publishedDateEpochDay,
        updatedAtEpochMillis = source.updatedAtEpochMillis,
    )
    return existing?.let { current ->
        incoming.copy(
            version = current.version ?: incoming.version,
            landingPageUrl = current.landingPageUrl ?: incoming.landingPageUrl,
            pdfUrl = current.pdfUrl ?: incoming.pdfUrl,
            license = current.license ?: incoming.license,
            publishedDateEpochDay = current.publishedDateEpochDay ?: incoming.publishedDateEpochDay,
            updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, incoming.updatedAtEpochMillis),
        )
    } ?: incoming
}

internal fun AnnotationEntity.sameAnchorAs(source: BackupAnnotationProto, targetWorkId: String): Boolean =
    workId == targetWorkId &&
        documentSha256.lowercase(Locale.ROOT) == source.documentSha256 &&
        blockId == source.blockId &&
        startOffset == source.startOffset &&
        endOffset == source.endOffset &&
        quoteExact == source.quoteExact &&
        pageIndex == source.pageIndex

internal fun BackupAnnotationProto.toEntity(targetWorkId: String) = AnnotationEntity(
    id = id,
    workId = targetWorkId,
    documentSha256 = documentSha256,
    blockId = blockId,
    startOffset = startOffset,
    endOffset = endOffset,
    quotePrefix = quotePrefix,
    quoteExact = quoteExact,
    quoteSuffix = quoteSuffix,
    pageIndex = pageIndex,
    note = note,
    color = color,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun LocalDocumentAnchorRow.toAnchor() = LocalAnchor(manifestationId, documentSha256)

internal fun summary(proto: MetadataBackupProto) = MetadataBackupSummary(
    works = proto.works.size,
    collections = proto.collections.size,
    manifestations = proto.works.sumOf { it.manifestations.size },
    readingStates = proto.readingStates.size,
    bookmarks = proto.bookmarks.size,
    annotations = proto.annotations.size,
    history = proto.histories.size,
    savedSearches = proto.savedSearches.size,
    savedSearchHits = proto.savedSearches.sumOf { it.hits.size },
)

internal fun invalidLocalDataError() = MetadataBackupError.Rejected(
    code = "invalid_local_data",
    detail = "Local metadata contains values that cannot be backed up safely",
)

internal fun manifestationKey(value: BackupManifestationProto) = ManifestationKey(
    provider = value.sourceProvider.lowercase(Locale.ROOT),
    record = value.sourceRecordId.normalizedManifestationRecord(value.sourceProvider),
    type = value.type,
    version = value.version,
)

internal fun manifestationKey(value: ManifestationEntity) = ManifestationKey(
    provider = value.sourceProvider.lowercase(Locale.ROOT),
    record = value.sourceRecordId.normalizedManifestationRecord(value.sourceProvider),
    type = value.type,
    version = value.version,
)

internal fun BackupWorkProto.localPdfSha256OrNull(): String? = manifestations
    .asSequence()
    .filter { it.sourceProvider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true) }
    .map { it.sourceRecordId.lowercase(Locale.ROOT) }
    .filter { it.matches(SHA256_REGEX) }
    .distinct()
    .toList()
    .singleOrNull()

internal fun String.normalizedManifestationRecord(provider: String): String =
    if (provider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true)) lowercase(Locale.ROOT) else this

internal fun stableRestoredManifestationId(workId: WorkId, key: ManifestationKey): String =
    if (key.provider == LOCAL_PDF_SOURCE_ID && key.record.matches(SHA256_REGEX)) {
        "m-local-${key.record}"
    } else {
        stableManifestationId(workId, key)
    }

internal fun stableManifestationId(workId: WorkId, key: ManifestationKey): String = "m-" + sha256(
    listOf(workId.value, key.provider, key.record, key.type, key.version.orEmpty()).joinToString(HASH_SEPARATOR),
)

internal fun mergeSubjects(existing: String, incoming: String): String =
    (existing.split(SUBJECT_SEPARATOR) + incoming.split(SUBJECT_SEPARATOR))
        .filter(String::isNotBlank)
        .toSortedSet()
        .joinToString(SUBJECT_SEPARATOR)

internal fun mergeSavedSearchSource(
    existing: SavedSearchSourceEntity?,
    incoming: SavedSearchSourceEntity,
): SavedSearchSourceEntity {
    if (existing == null) return incoming
    val incomingIsNewer = (incoming.lastCheckedAtEpochMillis ?: Long.MIN_VALUE) >
        (existing.lastCheckedAtEpochMillis ?: Long.MIN_VALUE)
    val status = if (incomingIsNewer) incoming else existing
    return status.copy(
        searchId = incoming.searchId,
        providerId = incoming.providerId,
        lastCheckedAtEpochMillis = maxNullable(existing.lastCheckedAtEpochMillis, incoming.lastCheckedAtEpochMillis),
        lastSuccessAtEpochMillis = maxNullable(existing.lastSuccessAtEpochMillis, incoming.lastSuccessAtEpochMillis),
    )
}

internal fun mergeSavedSearchHit(
    existing: SavedSearchHitEntity?,
    incoming: SavedSearchHitEntity?,
): SavedSearchHitEntity = when {
    existing == null -> checkNotNull(incoming)
    incoming == null -> existing
    else -> {
        val incomingFreshness = incoming.providerUpdatedAtEpochMillis ?: incoming.lastSeenAtEpochMillis
        val existingFreshness = existing.providerUpdatedAtEpochMillis ?: existing.lastSeenAtEpochMillis
        val metadata = if (incomingFreshness > existingFreshness) incoming else existing
        metadata.copy(
            id = incoming.id,
            searchId = incoming.searchId,
            providerId = incoming.providerId,
            providerRecordId = incoming.providerRecordId,
            linkedWorkId = existing.linkedWorkId ?: incoming.linkedWorkId,
            firstSeenAtEpochMillis = minOf(existing.firstSeenAtEpochMillis, incoming.firstSeenAtEpochMillis),
            lastSeenAtEpochMillis = maxOf(existing.lastSeenAtEpochMillis, incoming.lastSeenAtEpochMillis),
            unread = existing.unread,
        )
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private const val SUBJECT_SEPARATOR = "\u001f"
private const val HASH_SEPARATOR = "\u001e"
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
