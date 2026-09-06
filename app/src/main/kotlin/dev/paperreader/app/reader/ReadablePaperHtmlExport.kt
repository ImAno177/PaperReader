package dev.paperreader.app.reader

import android.content.ContentResolver
import android.net.Uri
import dev.paperreader.logic.reader.ReadablePaperAsset
import dev.paperreader.logic.reader.ReadablePaperDocument
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReadablePaperExportMetadata(
    val title: String,
    val sourceUrl: String,
    val sourceProvider: String,
    val sourceVersion: String,
    val license: String?,
    val retrievedAt: String,
    val sourceSha256: String,
    val documentSha256: String,
    val sanitizerPolicyVersion: String,
    val rendererContractVersion: String,
)

internal fun renderReadablePaperExportHtml(
    document: ReadablePaperDocument,
    assetBytes: Map<String, ByteArray> = emptyMap(),
): String {
    val assetsById = document.assets.associateBy { it.id }
    return renderReadablePaperHtml(
        sanitizedBodyHtml = document.bodyHtml,
        palette = ReadablePaperPalette(
            background = "#FFFFFF",
            surface = "#F4F6F8",
            text = "#17202A",
            mutedText = "#52606D",
            border = "#8A96A3",
            link = "#0645AD",
            selection = "#DDEBFF",
        ),
        dark = false,
        rewriteCitationLinks = false,
        exportMetadata = ReadablePaperExportMetadata(
            title = document.title,
            sourceUrl = document.sourceUrl,
            sourceProvider = document.sourceProvider,
            sourceVersion = document.sourceVersion,
            license = document.license,
            retrievedAt = document.retrievedAt.toString(),
            sourceSha256 = document.sourceSha256,
            documentSha256 = document.documentSha256,
            sanitizerPolicyVersion = EXPORT_SANITIZER_POLICY_VERSION,
            rendererContractVersion = EXPORT_RENDERER_CONTRACT_VERSION,
        ),
        assetUrlForId = { assetId ->
            val asset = checkNotNull(assetsById[assetId]) { "Readable asset metadata is missing" }
            val bytes = checkNotNull(assetBytes[assetId]) { "Readable asset bytes are missing" }
            "data:${asset.mediaType};base64,${Base64.getEncoder().encodeToString(bytes)}"
        },
    )
}

/**
 * Writes a self-contained export without building Base64-expanded assets into one giant String.
 * The HTML template is rendered with private sentinels, then each verified asset is encoded as
 * it is written. This keeps the peak heap bounded by the renderer plus one asset.
 */
internal suspend fun writeReadablePaperExportHtml(
    document: ReadablePaperDocument,
    readAsset: suspend (ReadablePaperAsset) -> ByteArray?,
    output: OutputStream,
) {
    val assetsById = document.assets.associateBy(ReadablePaperAsset::id)
    require(assetsById.size == document.assets.size) { "Readable asset metadata contains duplicates" }
    val tokenizedHtml = withContext(Dispatchers.Default) {
        renderReadablePaperHtml(
            sanitizedBodyHtml = document.bodyHtml,
            palette = ReadablePaperPalette(
                background = "#FFFFFF",
                surface = "#F4F6F8",
                text = "#17202A",
                mutedText = "#52606D",
                border = "#8A96A3",
                link = "#0645AD",
                selection = "#DDEBFF",
            ),
            dark = false,
            rewriteCitationLinks = false,
            exportMetadata = ReadablePaperExportMetadata(
                title = document.title,
                sourceUrl = document.sourceUrl,
                sourceProvider = document.sourceProvider,
                sourceVersion = document.sourceVersion,
                license = document.license,
                retrievedAt = document.retrievedAt.toString(),
                sourceSha256 = document.sourceSha256,
                documentSha256 = document.documentSha256,
                sanitizerPolicyVersion = EXPORT_SANITIZER_POLICY_VERSION,
                rendererContractVersion = EXPORT_RENDERER_CONTRACT_VERSION,
            ),
            assetUrlForId = { assetId ->
                require(assetId in assetsById) { "Readable asset metadata is missing" }
                "$EXPORT_ASSET_TOKEN_PREFIX$assetId$EXPORT_ASSET_TOKEN_SUFFIX"
            },
        )
    }
    val limitedOutput = LimitedOutputStream(output, MAXIMUM_EXPORT_HTML_BYTES)
    var cursor = 0
    EXPORT_ASSET_TOKEN.findAll(tokenizedHtml).forEach { match ->
        limitedOutput.writeUtf8(tokenizedHtml.substring(cursor, match.range.first))
        val assetId = match.groupValues[1]
        val asset = checkNotNull(assetsById[assetId]) { "Readable asset metadata is missing" }
        val bytes = checkNotNull(readAsset(asset)) { "Readable asset bytes are missing: $assetId" }
        require(bytes.size.toLong() == asset.byteLength) { "Readable asset size changed: $assetId" }
        limitedOutput.writeUtf8("data:${asset.mediaType};base64,")
        writeBase64(limitedOutput, bytes)
        cursor = match.range.last + 1
    }
    limitedOutput.writeUtf8(tokenizedHtml.substring(cursor))
    limitedOutput.flush()
}

