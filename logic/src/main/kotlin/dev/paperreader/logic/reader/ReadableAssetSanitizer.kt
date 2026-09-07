package dev.paperreader.logic.reader

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Final provider-neutral validation for bytes and URLs entering the app-private asset cache. */
internal class ReadableAssetSanitizer(
    private val maximumAssetBytes: Long = MAXIMUM_READABLE_ASSET_BYTES,
) {
    init {
        require(maximumAssetBytes > 0)
    }

    fun sanitize(resource: ReadableRemoteResource): ReadableRemoteResource? {
        val mediaType = resource.mediaType.normalizedMediaType()
        val safeBytes = when {
            resource.bytes.isEmpty() || resource.bytes.size.toLong() > maximumAssetBytes -> null
            mediaType in SAFE_READABLE_ASSET_MEDIA_TYPES - SVG_MEDIA_TYPE -> resource.bytes
            mediaType == SVG_MEDIA_TYPE -> sanitizeSvg(resource.bytes)
            else -> null
        } ?: return null
        if (safeBytes.isEmpty() || safeBytes.size.toLong() > maximumAssetBytes) return null
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
            if (assetId in unavailableIds) {
                image.replaceWith(unavailableFigurePlaceholder(image.attr("alt")))
            }
        }
        return document.body().html().trim()
    }

    fun isSafeDocumentUrl(value: String): Boolean = runCatching {
        URI(value).let { uri ->
            value.length <= MAXIMUM_URL_LENGTH &&
                uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.query == null &&
                uri.port == -1 &&
                !uri.path.isNullOrBlank()
        }
    }.getOrDefault(false)

    fun isTrustedAssetUrl(documentUrl: String, assetUrl: String): Boolean = runCatching {
        val document = URI(documentUrl)
        val asset = URI(assetUrl)
        val documentPath = canonicalPath(document)
        val assetPath = canonicalPath(asset)
        isSafeDocumentUrl(documentUrl) &&
            assetUrl.length <= MAXIMUM_URL_LENGTH &&
            asset.scheme == "https" &&
            asset.host.equals(document.host, ignoreCase = true) &&
            asset.userInfo == null &&
            asset.fragment == null &&
            asset.port == -1 &&
            asset.query == null &&
            documentPath != null &&
            assetPath != null &&
            assetPath.startsWith(documentPath.trimEnd('/') + "/")
    }.getOrDefault(false)

    private fun canonicalPath(uri: URI): String? {
        val rawPath = uri.rawPath ?: return null
        // URI.path decodes one level of percent escapes. Reject encoded percent signs as well so
        // a downstream URL canonicalizer cannot turn a double-encoded traversal into `..`.
        if (rawPath.contains("%25", ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (path.contains('\\') || path.split('/').any { it == "." || it == ".." }) return null
        val normalized = uri.normalize().path ?: return null
        return normalized.takeIf { it == path }
    }

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
        if (root.getAllElements().size > MAXIMUM_SVG_ELEMENT_COUNT) return null
        if (root.getAllElements().any { !isSafeSvgElement(it) }) return null
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
        val maximumEncodedBytes = (maximumAssetBytes * 4L / 3L) + 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maximumEncodedBytes) return false
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
        return decoded.isNotEmpty() && decoded.size.toLong() <= maximumAssetBytes
    }

    private fun unavailableFigurePlaceholder(label: String?): Element = Element("span")
        .addClass("paperreader-figure-unavailable")
        .attr("role", "img")
        .attr("aria-label", label?.trim()?.takeIf(String::isNotBlank)?.take(MAXIMUM_ALT_LENGTH) ?: "Figure unavailable")
        .text("Figure image unavailable offline. The caption is preserved below.")

    companion object {
        private const val MAXIMUM_URL_LENGTH = 2_048
        private const val MAXIMUM_ALT_LENGTH = 500
        private const val MAXIMUM_SVG_ELEMENT_COUNT = 100_000
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
    }
}
