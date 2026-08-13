package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.usecase.SearchResultCluster
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.net.URI

data class ManifestationUi(
    val id: String,
    val type: ManifestationType,
    val source: String,
    val version: String?,
    val publishedDate: LocalDate?,
    val landingPageUrl: String?,
    val pdfUrl: String?,
    val license: String?,
    val localCopy: LocalCopyUi?,
)

data class LocalCopyUi(
    val sha256: String,
    val byteLength: Long,
    val updatedAt: Instant,
)

data class PaperUi(
    val id: String,
    val title: String,
    val authors: List<String>,
    val savedAt: Instant,
    val updatedAt: Instant,
    val publishedDate: LocalDate?,
    val sources: List<String>,
    val primaryIdentifier: PaperIdentifier?,
    val identifiers: List<PaperIdentifier>,
    val abstractText: String?,
    val progress: Float,
    val status: ReadingStatus,
    val subjects: List<String>,
    val manifestations: List<ManifestationUi>,
    val collectionIds: Set<Long> = emptySet(),
    val annotationCount: Int = 0,
)

data class PaperCollectionUi(
    val id: Long,
    val name: String,
)

data class SearchPaperUi(
    val key: String,
    val title: String,
    val authors: List<String>,
    val publishedDate: LocalDate?,
    val sources: List<String>,
    val sourceDisplayNames: List<String> = sources.map(String::displayProviderName),
    val primaryIdentifier: PaperIdentifier?,
    val abstractText: String?,
    val records: List<RemotePaper>,
)

data class ReadingHistoryUi(
    val workId: String,
    val title: String,
    val lastReadAt: Instant,
    val totalReadDuration: Duration,
    val sessionCount: Int,
    val progression: Float,
)

fun LibraryPaper.toPaperUi(): PaperUi {
    val orderedIdentifiers = work.identifiers.sortedWith(identifierComparator)
    val orderedManifestations = manifestations
        .sortedWith(
            compareBy<dev.paperreader.logic.domain.PaperManifestation>(
                { manifestationPriority.getValue(it.type) },
                { it.publishedDate },
                { it.sourceProvider.lowercase() },
                { it.id.value },
            ),
        )
        .map { manifestation ->
            ManifestationUi(
                id = manifestation.id.value,
                type = manifestation.type,
                source = manifestation.sourceProvider,
                version = manifestation.version,
                publishedDate = manifestation.publishedDate,
                landingPageUrl = manifestation.landingPageUrl,
                pdfUrl = manifestation.pdfUrl,
                license = manifestation.license,
                localCopy = localArtifacts[manifestation.id]?.let { artifact ->
                    LocalCopyUi(
                        sha256 = artifact.sha256,
                        byteLength = artifact.byteLength,
                        updatedAt = artifact.updatedAt,
                    )
                },
            )
        }
    return PaperUi(
        id = work.id.value,
        title = work.title,
        authors = work.authors.map { it.displayName },
        savedAt = work.createdAt,
        updatedAt = work.updatedAt,
        publishedDate = work.publishedDate,
        sources = orderedManifestations.map(ManifestationUi::source).distinct(),
        primaryIdentifier = orderedIdentifiers.firstOrNull(),
        identifiers = orderedIdentifiers,
        abstractText = work.abstractText?.takeIf(String::isNotBlank),
        progress = readingState?.locator?.progression?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
        status = readingState?.status ?: ReadingStatus.UNREAD,
        subjects = work.subjects.sorted(),
        manifestations = orderedManifestations,
        collectionIds = collectionIds.map { it.value }.toSet(),
        annotationCount = annotationCount,
    )
}

fun PaperCollection.toPaperCollectionUi() = PaperCollectionUi(id.value, name)

fun SearchResultCluster.toSearchPaperUi(providerNames: Map<String, String> = emptyMap()): SearchPaperUi {
    val orderedRecords = records.sortedWith(
        compareByDescending<RemotePaper> { !it.abstractText.isNullOrBlank() }
            .thenByDescending { it.identifiers.size }
            .thenByDescending { it.manifestations.size }
            .thenBy { it.providerId.lowercase() }
            .thenBy { it.providerRecordId },
    )
    val primary = orderedRecords.first()
    val identifiers = orderedRecords.flatMap { it.identifiers }.distinct().sortedWith(identifierComparator)
    return SearchPaperUi(
        key = orderedRecords.joinToString("|") { "${it.providerId}:${it.providerRecordId}" },
        title = primary.title,
        authors = primary.authors.map { it.displayName },
        publishedDate = primary.publishedDate,
        sources = orderedRecords.map { it.providerId }.distinct().sorted(),
        sourceDisplayNames = orderedRecords.map { record ->
            providerNames[record.providerId] ?: record.providerId.displayProviderName()
        }.distinct().sorted(),
        primaryIdentifier = identifiers.firstOrNull(),
        abstractText = orderedRecords.firstNotNullOfOrNull { it.abstractText?.takeIf(String::isNotBlank) },
        records = orderedRecords,
    )
}

fun ReadingHistoryEntry.toReadingHistoryUi() = ReadingHistoryUi(
    workId = workId.value,
    title = title,
    lastReadAt = lastReadAt,
    totalReadDuration = totalReadDuration,
    sessionCount = sessionCount,
    progression = progression.toFloat().coerceIn(0f, 1f),
)

fun PaperIdentifier.displayValue(): String = when (type) {
    IdentifierType.DOI -> "doi:$value"
    IdentifierType.ARXIV -> "arXiv:$value"
    IdentifierType.PMID -> "PMID:$value"
    IdentifierType.PMCID -> "PMCID:$value"
    IdentifierType.PROVIDER -> "${authority.orEmpty()}:$value"
}

fun String.displayProviderName(): String = when (lowercase()) {
    "arxiv" -> "arXiv"
    "crossref" -> "Crossref"
    "local-pdf" -> "Local PDF"
    else -> this
}

fun String.safeWebUrlOrNull(): String? = takeIf {
    runCatching {
        val uri = URI(it)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private val identifierPriority = mapOf(
    IdentifierType.DOI to 0,
    IdentifierType.ARXIV to 1,
    IdentifierType.PMID to 2,
    IdentifierType.PMCID to 3,
    IdentifierType.PROVIDER to 4,
)

private val identifierComparator = compareBy<PaperIdentifier>(
    { identifierPriority.getValue(it.type) },
    { it.authority.orEmpty().lowercase() },
    { it.value.lowercase() },
)

private val manifestationPriority = mapOf(
    ManifestationType.VERSION_OF_RECORD to 0,
    ManifestationType.ACCEPTED_MANUSCRIPT to 1,
    ManifestationType.PREPRINT to 2,
    ManifestationType.OTHER to 3,
)