internal fun readablePaperHtmlFileName(document: ReadablePaperDocument): String {
    val title = document.title
        .trim()
        .replace(UNSAFE_FILENAME_CHARS, "_")
        .trim('_', '.', ' ')
        .take(MAX_FILENAME_TITLE_LENGTH)
        .ifBlank { "paper" }
    val version = document.sourceVersion
        .trim()
        .replace(UNSAFE_FILENAME_CHARS, "_")
        .trim('_', '.', ' ')
        .take(MAX_FILENAME_VERSION_LENGTH)
        .ifBlank { "latest" }
    return "$title-$version.html"
}

internal class ReadablePaperHtmlFileGateway(
    private val contentResolver: ContentResolver,
) {
    suspend fun write(uri: Uri, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAXIMUM_EXPORT_HTML_BYTES) {
            "Readable HTML export exceeds the safety limit"
        }
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("The selected HTML destination could not be opened")
        output.use { writeFully(it, bytes) }
    }

    suspend fun write(uri: Uri, writeContent: suspend (OutputStream) -> Unit) {
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("The selected HTML destination could not be opened")
        output.use { writeContent(it) }
    }
}

private fun writeFully(output: OutputStream, bytes: ByteArray) {
    output.write(bytes)
    output.flush()
}

private fun writeBase64(output: OutputStream, bytes: ByteArray) {
    Base64.getEncoder().wrap(output).use { encoded ->
        encoded.write(bytes)
        encoded.flush()
    }
}

private fun OutputStream.writeUtf8(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}

private class LimitedOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Long,
) : OutputStream() {
    private var writtenBytes = 0L

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
        writtenBytes += 1
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        reserve(length.toLong())
        delegate.write(bytes, offset, length)
        writtenBytes += length
    }

    override fun flush() = delegate.flush()

    override fun close() = flush()

    private fun reserve(length: Long) {
        require(length >= 0 && writtenBytes <= maximumBytes - length) {
            "Readable HTML export exceeds the safety limit"
        }
    }
}

internal fun ReadablePaperExportMetadata.toHeadMetadata(): String = buildString {
    appendMeta("paperreader-export-format", "sanitized-readable-html")
    appendMeta("paperreader-title", title)
    appendMeta("paperreader-source-provider", sourceProvider)
    appendMeta("paperreader-source-version", sourceVersion)
    appendMeta("paperreader-source-url", sourceUrl)
    appendMeta("paperreader-license", license ?: "not supplied")
    appendMeta("paperreader-retrieved-at", retrievedAt)
    appendMeta("paperreader-source-sha256", sourceSha256)
    appendMeta("paperreader-document-sha256", documentSha256)
    appendMeta("paperreader-sanitizer-policy-version", sanitizerPolicyVersion)
    appendMeta("paperreader-renderer-contract-version", rendererContractVersion)
}

internal fun ReadablePaperExportMetadata.toProvenanceMarkup(): String = """
    <aside class="paperreader-export-provenance" aria-label="Paper Reader provenance">
      <strong>Paper Reader HTML export</strong><br>
      Source: ${escapeHtml(sourceProvider)} ${escapeHtml(sourceVersion)} · ${escapeHtml(sourceUrl)}<br>
      Retrieved: ${escapeHtml(retrievedAt)} · License: ${escapeHtml(license ?: "not supplied")}<br>
      Document SHA-256: ${escapeHtml(documentSha256)}
    </aside>
""".trimIndent()

private fun StringBuilder.appendMeta(name: String, value: String) {
    append("<meta name=\"")
    append(escapeHtml(name))
    append("\" content=\"")
    append(escapeHtml(value))
    append("\">\n          ")
}

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(character)
        }
    }
}

private const val MAXIMUM_EXPORT_HTML_BYTES = 64L * 1024L * 1024L
private const val MAX_FILENAME_TITLE_LENGTH = 80
private const val MAX_FILENAME_VERSION_LENGTH = 16
private const val EXPORT_SANITIZER_POLICY_VERSION = "arxiv-html-sanitizer-10"
private const val EXPORT_RENDERER_CONTRACT_VERSION = "mobile-html-7"
private const val EXPORT_ASSET_TOKEN_PREFIX = "\u0001paperreader-export-asset:"
private const val EXPORT_ASSET_TOKEN_SUFFIX = "\u0002"
private val EXPORT_ASSET_TOKEN = Regex(
    "${Regex.escape(EXPORT_ASSET_TOKEN_PREFIX)}([0-9a-f]{64})${Regex.escape(EXPORT_ASSET_TOKEN_SUFFIX)}",
)
private val UNSAFE_FILENAME_CHARS = Regex("[^\\p{L}\\p{N}._-]+")
