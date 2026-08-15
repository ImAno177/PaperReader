package dev.paperreader.logic.provider

import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import java.time.Instant
import java.time.LocalDate
import java.net.URI

data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val minimumRequestIntervalMillis: Long,
    val requiresApiKey: Boolean = false,
    val roles: Set<ProviderRole> = setOf(ProviderRole.CONTENT_SOURCE),
    val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.DISCOVERY),
    val identifierLookupTypes: Set<IdentifierType> = IdentifierType.entries.toSet(),
    val supportedSorts: Set<SearchSort> = SearchSort.entries.toSet(),
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        require(displayName.isNotBlank())
        require(minimumRequestIntervalMillis >= 0)
        require(roles.isNotEmpty())
        require(capabilities.isNotEmpty())
        require(supportedSorts.isNotEmpty())
    }
}

enum class ProviderRole {
    /** Preferred owner of ranked candidates for discovery queries. */
    SEARCH_ENGINE,

    /** Owns a paper manifestation or a structured/full-text artifact; it may also expose discovery. */
    CONTENT_SOURCE,

    /** Enriches an already identified work; a metadata-only provider is not used for free text. */
    METADATA_ENGINE,
}

enum class ProviderCapability {
    /** Can discover papers from unstructured title/author/topic queries. */
    DISCOVERY,

    /** Resolves authoritative metadata from a canonical identifier; it is not a discovery source. */
    METADATA_RESOLUTION,
}

enum class SearchSort {
    RELEVANCE,
    NEWEST,
    OLDEST,
}

data class PaperSearchQuery(
    val text: String,
    val limit: Int = 20,
    val cursor: String? = null,
    val sort: SearchSort = SearchSort.RELEVANCE,
) {
    init {
        require(text.isNotBlank())
        require(limit in 1..100)
    }
}

data class RemoteManifestation(
    val type: ManifestationType,
    val version: String? = null,
    val landingPageUrl: String? = null,
    val pdfUrl: String? = null,
    val license: String? = null,
    val publishedDate: LocalDate? = null,
) {
    init {
        require(landingPageUrl == null || landingPageUrl.isSafeWebUrl()) {
            "Landing page URL must use HTTP(S) with a host"
        }
        require(pdfUrl == null || pdfUrl.isSafeWebUrl()) {
            "PDF URL must use HTTP(S) with a host"
        }
    }
}

data class CitationMetrics(
    val count: Int,
    val sourceId: String,
    val observedAt: Instant,
) {
    init {
        require(count >= 0)
        require(sourceId.length in 1..100 && sourceId.matches(Regex("[a-z0-9][a-z0-9._-]*")))
    }
}

data class RemotePaper(
    val providerId: String,
    val providerRecordId: String,
    val title: String,
    val abstractText: String? = null,
    val authors: List<PaperAuthor> = emptyList(),
    val identifiers: Set<PaperIdentifier> = emptySet(),
    val subjects: Set<String> = emptySet(),
    val publishedDate: LocalDate? = null,
    val updatedAt: Instant? = null,
    val manifestations: List<RemoteManifestation> = emptyList(),
    val citationMetrics: CitationMetrics? = null,
) {
    init {
        require(providerId.isNotBlank())
        require(providerRecordId.isNotBlank())
        require(title.isNotBlank())
    }
}

data class ProviderPage(
    val items: List<RemotePaper>,
    val nextCursor: String? = null,
)

interface PaperProvider {
    val descriptor: ProviderDescriptor

    suspend fun search(query: PaperSearchQuery): ProviderPage

    suspend fun get(recordId: String): RemotePaper?
}

sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfterMillis: Long?) : ProviderException("Provider rate limited the request")
    class Unavailable(cause: Throwable? = null) : ProviderException("Provider is unavailable", cause)
    class InvalidResponse(cause: Throwable? = null) : ProviderException("Provider returned invalid data", cause)
}

private fun String.isSafeWebUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)
