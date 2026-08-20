package dev.paperreader.app.reader

import android.content.ContentResolver
import android.net.Uri
import dev.paperreader.logic.reader.ReadablePaperDocument
import java.io.IOException
import java.io.OutputStream

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

internal fun renderReadablePaperExportHtml(document: ReadablePaperDocument): String =
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
    )

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
}

private fun writeFully(output: OutputStream, bytes: ByteArray) {
    output.write(bytes)
    output.flush()
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

private const val MAXIMUM_EXPORT_HTML_BYTES = 24L * 1024L * 1024L
private const val MAX_FILENAME_TITLE_LENGTH = 80
private const val MAX_FILENAME_VERSION_LENGTH = 16
private const val EXPORT_SANITIZER_POLICY_VERSION = "arxiv-html-sanitizer-9"
private const val EXPORT_RENDERER_CONTRACT_VERSION = "mobile-html-6"
private val UNSAFE_FILENAME_CHARS = Regex("[^\\p{L}\\p{N}._-]+")
