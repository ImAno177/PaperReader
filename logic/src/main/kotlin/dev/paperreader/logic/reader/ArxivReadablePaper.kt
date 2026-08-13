package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.PaperManifestation
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

data class ReadablePaperDocument(
    /** A sanitized fragment. Presentation and the CSP remain owned by the UI renderer. */
    val bodyHtml: String,
    val title: String,
    val sourceUrl: String,
    val sourceProvider: String,
    val sourceVersion: String,
    val license: String?,
    val sourceSha256: String,
    val documentSha256: String,
    val retrievedAt: Instant,
    val servedFromCache: Boolean,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
)

data class ReadablePaperSection(
    val anchor: String,
    val title: String,
    val level: Int,
) {
    init {
        require(anchor.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
        require(title.isNotBlank())
        require(level in 1..3)
    }
}

enum class ReadablePaperWarning {
    TABLE_OF_CONTENTS_MISSING,
    FIGURE_UNAVAILABLE,
    FIGURE_LIMIT_REACHED,
    SOURCE_CONVERSION_ARTIFACT_NORMALIZED,
}

enum class ReadablePaperFailure {
    PAPER_NOT_FOUND,
    MANIFESTATION_NOT_FOUND,
    UNSUPPORTED_SOURCE,
    UNVERSIONED_SOURCE,
    SOURCE_NOT_FOUND,
    RATE_LIMITED,
    OFFLINE_OR_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

sealed interface ReadablePaperResult {
    data class Ready(val document: ReadablePaperDocument) : ReadablePaperResult

    data class Unavailable(
        val reason: ReadablePaperFailure,
        val retryAfterMillis: Long? = null,
    ) : ReadablePaperResult
}

internal data class ReadableRemoteResource(
    val bytes: ByteArray,
    val mediaType: String,
)

internal sealed interface ReadableRemoteResult {
    data class Success(val resource: ReadableRemoteResource) : ReadableRemoteResult
    data object NotFound : ReadableRemoteResult
    data class RateLimited(val retryAfterMillis: Long?) : ReadableRemoteResult
    data object Unavailable : ReadableRemoteResult
    data object TooLarge : ReadableRemoteResult
    data object Invalid : ReadableRemoteResult
}

internal fun interface ReadableResourceFetcher {
    suspend fun fetch(request: ReadableResourceRequest): ReadableRemoteResult
}

internal data class ReadableResourceRequest(
    val url: String,
    val accept: String,
    val maximumBytes: Long,
)

internal fun interface ReadablePaperLoader {
    suspend fun load(title: String, manifestation: PaperManifestation): ReadablePaperResult
}

internal class ArxivReadablePaperLoader(
    private val fetcher: ReadableResourceFetcher,
    private val cache: ReadablePaperCache,
    private val sanitizer: ArxivHtmlSanitizer = ArxivHtmlSanitizer(),
    private val now: () -> Instant = Instant::now,
) : ReadablePaperLoader {
    override suspend fun load(title: String, manifestation: PaperManifestation): ReadablePaperResult {
        if (!manifestation.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) {
            return ReadablePaperResult.Unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        }
        val versionedId = versionedArxivId(manifestation)
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.UNVERSIONED_SOURCE)
        val sourceUrl = "https://arxiv.org/html/$versionedId"
        val cacheKey = ReadablePaperCache.keyFor(
            sourceUrl = sourceUrl,
            sanitizerPolicyVersion = SANITIZER_POLICY_VERSION,
            rendererContractVersion = RENDERER_CONTRACT_VERSION,
        )
        cache.read(cacheKey)?.let { cached ->
            return ReadablePaperResult.Ready(
                cached.toDocument(
                    title = title,
                    sourceProvider = manifestation.sourceProvider,
                    sourceVersion = versionedId.substringAfterLast('v').let { "v$it" },
                    license = manifestation.license,
                    servedFromCache = true,
                ),
            )
        }

        val page = when (
            val result = fetcher.fetch(
                ReadableResourceRequest(
                    url = sourceUrl,
                    accept = "text/html, application/xhtml+xml;q=0.9",
                    maximumBytes = MAXIMUM_HTML_BYTES,
                ),
            )
        ) {
            is ReadableRemoteResult.Success -> result.resource
            ReadableRemoteResult.NotFound -> return unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND)
            is ReadableRemoteResult.RateLimited -> return ReadablePaperResult.Unavailable(
                ReadablePaperFailure.RATE_LIMITED,
                result.retryAfterMillis,
            )
            ReadableRemoteResult.Unavailable -> return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
            ReadableRemoteResult.TooLarge -> return unavailable(ReadablePaperFailure.RESPONSE_TOO_LARGE)
            ReadableRemoteResult.Invalid -> return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        if (!page.mediaType.isHtmlMediaType()) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val rawHtml = page.bytes.toString(Charsets.UTF_8)
        val sanitized = sanitizer.sanitize(
            rawHtml = rawHtml,
            sourceUrl = sourceUrl,
            fetchAsset = { url, maximumBytes ->
                fetcher.fetch(
                    ReadableResourceRequest(
                        url = url,
                        accept = "image/png, image/jpeg, image/webp, image/gif",
                        maximumBytes = maximumBytes,
                    ),
                )
            },
        ) ?: return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        val retrievedAt = now()
        val record = CachedReadablePaper(
            bodyHtml = sanitized.bodyHtml,
            sourceUrl = sourceUrl,
            sourceSha256 = sha256(page.bytes),
            documentSha256 = sha256(sanitized.bodyHtml.toByteArray(Charsets.UTF_8)),
            retrievedAt = retrievedAt,
            sourceLicense = sanitized.sourceLicense,
            sections = sanitized.sections,
            warnings = sanitized.warnings,
        )
        try {
            cache.write(cacheKey, record)
        } catch (_: IOException) {
            // The verified document remains readable even when the disposable offline cache is unavailable.
        }
        return ReadablePaperResult.Ready(
            record.toDocument(
                title = title,
                sourceProvider = manifestation.sourceProvider,
                sourceVersion = versionedId.substringAfterLast('v').let { "v$it" },
                license = manifestation.license,
                servedFromCache = false,
            ),
        )
    }

    private fun versionedArxivId(manifestation: PaperManifestation): String? {
        val recordId = manifestation.sourceRecordId.trim().removePrefix("arXiv:")
        val explicit = VERSIONED_ARXIV_ID.matchEntire(recordId)?.value
        if (explicit != null) return explicit
        if (!UNVERSIONED_ARXIV_ID.matches(recordId)) return null
        val version = manifestation.version
            ?.trim()
            ?.removePrefix("v")
            ?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
            ?: return null
        return "${recordId}v$version"
    }

    private fun unavailable(reason: ReadablePaperFailure) = ReadablePaperResult.Unavailable(reason)

    companion object {
        private const val ARXIV_PROVIDER_ID = "arxiv"
        private const val SANITIZER_POLICY_VERSION = "arxiv-html-sanitizer-7"
        private const val RENDERER_CONTRACT_VERSION = "mobile-html-5"
        private const val MAXIMUM_HTML_BYTES = 4L * 1024L * 1024L
        private val UNVERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})",
        )
        private val VERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})v[1-9][0-9]*",
        )
    }
}

