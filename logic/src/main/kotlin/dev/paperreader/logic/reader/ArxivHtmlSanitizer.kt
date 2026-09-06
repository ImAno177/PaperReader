package dev.paperreader.logic.reader

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
    val assets: List<ReadablePaperAssetReference> = emptyList(),
)

internal class ArxivHtmlSanitizer(
    private val maximumFigureCount: Int = 24,
    private val maximumFigureBytes: Long = 6L * 1024L * 1024L,
    private val maximumSingleFigureBytes: Long = MAXIMUM_READABLE_ASSET_BYTES,
) {
    private val figureProcessor = ArxivReadableFigureProcessor(
        maximumFigureCount = maximumFigureCount,
        maximumFigureBytes = maximumFigureBytes,
        maximumSingleFigureBytes = maximumSingleFigureBytes,
    )

    init {
        require(maximumFigureCount >= 0)
        require(maximumFigureBytes >= 0)
        require(maximumSingleFigureBytes > 0)
    }

    /**
     * Sanitizes the document structure without loading figure bytes. The loader materializes the
     * returned references in a separate file-backed asset lane.
     */
    suspend fun sanitizeForReader(
        rawHtml: String,
        sourceUrl: String,
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
        if (sections.isEmpty()) warnings += ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING
        container.appendChild(article)

        val cleaned = Jsoup.clean(
            container.outerHtml(),
            sourceUrl,
            readableSafelist(keepFigureSources = true),
            Document.OutputSettings().prettyPrint(false),
        )
        val cleanedDocument = Jsoup.parseBodyFragment(cleaned, sourceUrl)
        normalizeLinks(cleanedDocument, sourceUri)
        val assets = figureProcessor.referenceFigures(cleanedDocument.body(), sourceUri, warnings)
        val bodyHtml = cleanedDocument.body().html().trim()
        if (cleanedDocument.body().text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return null
        if (containsExecutableMarkup(cleanedDocument)) return null
        return SanitizedReadableHtml(bodyHtml, sourceLicense, sections, warnings, assets)
    }

    /** Validates one fetched asset and returns bytes safe to publish to the file cache. */
    fun sanitizeAsset(resource: ReadableRemoteResource): ReadableRemoteResource? =
        figureProcessor.sanitizeAsset(resource)

    fun replaceUnavailableAssetReferences(
        bodyHtml: String,
        unavailableIds: Set<String>,
    ): String = figureProcessor.replaceUnavailableAssetReferences(bodyHtml, unavailableIds)

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
        figureProcessor.embedFigures(cleanedDocument.body(), sourceUri, warnings, fetchAsset)
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
                vector.replaceWith(figureProcessor.unavailableFigurePlaceholder(caption))
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
        private const val MAXIMUM_LICENSE_LENGTH = 160
        private const val MAXIMUM_SECTION_TITLE_LENGTH = 180
        private const val MAXIMUM_SECTION_COUNT = 120
        private const val SVG_MEDIA_TYPE = "image/svg+xml"
        private val SAFE_ANCHOR = Regex("[A-Za-z0-9._:-]{1,160}")
        private val CIRCLED_STEP_ARTIFACT = Regex(
            """\\raisebox\{[-+]?(?:\d+(?:\.\d+)?|\.\d+)pt\}\{\\scriptsize\s*([0-9]{1,2})\}⃝""",
        )
        private const val READABLE_BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,dt,dd,figcaption,pre,blockquote,th,td"
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

internal fun String.normalizedMediaType(): String = substringBefore(';').trim().lowercase()

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
