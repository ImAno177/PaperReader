package dev.paperreader.logic.reader

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist

internal data class SanitizedReadableHtml(
    val bodyHtml: String,
    val sourceLicense: String?,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
)

internal class ArxivHtmlSanitizer(
    private val maximumFigureCount: Int = 24,
    private val maximumFigureBytes: Long = 6L * 1024L * 1024L,
    private val maximumSingleFigureBytes: Long = 2L * 1024L * 1024L,
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
        normalizeAuthorLayout(article)
        normalizeTableLayout(article)
        val sourceLicense = parsed.selectFirst("#license-tr")
            ?.text()
            ?.trim()
            ?.take(MAXIMUM_LICENSE_LENGTH)
            ?.takeIf(String::isNotBlank)

        val warnings = linkedSetOf<ReadablePaperWarning>()
        if (normalizeKnownConversionArtifacts(article)) {
            warnings += ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED
        }
        replaceUnsupportedEmbeddedFigures(article, warnings)
        annotateReadableBlocks(article)
        val container = Element("div").addClass("paperreader-document")
        val sections = extractSections(parsed)
        if (sections.isEmpty()) warnings.add(ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING)
        container.appendChild(article)

        val cleaned = Jsoup.clean(
            container.outerHtml(),
            sourceUrl,
            readableSafelist(keepFigureSources = true),
            Document.OutputSettings().prettyPrint(false),
        )
        val cleanedDocument = Jsoup.parseBodyFragment(cleaned, sourceUrl)
        normalizeLinks(cleanedDocument, sourceUri)
        // Clean the document structure before embedding base64 assets. Running Jsoup.clean after
        // embedding large figures duplicates the HTML and can exhaust the WebView process heap on
        // image-heavy papers, even though each individual asset is within its byte budget.
        embedFigures(cleanedDocument.body(), sourceUri, warnings, fetchAsset)
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

    private fun replaceUnsupportedEmbeddedFigures(
        article: Element,
        warnings: MutableSet<ReadablePaperWarning>,
    ) {
        article.select("object")
            .filter { embedded ->
                embedded.attr("type").normalizedMediaType() != SVG_MEDIA_TYPE ||
                    embedded.attr("data").isBlank()
            }
            .forEach { vector ->
                val caption = vector.closest("figure")?.selectFirst("figcaption")?.text()?.trim()
                vector.replaceWith(unavailableFigurePlaceholder(caption))
                warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
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
        val authors = article.selectFirst(".ltx_authors") ?: return
        val deferredNotes = mutableListOf<Element>()
        var sawDirectAuthorName = false
        authors.select(".ltx_creator").forEach { creator ->
            val personName = creator.selectFirst(".ltx_personname")
            val legacyPersonName = personName?.takeIf { candidate ->
                sawDirectAuthorName &&
                    candidate.ownText().isBlank() &&
                    candidate.children().any { child ->
                        child.hasClass("ltx_text") &&
                            child.attr("style").contains("font-size", ignoreCase = true)
                    }
            }
            if (legacyPersonName != null) {
                legacyPersonName.select("a").forEach { link -> link.before(" ") }
                legacyPersonName.removeClass("ltx_personname")
                creator.removeClass("ltx_role_author").addClass("paperreader-author-details")
                deferredNotes.add(creator)
                return@forEach
            }
            if (!personName?.ownText().isNullOrBlank()) sawDirectAuthorName = true
            val authorNotes = creator.selectFirst(".ltx_author_notes") ?: return@forEach
            authorNotes.select(".ltx_contact_name").remove()
            // Thanks/acknowledgement prose is not author identity metadata. Keep affiliations
            // and email addresses next to the author on narrow screens.
            authorNotes.select(".ltx_role_thanks").remove()
            if (authorNotes.select(".ltx_contact").isEmpty()) {
                authorNotes.remove()
                deferredNotes.add(authorNotes)
            } else {
                authorNotes.addClass("paperreader-author-details")
            }
        }
        if (deferredNotes.isEmpty()) return
        val disclosure = Element("details").addClass("paperreader-author-notes")
        disclosure.appendElement("summary").text("Author notes and affiliations")
        val content = disclosure.appendElement("div").addClass("paperreader-author-notes-content")
        deferredNotes.forEach(content::appendChild)
        authors.after(disclosure)
    }

    private fun normalizeAuthorLayout(article: Element) {
        val authors = article.selectFirst(".ltx_authors") ?: return
        authors.select(".ltx_author_before").remove()
        authors.select(".ltx_creator").forEach { creator ->
            creator.addClass("paperreader-author")
            creator.selectFirst(".ltx_personname")?.addClass("paperreader-author-name")
            creator.select(".ltx_author_notes").forEach { notes ->
                notes.addClass("paperreader-author-details")
            }
            // LaTeXML puts the explanatory `footnotemark:` payload inside the author name.
            // Keep the superscript marker, but never expose those implementation internals.
            creator.select(".ltx_role_footnotemark").forEach { marker ->
                marker.select(".ltx_note_outer, .ltx_note_content, .ltx_note_type, .ltx_tag").remove()
            }
        }
    }

    /**
     * Keep wide tables intact and put the horizontal scroll affordance on a dedicated wrapper.
     * Applying overflow to the table itself lets WebView shrink or clip the table on narrow
     * screens; the wrapper preserves the source column geometry instead.
     */
    private fun normalizeTableLayout(article: Element) {
        article.select("table").toList().forEach { table ->
            if (table.parent()?.hasClass("paperreader-table-scroll") == true) return@forEach
            val wrapper = Element("div").addClass("paperreader-table-scroll")
            table.replaceWith(wrapper)
            wrapper.appendChild(table)
        }
    }

    private suspend fun embedFigures(
        container: Element,
        sourceUri: URI,
        warnings: MutableSet<ReadablePaperWarning>,
        fetchAsset: suspend (url: String, maximumBytes: Long) -> ReadableRemoteResult,
    ) {
        val images = container.select("img[src], object[data]").toList()
        var embeddedCount = 0
        var embeddedBytes = 0L
        var nextImageIndex = 0
        while (nextImageIndex < images.size) {
            val remaining = maximumFigureBytes - embeddedBytes
            if (embeddedCount >= maximumFigureCount || remaining <= 0L) {
                warnings += ReadablePaperWarning.FIGURE_LIMIT_REACHED
                for (index in nextImageIndex until images.size) {
                    replaceUnavailableFigure(images[index])
                }
                return
            }

            // Reserve a full maximum-sized response for every request in the batch. This keeps
            // peak asset memory within the total figure budget while preserving the per-image
            // limit, so a large valid figure is not rejected just because siblings are fetched
            // at the same time.
            val capacityByBytes = (remaining / maximumSingleFigureBytes)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
            val batchSize = minOf(
                MAXIMUM_CONCURRENT_FIGURE_REQUESTS,
                maximumFigureCount - embeddedCount,
                capacityByBytes,
            )
            val batch = ArrayList<FigureCandidate>(batchSize)
            while (nextImageIndex < images.size && batch.size < batchSize) {
                val element = images[nextImageIndex++]
                val rawAssetUrl = if (element.tagName().equals("object", ignoreCase = true)) {
                    element.attr("data")
                } else {
                    element.attr("src")
                }
                val assetUrl = resolveFigureUrl(rawAssetUrl, sourceUri)
                if (assetUrl == null) {
                    warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
                    replaceUnavailableFigure(element)
                } else {
                    batch += FigureCandidate(element, assetUrl)
                }
            }

            if (batch.isEmpty()) continue
            val maximumBytesPerRequest = minOf(maximumSingleFigureBytes, remaining)
            val results = coroutineScope {
                batch.map { candidate ->
                    async { fetchAsset(candidate.assetUrl, maximumBytesPerRequest) }
                }.awaitAll()
            }

            batch.zip(results).forEach { (candidate, result) ->
                val resource = (result as? ReadableRemoteResult.Success)?.resource
                val availableBytes = maximumFigureBytes - embeddedBytes
                val mediaType = resource?.mediaType?.normalizedMediaType()
                val safeBytes = when {
                    resource == null || resource.bytes.isEmpty() -> null
                    resource.bytes.size.toLong() > maximumSingleFigureBytes -> null
                    mediaType in SAFE_RASTER_MEDIA_TYPES -> resource.bytes
                    mediaType == SVG_MEDIA_TYPE -> sanitizeSvg(resource.bytes)
                    else -> null
                }
                if (
                    mediaType == null ||
                    safeBytes == null ||
                    safeBytes.size.toLong() > availableBytes
                ) {
                    warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
                    replaceUnavailableFigure(candidate.element)
                    return@forEach
                }
                val dataUri = "data:$mediaType;base64,${Base64.getEncoder().encodeToString(safeBytes)}"
                val image = if (candidate.element.tagName().equals("object", ignoreCase = true)) {
                    Element("img").apply {
                        attr("src", dataUri)
                        attr("alt", figureAlt(candidate.element))
                        attr("loading", "lazy")
                        attr("decoding", "async")
                        listOf("width", "height").forEach { attribute ->
                            candidate.element.attr(attribute).takeIf(String::isNotBlank)?.let { value ->
                                attr(attribute, value)
                            }
                        }
                    }.also(candidate.element::replaceWith)
                } else {
                    candidate.element.apply {
                        attr("src", dataUri)
                        attr("loading", "lazy")
                        attr("decoding", "async")
                        removeAttr("srcset")
                        val caption = closest("figure")?.selectFirst("figcaption")?.text()?.trim()
                        if (attr("alt").isGenericFigureAlt() && !caption.isNullOrBlank()) {
                            attr("alt", caption.take(MAXIMUM_GENERATED_ALT_LENGTH))
                        }
                    }
                }
                if (image.attr("alt").isBlank()) image.attr("alt", "Figure image")
                embeddedBytes += safeBytes.size
                embeddedCount += 1
            }
        }
    }

    private data class FigureCandidate(
        val element: Element,
        val assetUrl: String,
    )

    private fun figureAlt(element: Element): String =
        element.attr("alt").takeIf { it.isNotBlank() && !it.isGenericFigureAlt() }
            ?: element.closest("figure")?.selectFirst("figcaption")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
            ?: "Figure image"

    /**
     * SVG is embedded as an image data URI, never as live markup. The allowlist is intentionally
     * strict: arXiv's generated figures use paths, groups, clip paths, and internal font/path
     * references, while scripts, styles, external references, and interactive SVG features are
     * unnecessary for a readable paper and unsafe to carry into a WebView.
     */
    private fun sanitizeSvg(bytes: ByteArray): ByteArray? {
        val source = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        if (SVG_FORBIDDEN_MARKUP.containsMatchIn(source)) return null

        val document = runCatching { Jsoup.parse(source, "", Parser.xmlParser()) }.getOrNull() ?: return null
        if (document.children().size != 1) return null
        val root = document.child(0)
        if (root.tagName().lowercase(Locale.ROOT) != "svg") return null
        val elements = root.getAllElements()
        if (elements.size > MAXIMUM_SVG_ELEMENT_COUNT) return null
        if (elements.any { !isSafeSvgElement(it) }) return null
        return source.toByteArray(Charsets.UTF_8)
    }

    private fun isSafeSvgElement(element: Element): Boolean {
        if (element.tagName().lowercase(Locale.ROOT) !in SAFE_SVG_TAGS) return false
        return element.attributes().all { attribute ->
            val name = attribute.key.lowercase(Locale.ROOT)
            name in SAFE_SVG_ATTRIBUTES && isSafeSvgAttribute(name, attribute.value)
        }
    }

    private fun isSafeSvgAttribute(name: String, value: String): Boolean {
        val normalized = value.trim()
        if (name == "xmlns") return normalized == SVG_NAMESPACE
        if (name == "xmlns:xlink") return normalized == SVG_XLINK_NAMESPACE
        if (
            SVG_DANGEROUS_VALUE.containsMatchIn(normalized) ||
            SVG_EXTERNAL_URL_REFERENCE.containsMatchIn(normalized)
        ) return false
        return when (name) {
            "href", "xlink:href" -> normalized.matches(SAFE_SVG_FRAGMENT)
            "clip-path", "filter", "mask", "marker-end", "marker-mid", "marker-start" ->
                normalized.isBlank() || normalized.matches(SAFE_SVG_URL_REFERENCE)
            "id" -> normalized.matches(SAFE_SVG_ID)
            else -> true
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
        val caption = image.closest("figure")?.selectFirst("figcaption")?.text()?.trim()
        image.replaceWith(unavailableFigurePlaceholder(image.attr("alt").takeIf { it.isNotBlank() } ?: caption))
    }

    private fun unavailableFigurePlaceholder(label: String?): Element = Element("span")
            .addClass("paperreader-figure-unavailable")
            .attr("role", "img")
            .attr("aria-label", label?.take(MAXIMUM_GENERATED_ALT_LENGTH) ?: "Figure unavailable")
            .text("Figure image unavailable offline. The caption is preserved below.")

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

    private fun readableSafelist(keepFigureSources: Boolean = false): Safelist {
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
        if (keepFigureSources) {
            // Figure URLs are resolved and host/path checked before fetching, then every source is
            // replaced with a data URI or an explicit placeholder by embedFigures(). Keep these
            // nodes only through that intermediate sanitization pass.
            safelist.addTags("object")
                .addAttributes("object", "data", "type", "width", "height", "alt")
                .addProtocols("object", "data", "https")
                .addProtocols("img", "src", "https")
        }
        return safelist
    }

    companion object {
        private const val MINIMUM_ARTICLE_TEXT_LENGTH = 300
        private const val MAXIMUM_GENERATED_ALT_LENGTH = 500
        private const val MAXIMUM_LICENSE_LENGTH = 160
        private const val MAXIMUM_SECTION_TITLE_LENGTH = 180
        private const val MAXIMUM_SECTION_COUNT = 120
        private const val MAXIMUM_CONCURRENT_FIGURE_REQUESTS = 2
        private const val MAXIMUM_SVG_ELEMENT_COUNT = 20_000
        private const val SVG_MEDIA_TYPE = "image/svg+xml"
        private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
        private const val SVG_XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
        private val SAFE_ANCHOR = Regex("[A-Za-z0-9._:-]{1,160}")
        private val SVG_FORBIDDEN_MARKUP = Regex("(?is)<!|<\\?")
        private val SVG_DANGEROUS_VALUE = Regex("(?i)(?:javascript|vbscript|data|file|https?):")
        private val SVG_EXTERNAL_URL_REFERENCE = Regex("(?i)(?:url\\(\\s*(?!#)|//)")
        private val SAFE_SVG_FRAGMENT = Regex("#[A-Za-z_][A-Za-z0-9_.:-]*")
        private val SAFE_SVG_ID = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
        private val SAFE_SVG_URL_REFERENCE = Regex("(?i)url\\(\\s*#[A-Za-z_][A-Za-z0-9_.:-]*\\s*\\)")
        private val SAFE_SVG_TAGS = setOf(
            "svg", "defs", "g", "path", "use", "clippath", "mask", "pattern",
            "lineargradient", "radialgradient", "stop", "rect", "circle", "ellipse",
            "line", "polyline", "polygon", "text", "tspan", "title", "desc", "symbol",
            "marker",
        )
        private val SAFE_SVG_ATTRIBUTES = setOf(
            "xmlns", "xmlns:xlink", "version", "width", "height", "viewbox", "data-name",
            "preserveaspectratio", "id", "clip-path", "d", "fill", "fill-opacity",
            "fill-rule", "stroke", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray",
            "stroke-miterlimit", "stroke-width", "transform", "x", "y", "x1", "x2", "y1",
            "y2", "cx", "cy", "r", "rx", "ry", "dx", "dy", "points", "opacity", "color",
            "font-family", "font-size", "font-style", "font-weight", "text-anchor",
            "dominant-baseline", "letter-spacing", "word-spacing", "display", "visibility",
            "vector-effect", "shape-rendering", "text-rendering", "paint-order", "href",
            "xlink:href", "data-text", "filter", "mask", "marker-end", "marker-mid",
            "marker-start",
        )
        private val CIRCLED_STEP_ARTIFACT = Regex(
            """\\raisebox\{[-+]?(?:\d+(?:\.\d+)?|\.\d+)pt\}\{\\scriptsize\s*([0-9]{1,2})\}⃝""",
        )
        private const val READABLE_BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,dt,dd,figcaption,pre,blockquote,th,td"
        private val SAFE_RASTER_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
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

internal fun String.isHtmlMediaType(): Boolean = normalizedMediaType() in setOf("text/html", "application/xhtml+xml")

private fun String.normalizedMediaType(): String = substringBefore(';').trim().lowercase()

private fun String.isGenericFigureAlt(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isBlank() || normalized == "refer to caption" || normalized == "[uncaptioned image]"
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