internal data class SanitizedReadableHtml(
    val bodyHtml: String,
    val sourceLicense: String?,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
)

internal class ArxivHtmlSanitizer(
    private val maximumFigureCount: Int = 32,
    private val maximumFigureBytes: Long = 12L * 1024L * 1024L,
    private val maximumSingleFigureBytes: Long = 3L * 1024L * 1024L,
) {
    init {
        require(maximumFigureCount >= 0)
        require(maximumFigureBytes >= 0)
        require(maximumSingleFigureBytes > 0)
    }

    suspend fun sanitize(
        rawHtml: String,
        sourceUrl: String,
        fetchAsset: suspend (url: String, maximumBytes: Long) -> ReadableRemoteResult,
    ): SanitizedReadableHtml? {
        val sourceUri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (sourceUri.scheme != "https" || sourceUri.host != "arxiv.org" || sourceUri.fragment != null) return null
        val parsed = Jsoup.parse(rawHtml, sourceUrl)
        val article = parsed.selectFirst("article.ltx_document")?.clone() ?: return null
        if (article.text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return null
        collapseAuthorNotes(article)
        val sourceLicense = parsed.selectFirst("#license-tr")
            ?.text()
            ?.trim()
            ?.take(MAXIMUM_LICENSE_LENGTH)
            ?.takeIf(String::isNotBlank)

        val warnings = linkedSetOf<ReadablePaperWarning>()
        if (normalizeKnownConversionArtifacts(article)) {
            warnings += ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED
        }
        annotateReadableBlocks(article)
        val container = Element("div").addClass("paperreader-document")
        val sections = extractSections(parsed)
        if (sections.isEmpty()) warnings.add(ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING)
        container.appendChild(article)
        embedFigures(container, sourceUri, warnings, fetchAsset)

        val cleaned = Jsoup.clean(
            container.outerHtml(),
            sourceUrl,
            readableSafelist(),
            Document.OutputSettings().prettyPrint(false),
        )
        val cleanedDocument = Jsoup.parseBodyFragment(cleaned, sourceUrl)
        normalizeLinks(cleanedDocument, sourceUri)
        val bodyHtml = cleanedDocument.body().html().trim()
        if (cleanedDocument.body().text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return null
        if (containsExecutableMarkup(cleanedDocument)) return null
        return SanitizedReadableHtml(bodyHtml, sourceLicense, sections, warnings)
    }

    /**
     * arXiv's LaTeXML output occasionally leaves a narrow, human-readable circled-step command
     * as literal TeX. Preserve the authored step number without interpreting arbitrary TeX.
     */
    private fun normalizeKnownConversionArtifacts(article: Element): Boolean {
        var normalized = false
        article.getAllElements().forEach { element ->
            element.textNodes().forEach { node ->
                val source = node.text()
                val replacement = CIRCLED_STEP_ARTIFACT.replace(source) { match ->
                    normalized = true
                    "${match.groupValues[1]}⃝"
                }
                if (replacement != source) node.text(replacement)
            }
        }
        return normalized
    }

    private fun annotateReadableBlocks(article: Element) {
        article.select("[data-paperreader-block-id]").removeAttr("data-paperreader-block-id")
        article.select(READABLE_BLOCK_SELECTOR).forEachIndexed { index, element ->
            element.attr("data-paperreader-block-id", "prx-b${index.toString().padStart(5, '0')}")
        }
    }

    private fun extractSections(document: Document): List<ReadablePaperSection> = document
        .select("nav.ltx_TOC li > a[href^=#]")
        .asSequence()
        .mapNotNull { link ->
            val anchor = link.attr("href").removePrefix("#")
            val title = link.text().replace(Regex("\\s+"), " ").trim().take(MAXIMUM_SECTION_TITLE_LENGTH)
            if (!anchor.matches(SAFE_ANCHOR) || title.isBlank()) return@mapNotNull null
            val parent = link.parent()
            val level = when {
                parent?.hasClass("ltx_tocentry_subsubsection") == true -> 3
                parent?.hasClass("ltx_tocentry_subsection") == true -> 2
                else -> 1
            }
            ReadablePaperSection(anchor, title, level)
        }
        .distinctBy(ReadablePaperSection::anchor)
        .take(MAXIMUM_SECTION_COUNT)
        .toList()

    private fun collapseAuthorNotes(article: Element) {
        val authorNotes = article.selectFirst(".ltx_author_notes") ?: return
        authorNotes.select(".ltx_contact_name").remove()
        val disclosure = Element("details").addClass("paperreader-author-notes")
        disclosure.appendElement("summary").text("Author notes and affiliations")
        disclosure.appendElement("div")
            .addClass("paperreader-author-notes-content")
            .html(authorNotes.html())
        authorNotes.remove()
        article.selectFirst(".ltx_authors")?.after(disclosure)
    }

    private suspend fun embedFigures(
        container: Element,
        sourceUri: URI,
        warnings: MutableSet<ReadablePaperWarning>,
        fetchAsset: suspend (url: String, maximumBytes: Long) -> ReadableRemoteResult,
    ) {
        var embeddedCount = 0
        var embeddedBytes = 0L
        for (image in container.select("img[src]").toList()) {
            val remaining = maximumFigureBytes - embeddedBytes
            if (embeddedCount >= maximumFigureCount || remaining <= 0L) {
                warnings += ReadablePaperWarning.FIGURE_LIMIT_REACHED
                replaceUnavailableFigure(image)
                continue
            }
            val assetUrl = resolveFigureUrl(image.attr("src"), sourceUri)
            if (assetUrl == null) {
                warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
                replaceUnavailableFigure(image)
                continue
            }
            val limit = minOf(maximumSingleFigureBytes, remaining)
            val resource = (fetchAsset(assetUrl, limit) as? ReadableRemoteResult.Success)?.resource
            val mediaType = resource?.mediaType?.normalizedMediaType()
            if (resource == null || mediaType !in SAFE_IMAGE_MEDIA_TYPES || resource.bytes.isEmpty()) {
                warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
                replaceUnavailableFigure(image)
                continue
            }
            val caption = image.closest("figure")?.selectFirst("figcaption")?.text()?.trim()
            if (image.attr("alt").isGenericFigureAlt() && !caption.isNullOrBlank()) {
                image.attr("alt", caption.take(MAXIMUM_GENERATED_ALT_LENGTH))
            }
            image.attr("src", "data:$mediaType;base64,${Base64.getEncoder().encodeToString(resource.bytes)}")
            image.removeAttr("srcset")
            embeddedBytes += resource.bytes.size
            embeddedCount += 1
        }
    }

    private fun resolveFigureUrl(raw: String, sourceUri: URI): String? {
        val candidate = runCatching { sourceUri.resolve(raw) }.getOrNull() ?: return null
        if (candidate.scheme != "https" || candidate.host != "arxiv.org") return null
        if (candidate.userInfo != null || candidate.port != -1 || candidate.query != null || candidate.fragment != null) return null
        val expectedPrefix = sourceUri.path.trimEnd('/') + "/"
        if (!candidate.path.startsWith(expectedPrefix)) return null
        return candidate.toASCIIString()
    }

    private fun replaceUnavailableFigure(image: Element) {
        val replacement = Element("span")
            .addClass("paperreader-figure-unavailable")
            .attr("role", "img")
            .attr("aria-label", image.attr("alt").takeIf { it.isNotBlank() } ?: "Figure unavailable")
            .text("Figure image unavailable offline. The caption is preserved below.")
        image.replaceWith(replacement)
    }

    private fun normalizeLinks(document: Document, sourceUri: URI) {
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val uri = runCatching { URI(href) }.getOrNull()
            when {
                href.startsWith("#") -> Unit
                uri == null -> link.removeAttr("href")
                uri.scheme == "https" && uri.host == sourceUri.host &&
                    uri.path == sourceUri.path && !uri.fragment.isNullOrBlank() -> {
                    link.attr("href", "#${uri.fragment}")
                }
                uri.scheme !in setOf("https", "mailto") -> link.removeAttr("href")
                uri.userInfo != null -> link.removeAttr("href")
                else -> link.attr("rel", "external nofollow noopener noreferrer")
            }
        }
    }

    private fun containsExecutableMarkup(document: Document): Boolean {
        if (document.select("script, style, link, base, iframe, frame, object, embed, form, input, button, textarea, select, svg").isNotEmpty()) {
            return true
        }
        return document.allElements.any { element ->
            element.attributes().any { attribute -> attribute.key.startsWith("on", ignoreCase = true) }
        }
    }

    private fun readableSafelist(): Safelist {
        val safelist = Safelist.none()
            .addTags(*SAFE_HTML_TAGS)
            .addTags(*SAFE_MATHML_TAGS)
            .addAttributes(
                ":all",
                "id", "class", "title", "lang", "dir", "role",
                "aria-label", "aria-labelledby", "aria-describedby", "aria-hidden",
                "data-paperreader-block-id",
            )
            .addAttributes("a", "href")
            .addProtocols("a", "href", "https", "mailto", "#")
            .addAttributes("img", "src", "alt", "width", "height", "loading", "decoding")
            .addProtocols("img", "src", "data")
            .addAttributes("ol", "start", "reversed")
            .addAttributes("li", "value")
            .addAttributes("time", "datetime")
            .addAttributes("th", "colspan", "rowspan", "scope", "headers", "abbr")
            .addAttributes("td", "colspan", "rowspan", "headers")
            .addAttributes("col", "span")
            .addAttributes("colgroup", "span")
        SAFE_MATHML_TAGS.forEach { tag -> safelist.addAttributes(tag, *SAFE_MATHML_ATTRIBUTES) }
        return safelist
    }

    companion object {
        private const val MINIMUM_ARTICLE_TEXT_LENGTH = 300
        private const val MAXIMUM_GENERATED_ALT_LENGTH = 500
        private const val MAXIMUM_LICENSE_LENGTH = 160
        private const val MAXIMUM_SECTION_TITLE_LENGTH = 180
        private const val MAXIMUM_SECTION_COUNT = 120
        private val SAFE_ANCHOR = Regex("[A-Za-z0-9._:-]{1,160}")
        private val CIRCLED_STEP_ARTIFACT = Regex(
            """\\raisebox\{[-+]?(?:\d+(?:\.\d+)?|\.\d+)pt\}\{\\scriptsize\s*([0-9]{1,2})\}⃝""",
        )
        private const val READABLE_BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,dt,dd,figcaption,pre,blockquote,th,td"
        private val SAFE_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val SAFE_HTML_TAGS = arrayOf(
            "article", "section", "nav", "header", "footer", "div", "span",
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "a", "ol", "ul", "li",
            "dl", "dt", "dd", "figure", "figcaption", "img", "table", "caption",
            "thead", "tbody", "tfoot", "tr", "th", "td", "colgroup", "col",
            "blockquote", "pre", "code", "kbd", "samp", "var", "strong", "b",
            "em", "i", "u", "s", "small", "sup", "sub", "br", "hr", "details",
            "summary", "time", "address", "abbr", "cite", "q", "mark",
        )
        private val SAFE_MATHML_TAGS = arrayOf(
            "math", "mrow", "mi", "mn", "mo", "ms", "mtext", "mspace", "mfrac",
            "msqrt", "mroot", "mstyle", "merror", "mpadded", "mphantom", "mfenced",
            "menclose", "msub", "msup", "msubsup", "munder", "mover", "munderover",
            "mmultiscripts", "mprescripts", "none", "mtable", "mtr", "mtd", "mlabeledtr",
            "maligngroup", "malignmark", "semantics", "annotation", "annotation-xml", "mglyph",
        )
        private val SAFE_MATHML_ATTRIBUTES = arrayOf(
            "display", "alttext", "encoding", "mathvariant", "mathsize", "mathcolor",
            "stretchy", "symmetric", "fence", "separator", "form", "movablelimits",
            "accent", "accentunder", "displaystyle", "scriptlevel", "linethickness",
            "columnalign", "rowalign", "columnspacing", "rowspacing", "columnspan",
            "rowspan", "bevelled", "close", "open", "notation",
        )
    }
}

internal data class CachedReadablePaper(
    val bodyHtml: String,
    val sourceUrl: String,
    val sourceSha256: String,
    val documentSha256: String,
    val retrievedAt: Instant,
    val sourceLicense: String?,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
) {
    fun toDocument(
        title: String,
        sourceProvider: String,
        sourceVersion: String,
        license: String?,
        servedFromCache: Boolean,
    ) = ReadablePaperDocument(
        bodyHtml = bodyHtml,
        title = title,
        sourceUrl = sourceUrl,
        sourceProvider = sourceProvider,
        sourceVersion = sourceVersion,
        license = license ?: sourceLicense,
        sourceSha256 = sourceSha256,
        documentSha256 = documentSha256,
        retrievedAt = retrievedAt,
        servedFromCache = servedFromCache,
        sections = sections,
        warnings = warnings,
    )
}

internal class ReadablePaperCache(
    private val directory: Path,
    private val maximumCachedBodyBytes: Long = 20L * 1024L * 1024L,
    private val maximumTotalCacheBytes: Long = 160L * 1024L * 1024L,
) {
    init {
        require(maximumCachedBodyBytes > 0)
        require(maximumTotalCacheBytes > maximumCachedBodyBytes)
    }

    @Synchronized
    fun read(key: String): CachedReadablePaper? {
        if (!key.matches(SHA256_PATTERN)) return null
        try {
            pruneToBudget(protectedKey = key)
        } catch (_: IOException) {
            // A missing or temporarily unavailable cache directory is an ordinary cache miss.
        }
        val manifestPath = directory.resolve("$key$MANIFEST_SUFFIX")
        val bodyPath = directory.resolve("$key$BODY_SUFFIX")
        val record = runCatching {
            if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(bodyPath)) return@runCatching null
            if (Files.size(manifestPath) > MAXIMUM_MANIFEST_BYTES || Files.size(bodyPath) > maximumCachedBodyBytes) {
                return@runCatching null
            }
            val fields = Files.readAllLines(manifestPath, Charsets.UTF_8)
            if (fields.firstOrNull() != CACHE_HEADER) return@runCatching null
            val values = fields.drop(1).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
            val body = Files.newBufferedReader(bodyPath, Charsets.UTF_8).use { it.readText() }
            val documentSha = values.getValue("document_sha256")
            if (!documentSha.matches(SHA256_PATTERN) || sha256(body.toByteArray(Charsets.UTF_8)) != documentSha) {
                return@runCatching null
            }
            val sourceSha = values.getValue("source_sha256")
            if (!sourceSha.matches(SHA256_PATTERN)) return@runCatching null
            CachedReadablePaper(
                bodyHtml = body,
                sourceUrl = decode(values.getValue("source_url")),
                sourceSha256 = sourceSha,
                documentSha256 = documentSha,
                retrievedAt = Instant.parse(values.getValue("retrieved_at")),
                sourceLicense = values["source_license"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::decode)
                    ?.takeIf(String::isNotBlank),
                sections = decodeSections(values["sections"].orEmpty()),
                warnings = decode(values["warnings"].orEmpty())
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .mapNotNull { runCatching { ReadablePaperWarning.valueOf(it) }.getOrNull() }
                    .toSet(),
            )
        }.getOrNull()
        if (record == null) {
            deleteEntry(key)
            return null
        }
        runCatching {
            val now = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())
            Files.setLastModifiedTime(bodyPath, now)
            Files.setLastModifiedTime(manifestPath, now)
        }
        return record
    }

    @Synchronized
    fun write(key: String, record: CachedReadablePaper) {
        require(key.matches(SHA256_PATTERN))
        val bodyBytes = record.bodyHtml.toByteArray(Charsets.UTF_8)
        require(bodyBytes.size <= maximumCachedBodyBytes)
        val manifest = listOf(
            CACHE_HEADER,
            "source_url=${encode(record.sourceUrl)}",
            "source_sha256=${record.sourceSha256}",
            "document_sha256=${record.documentSha256}",
            "retrieved_at=${record.retrievedAt}",
            "source_license=${record.sourceLicense?.let(::encode).orEmpty()}",
            "sections=${encodeSections(record.sections)}",
            "warnings=${encode(record.warnings.sortedBy { it.name }.joinToString("\n") { it.name })}",
        ).joinToString("\n", postfix = "\n")
        val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAXIMUM_MANIFEST_BYTES)
        require(bodyBytes.size.toLong() + manifestBytes.size <= maximumTotalCacheBytes)
        Files.createDirectories(directory)
        val bodyPath = directory.resolve("$key$BODY_SUFFIX")
        val manifestPath = directory.resolve("$key$MANIFEST_SUFFIX")
        val bodyTemporary = Files.createTempFile(directory, ".$key-", BODY_TEMP_SUFFIX)
        val manifestTemporary = Files.createTempFile(directory, ".$key-", MANIFEST_TEMP_SUFFIX)
        try {
            Files.newBufferedWriter(bodyTemporary, Charsets.UTF_8).use { it.write(record.bodyHtml) }
            Files.newBufferedWriter(manifestTemporary, Charsets.UTF_8).use { it.write(manifest) }
            moveAtomically(bodyTemporary, bodyPath)
            moveAtomically(manifestTemporary, manifestPath)
            try {
                pruneToBudget(protectedKey = key)
            } catch (_: IOException) {
                // A later write retries pruning; a successful cache publication must remain usable.
            }
        } finally {
            Files.deleteIfExists(bodyTemporary)
            Files.deleteIfExists(manifestTemporary)
        }
    }

    private fun pruneToBudget(protectedKey: String) {
        val keys = linkedSetOf<String>()
        Files.newDirectoryStream(directory).use { paths ->
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name.endsWith(BODY_TEMP_SUFFIX) || name.endsWith(MANIFEST_TEMP_SUFFIX)) {
                    runCatching { Files.deleteIfExists(path) }
                    return@forEach
                }
                val key = when {
                    name.endsWith(BODY_SUFFIX) -> name.removeSuffix(BODY_SUFFIX)
                    name.endsWith(MANIFEST_SUFFIX) -> name.removeSuffix(MANIFEST_SUFFIX)
                    else -> null
                }
                if (key != null && key.matches(SHA256_PATTERN)) keys += key
            }
        }
        val entries = keys.mapNotNull { key ->
            val body = directory.resolve("$key$BODY_SUFFIX")
            val manifest = directory.resolve("$key$MANIFEST_SUFFIX")
            if (!Files.isRegularFile(body) || !Files.isRegularFile(manifest)) {
                deleteEntry(key)
                return@mapNotNull null
            }
            val bodySize = runCatching { Files.size(body) }.getOrNull()
            val manifestSize = runCatching { Files.size(manifest) }.getOrNull()
            if (bodySize == null || manifestSize == null) {
                deleteEntry(key)
                return@mapNotNull null
            }
            if (bodySize > maximumCachedBodyBytes || manifestSize > MAXIMUM_MANIFEST_BYTES) {
                deleteEntry(key)
                return@mapNotNull null
            }
            runCatching {
                CacheEntry(
                    key = key,
                    bytes = bodySize + manifestSize,
                    lastUsedMillis = maxOf(
                        Files.getLastModifiedTime(body).toMillis(),
                        Files.getLastModifiedTime(manifest).toMillis(),
                    ),
                )
            }.getOrElse {
                deleteEntry(key)
                null
            }
        }
        var totalBytes = entries.sumOf(CacheEntry::bytes)
        entries.sortedBy(CacheEntry::lastUsedMillis).forEach { entry ->
            if (totalBytes <= maximumTotalCacheBytes) return
            if (entry.key == protectedKey) return@forEach
            deleteEntry(entry.key)
            totalBytes -= entry.bytes
        }
    }

    private fun deleteEntry(key: String) {
        runCatching { Files.deleteIfExists(directory.resolve("$key$BODY_SUFFIX")) }
        runCatching { Files.deleteIfExists(directory.resolve("$key$MANIFEST_SUFFIX")) }
    }

    private data class CacheEntry(
        val key: String,
        val bytes: Long,
        val lastUsedMillis: Long,
    )

    companion object {
        private const val CACHE_HEADER = "PAPERREADER-READABLE-CACHE-2"
        private const val MAXIMUM_MANIFEST_BYTES = 16L * 1024L
        private const val BODY_SUFFIX = ".body.html"
        private const val MANIFEST_SUFFIX = ".manifest"
        private const val BODY_TEMP_SUFFIX = ".body.part"
        private const val MANIFEST_TEMP_SUFFIX = ".manifest.part"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun keyFor(
            sourceUrl: String,
            sanitizerPolicyVersion: String,
            rendererContractVersion: String,
        ): String = sha256(
            listOf(sourceUrl, sanitizerPolicyVersion, rendererContractVersion)
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8),
        )

        private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decode(value: String): String = String(
            Base64.getUrlDecoder().decode(value),
            Charsets.UTF_8,
        )

        private fun encodeSections(sections: List<ReadablePaperSection>): String = encode(
            sections.joinToString("\n") { section ->
                "${encode(section.anchor)}\t${encode(section.title)}\t${section.level}"
            },
        )

        private fun decodeSections(value: String): List<ReadablePaperSection> = runCatching {
            decode(value).lineSequence().filter(String::isNotBlank).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                runCatching {
                    ReadablePaperSection(
                        anchor = decode(parts[0]),
                        title = decode(parts[1]),
                        level = parts[2].toInt(),
                    )
                }.getOrNull()
            }.toList()
        }.getOrDefault(emptyList())
    }
}

private fun String.isHtmlMediaType(): Boolean = normalizedMediaType() in setOf("text/html", "application/xhtml+xml")

private fun String.normalizedMediaType(): String = substringBefore(';').trim().lowercase()

private fun String.isGenericFigureAlt(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isBlank() || normalized == "refer to caption" || normalized == "[uncaptioned image]"
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
