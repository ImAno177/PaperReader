package dev.paperreader.logic.reader

import java.security.MessageDigest
import java.time.Instant

data class ParseOptions(
    val detectTables: Boolean = true,
    val preserveReadingOrder: Boolean = true,
    val extractImages: Boolean = false,
) {
    fun stableValue(): String = listOf(detectTables, preserveReadingOrder, extractImages).joinToString(":")
}

data class ExtractionCacheKey(
    val pdfSha256: String,
    val extractionSchema: Int,
    val parserVersion: String,
    val parseOptionsHash: String,
    val rendererVersion: String,
) {
    init {
        require(pdfSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid PDF SHA-256" }
        require(extractionSchema > 0)
        require(parserVersion.isNotBlank())
        require(parseOptionsHash.isNotBlank())
        require(rendererVersion.isNotBlank())
    }

    val digest: String by lazy {
        sha256(
            listOf(
                pdfSha256.lowercase(),
                extractionSchema,
                parserVersion,
                parseOptionsHash,
                rendererVersion,
            ).joinToString("\n"),
        )
    }

    companion object {
        fun optionsHash(options: ParseOptions): String = sha256(options.stableValue())

        fun contentHash(content: String): String = sha256(content)

        private fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class PdfSource(
    val uri: String,
    val sha256: String,
    val byteSize: Long,
) {
    init {
        require(uri.isNotBlank())
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(byteSize > 0)
    }
}

data class SourceRange(
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(pageIndex >= 0)
        require(startOffset >= 0 && endOffset >= startOffset)
    }
}

data class DocumentBlock(
    val id: String,
    val markdown: String,
    val plainText: String,
    val sourceRanges: List<SourceRange>,
) {
    init {
        require(id.isNotBlank())
        require(markdown.isNotBlank() || plainText.isNotBlank())
    }
}

data class ExtractedDocument(
    val markdown: String,
    val blocks: List<DocumentBlock>,
    val pageCount: Int,
    val warnings: Set<String> = emptySet(),
) {
    init {
        require(pageCount > 0)
    }
}

data class ExtractionManifest(
    val cacheKey: ExtractionCacheKey,
    val markdownSha256: String,
    val pageCount: Int,
    val hasOcr: Boolean,
    val hasLayoutWarnings: Boolean,
    val createdAt: Instant,
)

interface PdfTextExtractor {
    /** Implemented by the future pdf-inspector JNI adapter; never by UI code. */
    suspend fun extract(source: PdfSource, options: ParseOptions): ExtractedDocument
}

sealed interface ExtractionDecision {
    data class UseCached(val manifest: ExtractionManifest) : ExtractionDecision
    data class Extract(val key: ExtractionCacheKey) : ExtractionDecision
}

object ExtractionPlanner {
    fun decide(
        requested: ExtractionCacheKey,
        cached: ExtractionManifest?,
    ): ExtractionDecision = if (cached?.cacheKey == requested) {
        ExtractionDecision.UseCached(cached)
    } else {
        ExtractionDecision.Extract(requested)
    }
}
