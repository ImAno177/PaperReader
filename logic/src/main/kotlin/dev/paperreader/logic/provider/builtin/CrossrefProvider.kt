package dev.paperreader.logic.provider.builtin

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.network.ProviderHttpClient
import dev.paperreader.logic.network.ProviderRequestGate
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.CitationMetrics
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class CrossrefProvider(
    private val transport: ProviderHttpClient = ProviderHttpClient(),
    private val endpoint: String = "https://api.crossref.org",
    private val requestGate: ProviderRequestGate = ProviderRequestGate(200),
    private val clock: Clock = Clock.systemUTC(),
) : PaperProvider {
    override val descriptor = ProviderDescriptor(
        id = "crossref",
        displayName = "Crossref",
        minimumRequestIntervalMillis = 200,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: PaperSearchQuery): ProviderPage {
        if (runCatching { IdentifierNormalizer.arxiv(query.text) }.isSuccess) {
            return ProviderPage(emptyList())
        }
        val url = buildSearchUrl(query)
        return parseBody(requestGate.execute { transport.get(url, "application/json") }.body)
    }

    override suspend fun get(recordId: String): RemotePaper? {
        val doi = runCatching { IdentifierNormalizer.doi(recordId) }.getOrElse {
            throw dev.paperreader.logic.provider.ProviderException.InvalidResponse(it)
        }
        val url = "$endpoint/works/${encodePath(doi)}"
        val body = requestGate.execute { transport.get(url, "application/json") }.body
        val response = runCatching {
            json.decodeFromString<CrossrefSingleResponse>(body)
        }.getOrElse { throw dev.paperreader.logic.provider.ProviderException.InvalidResponse(it) }
        return toRemotePaper(response.message)
    }

    private fun toRemotePaper(item: CrossrefWork): RemotePaper? {
        val doi = item.DOI?.let { runCatching { IdentifierNormalizer.doi(it) }.getOrNull() } ?: return null
        val title = item.title.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val published = item.publishedPrint?.toLocalDate() ?: item.publishedOnline?.toLocalDate() ?: item.issued?.toLocalDate()
        val updated = item.updated?.dateTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val authors = item.author.mapNotNull { author ->
            val display = listOfNotNull(author.given?.trim(), author.family?.trim()).joinToString(" ").trim()
                .takeIf(String::isNotBlank) ?: author.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            PaperAuthor(displayName = display, givenName = author.given, familyName = author.family, orcid = author.ORCID)
        }
        val links = item.link
        val pdf = links.firstOrNull { it.contentType.equals("application/pdf", true) }?.URL
        val landing = item.URL ?: "https://doi.org/$doi"
        val identifiers = setOf(PaperIdentifier(IdentifierType.DOI, doi))
        return RemotePaper(
            providerId = descriptor.id,
            providerRecordId = doi,
            title = title,
            abstractText = item.abstractText?.stripJats(),
            authors = authors,
            identifiers = identifiers,
            subjects = item.subject.toSet(),
            publishedDate = published,
            updatedAt = updated,
            manifestations = listOf(
                RemoteManifestation(
                    type = ManifestationType.VERSION_OF_RECORD,
                    landingPageUrl = landing,
                    pdfUrl = pdf,
                    license = item.license.firstOrNull()?.URL,
                    publishedDate = published,
                ),
            ),
            citationMetrics = item.citedByCount?.takeIf { it >= 0 }?.let { count ->
                CitationMetrics(count = count, sourceId = descriptor.id, observedAt = clock.instant())
            },
        )
    }

    private fun String.stripJats() = replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
    private fun encode(value: String) = URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    private fun encodePath(value: String) = value.replace("/", "%2F")

    internal fun buildSearchUrl(query: PaperSearchQuery): String {
        val exactDoi = runCatching { IdentifierNormalizer.doi(query.text) }.getOrNull()
        if (exactDoi != null) {
            return "$endpoint/works?filter=doi:${encode(exactDoi)}&rows=1"
        }
        val sort = when (query.sort) {
            SearchSort.RELEVANCE -> "relevance"
            SearchSort.NEWEST, SearchSort.OLDEST -> "published"
        }
        val order = if (query.sort == SearchSort.OLDEST) "asc" else "desc"
        val cursor = query.cursor?.let { "&cursor=${encode(it)}" }.orEmpty()
        return "$endpoint/works?query.bibliographic=${encode(query.text.trim())}" +
            "&rows=${query.limit}&sort=$sort&order=$order$cursor"
    }

    internal fun parseBody(body: String): ProviderPage {
        val response = runCatching { json.decodeFromString<CrossrefResponse>(body) }
            .getOrElse { throw dev.paperreader.logic.provider.ProviderException.InvalidResponse(it) }
        return ProviderPage(response.message.items.mapNotNull(::toRemotePaper), response.message.nextCursor)
    }

    @Serializable private data class CrossrefResponse(val message: CrossrefMessage)
    @Serializable private data class CrossrefSingleResponse(val message: CrossrefWork)
    @Serializable private data class CrossrefMessage(
        val items: List<CrossrefWork> = emptyList(),
        @SerialName("next-cursor") val nextCursor: String? = null,
    )
    @Serializable private data class CrossrefWork(
        val DOI: String? = null,
        val title: List<String> = emptyList(),
        @SerialName("abstract") val abstractText: String? = null,
        val author: List<CrossrefAuthor> = emptyList(),
        val subject: List<String> = emptyList(),
        val URL: String? = null,
        val link: List<CrossrefLink> = emptyList(),
        val license: List<CrossrefLicense> = emptyList(),
        @SerialName("published-print") val publishedPrint: CrossrefDate? = null,
        @SerialName("published-online") val publishedOnline: CrossrefDate? = null,
        val issued: CrossrefDate? = null,
        val updated: CrossrefDateTime? = null,
        @SerialName("is-referenced-by-count") val citedByCount: Int? = null,
    )
    @Serializable private data class CrossrefAuthor(
        val given: String? = null,
        val family: String? = null,
        val name: String? = null,
        val ORCID: String? = null,
    )
    @Serializable private data class CrossrefLink(val URL: String? = null, @SerialName("content-type") val contentType: String? = null)
    @Serializable private data class CrossrefLicense(val URL: String? = null)
    @Serializable private data class CrossrefDateTime(@SerialName("date-time") val dateTime: String? = null)
    @Serializable private data class CrossrefDate(
        @SerialName("date-parts") val dateParts: JsonElement? = null,
    ) {
        fun toLocalDate(): LocalDate? {
            val part = (dateParts as? JsonArray)?.firstOrNull() as? JsonArray ?: return null
            val year = (part.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return null
            val month = (part.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: 1
            val day = (part.getOrNull(2) as? JsonPrimitive)?.intOrNull ?: 1
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
    }
}
