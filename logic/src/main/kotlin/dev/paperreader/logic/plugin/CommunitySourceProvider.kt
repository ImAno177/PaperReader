package dev.paperreader.logic.plugin

import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.CitationMetrics
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal interface SourceExtensionTransport {
    val descriptor: SourceExtensionDescriptor

    suspend fun search(request: SourceSearchRequest): SourceSearchPage

    suspend fun getPaper(request: SourceGetPaperRequest): SourcePaperRecord?
}

internal class CommunitySourceProvider(
    private val transport: SourceExtensionTransport,
) : PaperProvider {
    override val descriptor = ProviderDescriptor(
        id = transport.descriptor.providerId,
        displayName = transport.descriptor.displayName,
        minimumRequestIntervalMillis = transport.descriptor.minimumRequestIntervalMillis,
        roles = transport.descriptor.roles.mapTo(linkedSetOf()) { it.toProviderRole() },
        capabilities = buildSet {
            if (dev.paperreader.extensions.api.SourceCapability.SEARCH in transport.descriptor.capabilities) {
                add(ProviderCapability.DISCOVERY)
            }
            if (dev.paperreader.extensions.api.SourceCapability.DETAILS in transport.descriptor.capabilities) {
                add(ProviderCapability.METADATA_RESOLUTION)
            }
        },
        identifierLookupTypes = transport.descriptor.identifierLookupTypes.mapTo(linkedSetOf()) {
            it.toIdentifierType()
        },
        supportedSorts = transport.descriptor.supportedSorts.mapTo(linkedSetOf()) { it.toSearchSort() },
    )

    override suspend fun search(query: PaperSearchQuery): ProviderPage = translateFailures {
        val response = transport.search(
            SourceSearchRequest(
                requestId = newRequestId(),
                query = query.text,
                limit = query.limit.coerceAtMost(dev.paperreader.extensions.api.PaperExtensionContract.MAX_RESULTS_PER_PAGE),
                cursor = query.cursor,
                sort = query.sort.toExtensionSort(),
            ),
        )
        ProviderPage(
            items = response.records.map(::toRemotePaper),
            nextCursor = response.nextCursor,
        )
    }

    override suspend fun get(recordId: String): RemotePaper? = translateFailures {
        transport.getPaper(
            SourceGetPaperRequest(
                requestId = newRequestId(),
                providerRecordId = recordId,
            ),
        )?.let(::toRemotePaper)
    }

    private fun toRemotePaper(record: SourcePaperRecord): RemotePaper {
        return try {
            val identifiers = buildSet {
                add(PaperIdentifier(IdentifierType.PROVIDER, record.providerRecordId, descriptor.id))
                record.doi?.takeIf(String::isNotBlank)?.let { add(PaperIdentifier(IdentifierType.DOI, it)) }
                record.arxivId?.takeIf(String::isNotBlank)?.let { add(PaperIdentifier(IdentifierType.ARXIV, it)) }
                record.pmid?.takeIf(String::isNotBlank)?.let { add(PaperIdentifier(IdentifierType.PMID, it)) }
                record.pmcid?.takeIf(String::isNotBlank)?.let { add(PaperIdentifier(IdentifierType.PMCID, it)) }
            }
            identifiers.forEach { identifier ->
                dev.paperreader.logic.domain.identity.IdentifierNormalizer.canonical(identifier)
            }
            RemotePaper(
                providerId = descriptor.id,
                providerRecordId = record.providerRecordId,
                title = record.title,
                abstractText = record.abstractText,
                authors = record.authors.map(::PaperAuthor),
                identifiers = identifiers,
                subjects = record.subjects,
                publishedDate = record.publishedDate?.let(LocalDate::parse),
                updatedAt = record.updatedAt?.let(Instant::parse),
                manifestations = record.manifestations.map { manifestation ->
                    RemoteManifestation(
                        type = when (manifestation.type) {
                            "preprint" -> ManifestationType.PREPRINT
                            "accepted_manuscript" -> ManifestationType.ACCEPTED_MANUSCRIPT
                            "version_of_record" -> ManifestationType.VERSION_OF_RECORD
                            else -> ManifestationType.OTHER
                        },
                        version = manifestation.version,
                        landingPageUrl = manifestation.landingPageUrl,
                        pdfUrl = manifestation.pdfUrl,
                        license = manifestation.license,
                        publishedDate = manifestation.publishedDate?.let(LocalDate::parse),
                    )
                },
                citationMetrics = record.citationCount?.let { count ->
                    CitationMetrics(count = count, sourceId = descriptor.id, observedAt = Instant.now())
                },
            )
        } catch (error: IllegalArgumentException) {
            throw ProviderException.InvalidResponse(error)
        }
    }

    private suspend fun <T> translateFailures(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceExtensionRequestException) {
        throw when (failure.failure.code) {
            ExtensionFailureCode.RATE_LIMITED -> ProviderException.RateLimited(failure.failure.retryAfterMillis)
            ExtensionFailureCode.CANCELLED -> CancellationException(failure.failure.message)
            ExtensionFailureCode.UNAVAILABLE,
            ExtensionFailureCode.INTERNAL_ERROR,
            -> ProviderException.Unavailable(failure)
            ExtensionFailureCode.INVALID_REQUEST,
            ExtensionFailureCode.INVALID_RESPONSE,
            -> ProviderException.InvalidResponse(failure)
        }
    } catch (provider: ProviderException) {
        throw provider
    } catch (error: Exception) {
        throw ProviderException.Unavailable(error)
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()
}

internal class SourceExtensionRequestException(
    val failure: ExtensionFailure,
) : Exception(failure.message)

private fun SearchSort.toExtensionSort(): SourceSearchSort = when (this) {
    SearchSort.RELEVANCE -> SourceSearchSort.RELEVANCE
    SearchSort.NEWEST -> SourceSearchSort.NEWEST
    SearchSort.OLDEST -> SourceSearchSort.OLDEST
}

private fun SourceSearchSort.toSearchSort(): SearchSort = when (this) {
    SourceSearchSort.RELEVANCE -> SearchSort.RELEVANCE
    SourceSearchSort.NEWEST -> SearchSort.NEWEST
    SourceSearchSort.OLDEST -> SearchSort.OLDEST
}

private fun SourceRole.toProviderRole(): ProviderRole = when (this) {
    SourceRole.SEARCH_ENGINE -> ProviderRole.SEARCH_ENGINE
    SourceRole.CONTENT_SOURCE -> ProviderRole.CONTENT_SOURCE
    SourceRole.METADATA_ENGINE -> ProviderRole.METADATA_ENGINE
}

private fun SourceIdentifierType.toIdentifierType(): IdentifierType = when (this) {
    SourceIdentifierType.DOI -> IdentifierType.DOI
    SourceIdentifierType.ARXIV -> IdentifierType.ARXIV
    SourceIdentifierType.PMID -> IdentifierType.PMID
    SourceIdentifierType.PMCID -> IdentifierType.PMCID
}
