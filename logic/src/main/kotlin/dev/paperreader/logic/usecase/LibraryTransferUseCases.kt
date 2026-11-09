package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.interop.BibliographyCodec
import dev.paperreader.logic.interop.BibliographyDate
import dev.paperreader.logic.interop.BibliographyEntry
import dev.paperreader.logic.interop.BibliographyError
import dev.paperreader.logic.interop.BibliographyFormat
import dev.paperreader.logic.interop.BibliographyParseResult
import dev.paperreader.logic.interop.stableBibliographyRecordId
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.paperreader.logic.domain.repository.ExactIdentityConflictException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ExportBibliography(private val library: LibraryRepository) {
    suspend fun export(format: BibliographyFormat): String = withContext(Dispatchers.Default) {
        BibliographyCodec.encode(format, library.library.first().map(LibraryPaper::toBibliographyEntry))
    }
}

enum class BibliographyImportDisposition {
    NEW,
    EXACT_MATCH,
    AMBIGUOUS,
}

data class BibliographyImportCandidate(
    val index: Int,
    val entry: BibliographyEntry,
    val stableRecordId: String,
    val matchedWorkIds: Set<WorkId>,
    val disposition: BibliographyImportDisposition,
)

data class BibliographyImportPreview(
    val format: BibliographyFormat,
    val candidates: List<BibliographyImportCandidate>,
    val error: BibliographyError? = null,
    val partialDatePrecisionLossIndices: List<Int> = emptyList(),
)

class PreviewBibliographyImport(private val library: LibraryRepository) {
    suspend fun preview(format: BibliographyFormat, input: ByteArray): BibliographyImportPreview = withContext(Dispatchers.Default) {
        val parsed = BibliographyCodec.parse(format, input)
        if (parsed is BibliographyParseResult.Failure) {
            return@withContext BibliographyImportPreview(format, emptyList(), parsed.error)
        }
        val entries = (parsed as BibliographyParseResult.Success).entries
        val papers = library.library.first()
        val candidates = entries.mapIndexed { index, entry ->
            val stableId = stableBibliographyRecordId(entry)
            val incoming = entry.identifiers + PaperIdentifier(
                IdentifierType.PROVIDER,
                stableId,
                BIBLIOGRAPHY_IMPORT_PROVIDER,
            )
            val matched = papers.filter { paper -> hasExactIdentity(paper, incoming) }
                .mapTo(linkedSetOf()) { it.work.id }
            BibliographyImportCandidate(
                index = index,
                entry = entry,
                stableRecordId = stableId,
                matchedWorkIds = matched,
                disposition = when {
                    matched.size > 1 -> BibliographyImportDisposition.AMBIGUOUS
                    matched.size == 1 -> BibliographyImportDisposition.EXACT_MATCH
                    else -> BibliographyImportDisposition.NEW
                },
            )
        }
        return@withContext BibliographyImportPreview(
            format = format,
            candidates = candidates,
            partialDatePrecisionLossIndices = entries.mapIndexedNotNull { index, entry ->
                index.takeIf { entry.publishedAt?.month != null && entry.publishedAt.day == null }
            },
        )
    }
}

enum class BibliographyImportOutcome {
    IMPORTED,
    MERGED_BY_EXACT_IDENTIFIER,
    SKIPPED_AMBIGUOUS,
}

data class BibliographyImportRecordResult(
    val index: Int,
    val outcome: BibliographyImportOutcome,
    val workId: WorkId? = null,
)

data class BibliographyImportReport(
    val records: List<BibliographyImportRecordResult>,
    val error: BibliographyError? = null,
    val partialDatePrecisionLossIndices: List<Int> = emptyList(),
) {
    val importedCount: Int get() = records.count { it.outcome == BibliographyImportOutcome.IMPORTED }
    val mergedCount: Int get() = records.count { it.outcome == BibliographyImportOutcome.MERGED_BY_EXACT_IDENTIFIER }
    val skippedCount: Int get() = records.count { it.outcome == BibliographyImportOutcome.SKIPPED_AMBIGUOUS }
}

