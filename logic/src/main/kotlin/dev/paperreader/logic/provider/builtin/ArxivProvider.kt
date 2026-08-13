package dev.paperreader.logic.provider.builtin

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.network.ProviderHttpClient
import dev.paperreader.logic.network.ProviderRequestGate
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class ArxivProvider(
    private val transport: ProviderHttpClient = ProviderHttpClient(),
    private val endpoint: String = "https://export.arxiv.org/api/query",
    private val requestGate: ProviderRequestGate = ProviderRequestGate(3_000),
) : PaperProvider {
    override val descriptor = ProviderDescriptor(
        id = "arxiv",
        displayName = "arXiv",
        minimumRequestIntervalMillis = 3_000,
    )

    override suspend fun search(query: PaperSearchQuery): ProviderPage {
        val start = query.cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val url = buildSearchUrl(query, start)
        val xml = try {
            requestGate.execute { transport.get(url, "application/atom+xml, application/xml") }.body
        } catch (error: ProviderException) {
            throw error
        } catch (error: Exception) {
            throw ProviderException.InvalidResponse(error)
        }
        val entries = parse(xml)
        val next = if (entries.size < query.limit) null else (start + entries.size).toString()
        return ProviderPage(entries, next)
    }

    override suspend fun get(recordId: String): RemotePaper? {
        val normalized = runCatching { IdentifierNormalizer.arxiv(recordId) }
            .getOrElse { throw ProviderException.InvalidResponse(it) }
        val url = "$endpoint?id_list=${encode(normalized.versionedId)}&max_results=1"
        val entries = parse(
            requestGate.execute { transport.get(url, "application/atom+xml, application/xml") }.body,
        )
        return entries.firstOrNull()
    }

    fun buildSearchUrl(query: PaperSearchQuery, start: Int = query.cursor?.toIntOrNull() ?: 0): String {
        val exactId = runCatching { IdentifierNormalizer.arxiv(query.text) }.getOrNull()
        if (exactId != null) {
            return "$endpoint?id_list=${encode(exactId.versionedId)}&max_results=1"
        }
        val sort = when (query.sort) {
            SearchSort.RELEVANCE -> "relevance"
            SearchSort.NEWEST -> "submittedDate"
            SearchSort.OLDEST -> "submittedDate"
        }
        val order = if (query.sort == SearchSort.OLDEST) "ascending" else "descending"
        return "$endpoint?search_query=all:${encode(query.text.trim())}" +
            "&start=$start&max_results=${query.limit}&sortBy=$sort&sortOrder=$order"
    }

    fun parse(xml: String): List<RemotePaper> = runCatching {
        require(!xml.contains(DOCTYPE_MARKER, ignoreCase = true)) {
            "arXiv XML must not declare a DOCTYPE"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's Harmony parser exposes none of these Xerces/SAX feature
            // switches consistently. The explicit DOCTYPE rejection above is the
            // cross-runtime boundary; supported flags add defense in depth.
            runCatching { setFeature(DISALLOW_DOCTYPE, true) }
            runCatching { setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
            runCatching { setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
            runCatching { setFeature(LOAD_EXTERNAL_DTD, false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External XML entities are not allowed") }
        }
        val document = builder.parse(xml.byteInputStream())
        val nodes = document.getElementsByTagNameNS(ATOM_NS, "entry")
        (0 until nodes.length).map { parseEntry(nodes.item(it) as Element) }
    }.getOrElse { throw ProviderException.InvalidResponse(it) }

    private fun parseEntry(entry: Element): RemotePaper {
        val rawId = text(entry, "id") ?: throw IllegalArgumentException("arXiv entry has no id")
        val arxiv = IdentifierNormalizer.arxiv(rawId)
        val title = text(entry, "title")?.collapseWhitespace()
            ?: throw IllegalArgumentException("arXiv entry has no title")
        val authors = entry.children("author").mapNotNull { author ->
            val name = author.childText("name")?.collapseWhitespace()
            name?.takeIf(String::isNotBlank)?.let { PaperAuthor(displayName = it) }
        }
        val links = entry.children("link")
        val landing = links.firstOrNull { it.getAttribute("rel").isNullOrBlank() || it.getAttribute("rel") == "alternate" }
            ?.getAttribute("href")
            ?.takeIf(String::isNotBlank)
            ?: "https://arxiv.org/abs/${arxiv.versionedId}"
        val pdf = links.firstOrNull { it.getAttribute("title") == "pdf" || it.getAttribute("type") == "application/pdf" }
            ?.getAttribute("href")?.takeIf(String::isNotBlank)
            ?: "https://arxiv.org/pdf/${arxiv.versionedId}.pdf"
        val published = parseDate(text(entry, "published"))
        val updated = text(entry, "updated")?.let { Instant.parse(it) }
        val manifestationPublished = arxiv.version
            ?.let { updated?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate() }
            ?: published
        val doi = entry.children("doi").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotBlank)
        val identifiers = linkedSetOf(PaperIdentifier(IdentifierType.ARXIV, arxiv.baseId))
        doi?.let { runCatching { IdentifierNormalizer.doi(it) }.getOrNull() }
            ?.let { identifiers += PaperIdentifier(IdentifierType.DOI, it) }
        val subjects = entry.children("category").mapNotNull { it.getAttribute("term").takeIf(String::isNotBlank) }.toSet()
        return RemotePaper(
            providerId = descriptor.id,
            providerRecordId = arxiv.versionedId,
            title = title,
            abstractText = text(entry, "summary")?.collapseWhitespace(),
            authors = authors,
            identifiers = identifiers,
            subjects = subjects,
            publishedDate = published,
            updatedAt = updated,
            manifestations = listOf(
                RemoteManifestation(
                    type = ManifestationType.PREPRINT,
                    version = arxiv.version?.let { "v$it" },
                    landingPageUrl = landing,
                    pdfUrl = pdf,
                    publishedDate = manifestationPublished,
                ),
            ),
        )
    }

    private fun parseDate(value: String?): LocalDate? = value?.let { runCatching { Instant.parse(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }.getOrNull() }

    private fun text(parent: Element, localName: String): String? = parent.getElementsByTagNameNS(ATOM_NS, localName)
        .item(0)?.textContent?.trim()?.takeIf(String::isNotBlank)

    private fun Element.children(localName: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.localName == localName || it.tagName == localName }

    private fun Element.childText(localName: String): String? = children(localName).firstOrNull()?.textContent?.trim()

    private fun String.collapseWhitespace() = replace(Regex("\\s+"), " ").trim()

    private fun encode(value: String) = URLEncoder
        .encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

    companion object {
        private const val ATOM_NS = "http://www.w3.org/2005/Atom"
        private const val DOCTYPE_MARKER = "<!DOCTYPE"
        private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
        private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
        private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    }
}
