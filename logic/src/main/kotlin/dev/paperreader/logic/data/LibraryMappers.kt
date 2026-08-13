package dev.paperreader.logic.data

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.provider.RemotePaper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

internal fun WorkEntity.toDomain(
    authors: List<AuthorEntity>,
    identifiers: List<IdentifierEntity>,
    manifestations: List<ManifestationEntity>,
    readingState: ReadingStateEntity?,
    files: List<FileEntity> = emptyList(),
    collections: List<CollectionEntity> = emptyList(),
    annotationCount: Int = 0,
): LibraryPaper = LibraryPaper(
    work = toDomain(authors, identifiers),
    manifestations = manifestations.sortedBy { it.id }.map(ManifestationEntity::toDomain),
    readingState = readingState?.toDomain(),
    localArtifacts = files.mapNotNull(FileEntity::toDomain).associateBy { it.manifestationId },
    collectionIds = collections.map { CollectionId(it.id) }.toSet(),
    annotationCount = annotationCount,
)

internal fun LibraryPaperAggregate.toDomain(): LibraryPaper = work.toDomain(
    authors = authors,
    identifiers = identifiers,
    manifestations = manifestations.map { it.manifestation },
    readingState = readingState,
    files = manifestations.flatMap { it.files },
    collections = collections,
    annotationCount = annotationCount,
)

internal fun CollectionEntity.toDomain() = PaperCollection(
    id = CollectionId(id),
    name = name,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

internal fun WorkEntity.toDomain(
    authors: List<AuthorEntity>,
    identifiers: List<IdentifierEntity>,
): PaperWork = PaperWork(
    id = WorkId(id),
    title = title,
    abstractText = abstractText,
    subjects = subjects.split(SUBJECT_SEPARATOR).filter(String::isNotBlank).toSet(),
    authors = authors.sortedBy { it.position }.map { PaperAuthor(it.displayName, it.givenName, it.familyName, it.orcid) },
    identifiers = identifiers.map(IdentifierEntity::toDomain).toSet(),
    publishedDate = publishedDateEpochDay?.let(LocalDate::ofEpochDay),
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

private fun IdentifierEntity.toDomain() = PaperIdentifier(
    type = IdentifierType.valueOf(type),
    value = value,
    authority = authority.takeIf(String::isNotEmpty),
)

private fun ManifestationEntity.toDomain() = PaperManifestation(
    id = ManifestationId(id), workId = WorkId(workId), type = ManifestationType.valueOf(type),
    sourceProvider = sourceProvider, sourceRecordId = sourceRecordId, version = version,
    landingPageUrl = landingPageUrl, pdfUrl = pdfUrl, license = license,
    publishedDate = publishedDateEpochDay?.let(LocalDate::ofEpochDay),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

private fun ReadingStateEntity.toDomain() = ReadingState(
    workId = WorkId(workId), manifestationId = manifestationId?.let(::ManifestationId),
    locator = ReadingLocator(documentSha256, blockId, characterOffset, pageIndex, progression),
    status = ReadingStatus.valueOf(status), updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

internal fun FileEntity.toDomain(): dev.paperreader.logic.domain.LocalPaperArtifact? {
    val path = localPath ?: return null
    val hash = sha256 ?: return null
    val length = byteLength ?: return null
    return dev.paperreader.logic.domain.LocalPaperArtifact(
        id = id,
        manifestationId = ManifestationId(manifestationId),
        storagePath = path,
        sha256 = hash,
        byteLength = length,
        mimeType = mimeType,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
}

internal fun ReadingState.toEntity() = ReadingStateEntity(
    workId = workId.value, manifestationId = manifestationId?.value,
    documentSha256 = locator.documentSha256, blockId = locator.blockId,
    characterOffset = locator.characterOffset, pageIndex = locator.pageIndex,
    progression = locator.progression, status = status.name, updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

internal fun RemotePaper.toEntities(workId: WorkId, now: Instant): RemotePaperEntities {
    val identifiers = (identifiers + PaperIdentifier(IdentifierType.PROVIDER, providerRecordId, providerId))
        .map(IdentifierNormalizer::canonical).distinctBy { IdentityResolver.exactKeys(listOf(it)).single() }
    return RemotePaperEntities(
        work = WorkEntity(workId.value, title, abstractText, subjects.joinToString(SUBJECT_SEPARATOR), publishedDate?.toEpochDay(), now.toEpochMilli(), (updatedAt ?: now).toEpochMilli()),
        authors = authors.mapIndexed { index, author -> AuthorEntity(workId.value, index, author.displayName, author.givenName, author.familyName, author.orcid) },
        identifiers = identifiers.map { IdentifierEntity(workId.value, it.type.name, it.value, it.authority.orEmpty()) },
        manifestations = manifestations.mapIndexed { index, manifestation ->
            ManifestationEntity(
                id = "m-" + sha256("${workId.value}|$providerId|$providerRecordId|$index"),
                workId = workId.value, type = manifestation.type.name, sourceProvider = providerId,
                sourceRecordId = providerRecordId, version = manifestation.version,
                landingPageUrl = manifestation.landingPageUrl, pdfUrl = manifestation.pdfUrl,
                license = manifestation.license, publishedDateEpochDay = manifestation.publishedDate?.toEpochDay(),
                updatedAtEpochMillis = (updatedAt ?: now).toEpochMilli(),
            )
        },
    )
}

internal data class RemotePaperEntities(
    val work: WorkEntity,
    val authors: List<AuthorEntity>,
    val identifiers: List<IdentifierEntity>,
    val manifestations: List<ManifestationEntity>,
)

internal fun mergeIdentifiers(
    existing: List<IdentifierEntity>,
    incoming: List<IdentifierEntity>,
): List<IdentifierEntity> = (existing + incoming).distinctBy { listOf(it.workId, it.type, it.value, it.authority) }

internal fun mergeManifestations(
    existing: List<ManifestationEntity>,
    incoming: List<ManifestationEntity>,
): List<ManifestationEntity> = (existing + incoming).associateBy { it.id }.values.toList()

internal fun mergeWork(existing: WorkEntity?, incoming: WorkEntity): WorkEntity {
    if (existing == null) return incoming
    val subjects = (existing.subjects.split(SUBJECT_SEPARATOR) + incoming.subjects.split(SUBJECT_SEPARATOR))
        .filter(String::isNotBlank)
        .toSortedSet()
        .joinToString(SUBJECT_SEPARATOR)
    return incoming.copy(
        abstractText = incoming.abstractText ?: existing.abstractText,
        subjects = subjects,
        publishedDateEpochDay = incoming.publishedDateEpochDay ?: existing.publishedDateEpochDay,
        createdAtEpochMillis = existing.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(existing.updatedAtEpochMillis, incoming.updatedAtEpochMillis),
    )
}

internal fun stableWorkId(identifiers: Iterable<PaperIdentifier>): WorkId {
    val key = IdentityResolver.exactKeys(identifiers).sorted().firstOrNull()
        ?: error("A paper must have at least one exact identifier")
    return WorkId("w-" + sha256(key))
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private const val SUBJECT_SEPARATOR = "\u001f"
