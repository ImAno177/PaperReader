package dev.paperreader.logic.reader

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Handles figure URL resolution, SVG validation, and the legacy inline-figure compatibility path.
 * The reader path only creates opaque references; the loader owns downloading and caching bytes.
 */
internal class ArxivReadableFigureProcessor(
    private val maximumFigureCount: Int,
    private val maximumFigureBytes: Long,
    private val maximumSingleFigureBytes: Long,
) {
    /** Validates one fetched asset before it is published to the file-backed asset cache. */
    fun sanitizeAsset(resource: ReadableRemoteResource): ReadableRemoteResource? {
        val mediaType = resource.mediaType.normalizedMediaType()
        val safeBytes = when {
            resource.bytes.isEmpty() || resource.bytes.size.toLong() > maximumSingleFigureBytes -> null
            mediaType in SAFE_READABLE_ASSET_MEDIA_TYPES - SVG_MEDIA_TYPE -> resource.bytes
            mediaType == SVG_MEDIA_TYPE -> sanitizeSvg(resource.bytes)
            else -> null
        } ?: return null
        if (safeBytes.isEmpty() || safeBytes.size.toLong() > maximumSingleFigureBytes) return null
        return ReadableRemoteResource(safeBytes, mediaType)
    }

    fun replaceUnavailableAssetReferences(
        bodyHtml: String,
        unavailableIds: Set<String>,
    ): String {
        if (unavailableIds.isEmpty()) return bodyHtml
        val document = Jsoup.parseBodyFragment(bodyHtml)
        document.select("img[src^='paperreader-asset://']").toList().forEach { image ->
            val assetId = image.attr("src").removePrefix("paperreader-asset://")
            if (assetId in unavailableIds) replaceUnavailableFigure(image)
        }
        return document.body().html().trim()
    }

    /** Keeps the existing inline-data behavior for compatibility callers and old fixtures. */
    suspend fun embedFigures(
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
                for (index in nextImageIndex until images.size) replaceUnavailableFigure(images[index])
                return
            }
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
                if (mediaType == null || safeBytes == null || safeBytes.size.toLong() > availableBytes) {
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

    /** Replaces remote figure URLs with opaque IDs; no image-count budget is applied here. */
    fun referenceFigures(
        container: Element,
        sourceUri: URI,
        warnings: MutableSet<ReadablePaperWarning>,
    ): List<ReadablePaperAssetReference> {
        val references = linkedMapOf<String, ReadablePaperAssetReference>()
        container.select("img[src], object[data]").toList().forEach { element ->
            val rawAssetUrl = if (element.tagName().equals("object", ignoreCase = true)) {
                element.attr("data")
            } else {
                element.attr("src")
            }
            val assetUrl = resolveFigureUrl(rawAssetUrl, sourceUri)
            if (assetUrl == null) {
                warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
                replaceUnavailableFigure(element)
                return@forEach
            }
            val assetId = sha256(assetUrl.toByteArray(Charsets.UTF_8))
            references.putIfAbsent(assetId, ReadablePaperAssetReference(assetId, assetUrl))
            if (element.tagName().equals("object", ignoreCase = true)) {
                Element("img").apply {
                    attr("src", "paperreader-asset://$assetId")
                    attr("alt", figureAlt(element))
                    attr("loading", "lazy")
                    attr("decoding", "async")
                    listOf("width", "height").forEach { attribute ->
                        element.attr(attribute).takeIf(String::isNotBlank)?.let { value -> attr(attribute, value) }
                    }
                }.also(element::replaceWith)
            } else {
                element.attr("src", "paperreader-asset://$assetId")
                element.attr("loading", "lazy")
                element.attr("decoding", "async")
                element.removeAttr("srcset")
                val caption = element.closest("figure")?.selectFirst("figcaption")?.text()?.trim()
                if (element.attr("alt").isGenericFigureAlt() && !caption.isNullOrBlank()) {
                    element.attr("alt", caption.take(MAXIMUM_GENERATED_ALT_LENGTH))
                }
            }
        }
        return references.values.toList()
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

    /** SVG is stored as an image, never as live markup, after a strict allowlist check. */
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
        if (name == "href" || name == "xlink:href") {
            if (isSafeSvgDataImage(normalized)) return true
        }
        if (name == "xmlns") return normalized == SVG_NAMESPACE
        if (name == "xmlns:xlink") return normalized == SVG_XLINK_NAMESPACE
        if (SVG_DANGEROUS_VALUE.containsMatchIn(normalized) || SVG_EXTERNAL_URL_REFERENCE.containsMatchIn(normalized)) {
            return false
        }
        return when (name) {
            "href", "xlink:href" -> normalized.matches(SAFE_SVG_FRAGMENT)
            "clip-path", "filter", "mask", "marker-end", "marker-mid", "marker-start" ->
                normalized.isBlank() || normalized.matches(SAFE_SVG_URL_REFERENCE)
            "id" -> normalized.matches(SAFE_SVG_ID)
            else -> true
        }
    }

    private fun isSafeSvgDataImage(value: String): Boolean {
        val separator = value.indexOf(',')
        if (separator <= 0) return false
        val header = value.substring(0, separator).lowercase(Locale.ROOT)
        if (header !in SAFE_SVG_DATA_IMAGE_HEADERS) return false
        val encoded = value.substring(separator + 1).filterNot(Char::isWhitespace)
        val maximumEncodedBytes = (maximumSingleFigureBytes * 4L / 3L) + 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maximumEncodedBytes) return false
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        return decoded.isNotEmpty() && decoded.size.toLong() <= maximumSingleFigureBytes
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

    fun unavailableFigurePlaceholder(label: String?): Element = Element("span")
        .addClass("paperreader-figure-unavailable")
        .attr("role", "img")
        .attr("aria-label", label?.take(MAXIMUM_GENERATED_ALT_LENGTH) ?: "Figure unavailable")
        .text("Figure image unavailable offline. The caption is preserved below.")

    private fun String.isGenericFigureAlt(): Boolean {
        val normalized = trim().lowercase(Locale.ROOT)
        return normalized.isBlank() || normalized == "refer to caption" || normalized == "[uncaptioned image]"
    }

    companion object {
        private const val MAXIMUM_CONCURRENT_FIGURE_REQUESTS = 2
        private const val MAXIMUM_SVG_ELEMENT_COUNT = 100_000
        private const val MAXIMUM_GENERATED_ALT_LENGTH = 500
        private const val SVG_MEDIA_TYPE = "image/svg+xml"
        private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
        private const val SVG_XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
        private val SVG_FORBIDDEN_MARKUP = Regex("(?is)<!|<\\?")
        private val SVG_DANGEROUS_VALUE = Regex("(?i)(?:javascript|vbscript|data|file|https?):")
        private val SVG_EXTERNAL_URL_REFERENCE = Regex("(?i)(?:url\\(\\s*(?!#)|//)")
        private val SAFE_SVG_FRAGMENT = Regex("#[A-Za-z_][A-Za-z0-9_.:-]*")
        private val SAFE_SVG_ID = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
        private val SAFE_SVG_URL_REFERENCE = Regex("(?i)url\\(\\s*#[A-Za-z_][A-Za-z0-9_.:-]*\\s*\\)")
        private val SAFE_SVG_DATA_IMAGE_HEADERS = setOf(
            "data:image/png;base64",
            "data:image/jpeg;base64",
            "data:image/webp;base64",
            "data:image/gif;base64",
        )
        private val SAFE_SVG_TAGS = setOf(
            "svg", "defs", "g", "path", "use", "clippath", "mask", "pattern",
            "lineargradient", "radialgradient", "stop", "rect", "circle", "ellipse", "image",
            "line", "polyline", "polygon", "text", "tspan", "title", "desc", "symbol", "marker",
        )
        private val SAFE_SVG_ATTRIBUTES = setOf(
            "xmlns", "xmlns:xlink", "version", "width", "height", "viewbox", "data-name",
            "preserveaspectratio", "id", "clip-path", "d", "fill", "fill-opacity", "fill-rule",
            "stroke", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-dasharray",
            "stroke-miterlimit", "stroke-width", "transform", "x", "y", "x1", "x2", "y1", "y2",
            "cx", "cy", "r", "rx", "ry", "dx", "dy", "points", "opacity", "color", "font-family",
            "font-size", "font-style", "font-weight", "text-anchor", "dominant-baseline",
            "letter-spacing", "word-spacing", "display", "visibility", "vector-effect", "shape-rendering",
            "text-rendering", "paint-order", "href", "xlink:href", "data-text", "filter", "mask",
            "marker-end", "marker-mid", "marker-start",
        )
        private val SAFE_RASTER_MEDIA_TYPES = SAFE_READABLE_ASSET_MEDIA_TYPES - SVG_MEDIA_TYPE
    }
}