class ImportBibliography(private val library: LibraryRepository) {
    suspend fun import(preview: BibliographyImportPreview): BibliographyImportReport = withContext(Dispatchers.IO) {
        if (preview.error != null) {
            return@withContext BibliographyImportReport(emptyList(), preview.error, preview.partialDatePrecisionLossIndices)
        }
        val results = mutableListOf<BibliographyImportRecordResult>()
        val aliasOwners = mutableMapOf<String, MutableSet<WorkId>>()
        for (paper in library.library.first()) {
            for (key in IdentityResolver.exactKeys(paper.work.identifiers)) {
                aliasOwners.getOrPut(key) { linkedSetOf() }.add(paper.work.id)
            }
        }
        for (candidate in preview.candidates) {
            currentCoroutineContext().ensureActive()
            val incoming = candidate.entry.identifiers + PaperIdentifier(
                IdentifierType.PROVIDER,
                candidate.stableRecordId,
                BIBLIOGRAPHY_IMPORT_PROVIDER,
            )
            val incomingKeys = IdentityResolver.exactKeys(incoming)
            val matched = incomingKeys.flatMap { aliasOwners[it].orEmpty() }.toSet()
            if (matched.size > 1) {
                results += BibliographyImportRecordResult(
                    candidate.index,
                    BibliographyImportOutcome.SKIPPED_AMBIGUOUS,
                )
                continue
            }
            val workId = try {
                library.save(candidate.entry.toRemotePaper(candidate.stableRecordId))
            } catch (_: ExactIdentityConflictException) {
                results += BibliographyImportRecordResult(candidate.index, BibliographyImportOutcome.SKIPPED_AMBIGUOUS)
                continue
            }
            for (key in incomingKeys) aliasOwners.getOrPut(key) { linkedSetOf() }.add(workId)
            results += BibliographyImportRecordResult(
                index = candidate.index,
                outcome = if (matched.isEmpty()) BibliographyImportOutcome.IMPORTED
                else BibliographyImportOutcome.MERGED_BY_EXACT_IDENTIFIER,
                workId = workId,
            )
        }
        return@withContext BibliographyImportReport(
            records = results,
            partialDatePrecisionLossIndices = preview.partialDatePrecisionLossIndices,
        )
    }
}

const val BIBLIOGRAPHY_IMPORT_PROVIDER = "bibliography-import"

private fun hasExactIdentity(paper: LibraryPaper, identifiers: Set<PaperIdentifier>): Boolean {
    val paperKeys = IdentityResolver.exactKeys(paper.work.identifiers)
    val incomingKeys = IdentityResolver.exactKeys(identifiers)
    return paperKeys.intersect(incomingKeys).isNotEmpty()
}

private fun BibliographyEntry.toRemotePaper(stableId: String): RemotePaper {
    val date = publishedAt
    val fullDate = date?.toLocalDateOrNull()
    val safeUrl = sourceUrl?.takeIf(::isSafeBibliographyUrl)
    return RemotePaper(
        providerId = BIBLIOGRAPHY_IMPORT_PROVIDER,
        providerRecordId = stableId,
        title = title,
        abstractText = abstractText,
        authors = authors.map(::PaperAuthor),
        identifiers = identifiers.map(IdentifierNormalizer::canonical).toCollection(linkedSetOf()),
        publishedDate = fullDate,
        publicationYear = date?.year,
        manifestations = safeUrl?.let {
            listOf(
                RemoteManifestation(
                    type = ManifestationType.OTHER,
                    landingPageUrl = it,
                    publishedDate = fullDate,
                ),
            )
        }.orEmpty(),
    )
}

private fun LibraryPaper.toBibliographyEntry(): BibliographyEntry {
    val identifiers = work.identifiers.asSequence()
        .filter { it.type != IdentifierType.PROVIDER }
        .map(IdentifierNormalizer::canonical)
        .toCollection(linkedSetOf())
    val date = work.publishedDate?.let { BibliographyDate(it.year, it.monthValue, it.dayOfMonth) }
        ?: work.publicationYear?.let(::BibliographyDate)
    val url = manifestations.asSequence().mapNotNull { it.landingPageUrl }
        .firstOrNull(::isSafeBibliographyUrl)
    return BibliographyEntry(
        title = work.title.trim(),
        authors = work.authors.map(PaperAuthor::displayName).map(String::trim).filter(String::isNotEmpty),
        identifiers = identifiers,
        abstractText = work.abstractText?.takeIf(String::isNotBlank),
        publishedAt = date,
        sourceUrl = url,
        entryType = "misc",
    )
}

private fun isSafeBibliographyUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)
