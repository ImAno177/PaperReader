package dev.paperreader.logic.reader

import java.time.Clock

data class ExtractionConfiguration(
    val extractionSchema: Int,
    val parserVersion: String,
    val rendererVersion: String,
    val options: ParseOptions = ParseOptions(),
) {
    init {
        require(extractionSchema > 0)
        require(parserVersion.isNotBlank())
        require(rendererVersion.isNotBlank())
    }

    fun cacheKey(pdfSha256: String): ExtractionCacheKey = ExtractionCacheKey(
        pdfSha256 = pdfSha256,
        extractionSchema = extractionSchema,
        parserVersion = parserVersion,
        parseOptionsHash = ExtractionCacheKey.optionsHash(options),
        rendererVersion = rendererVersion,
    )
}

data class PreparedDocument(
    val document: ExtractedDocument,
    val manifest: ExtractionManifest,
    val fromCache: Boolean,
)

interface ExtractionArtifactStore {
    suspend fun read(cacheDigest: String): PreparedDocument?

    /** Implementations must publish the document and manifest atomically. */
    suspend fun write(document: PreparedDocument)
}

sealed class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class EmptyDocument : ExtractionException("PDF extraction produced no readable text")
    class InvalidCachedArtifact : ExtractionException("Cached extraction does not match its cache key")
    class InvalidExtractedDocument : ExtractionException("PDF extraction produced structurally invalid content")
}

class ExtractionService(
    private val extractor: PdfTextExtractor,
    private val store: ExtractionArtifactStore,
    private val clock: Clock,
) {
    suspend fun prepare(
        source: PdfSource,
        configuration: ExtractionConfiguration,
    ): PreparedDocument {
        val key = configuration.cacheKey(source.sha256)
        val cached = store.read(key.digest)
        if (cached != null) {
            if (cached.manifest.cacheKey != key) throw ExtractionException.InvalidCachedArtifact()
            try {
                validatePrepared(cached, key)
            } catch (_: ExtractionException) {
                throw ExtractionException.InvalidCachedArtifact()
            }
            return cached.copy(fromCache = true)
        }

        val extracted = extractor.extract(source, configuration.options)
        validateExtracted(extracted)
        val manifest = ExtractionManifest(
            cacheKey = key,
            markdownSha256 = ExtractionCacheKey.contentHash(extracted.markdown),
            pageCount = extracted.pageCount,
            hasOcr = extracted.hasOcrWarning(),
            hasLayoutWarnings = extracted.hasLayoutWarnings(),
            createdAt = clock.instant(),
        )
        return PreparedDocument(extracted, manifest, fromCache = false).also {
            validatePrepared(it, key)
            store.write(it)
        }
    }

    private fun validatePrepared(document: PreparedDocument, requestedKey: ExtractionCacheKey) {
        if (document.manifest.cacheKey != requestedKey) {
            throw ExtractionException.InvalidCachedArtifact()
        }
        validateExtracted(document.document)
        if (document.manifest.pageCount != document.document.pageCount ||
            document.manifest.hasOcr != document.document.hasOcrWarning() ||
            document.manifest.hasLayoutWarnings != document.document.hasLayoutWarnings() ||
            !document.manifest.markdownSha256.equals(
                ExtractionCacheKey.contentHash(document.document.markdown),
                ignoreCase = true,
            )
        ) {
            throw ExtractionException.InvalidCachedArtifact()
        }
    }

    private fun ExtractedDocument.hasOcrWarning(): Boolean =
        warnings.any { it.contains("ocr", ignoreCase = true) }

    private fun ExtractedDocument.hasLayoutWarnings(): Boolean =
        warnings.any { !it.contains("ocr", ignoreCase = true) }

    private fun validateExtracted(document: ExtractedDocument) {
        if (document.markdown.isBlank() || document.blocks.isEmpty()) {
            throw ExtractionException.EmptyDocument()
        }
        if (document.pageCount <= 0 ||
            document.blocks.map(DocumentBlock::id).toSet().size != document.blocks.size ||
            document.blocks.any { block ->
                block.id.isBlank() ||
                    block.markdown.isBlank() && block.plainText.isBlank() ||
                    block.sourceRanges.isEmpty() ||
                    block.sourceRanges.any { range ->
                        range.pageIndex !in 0 until document.pageCount ||
                            range.startOffset < 0 ||
                            range.endOffset < range.startOffset ||
                            range.endOffset > block.plainText.length
                    }
            }
        ) {
            throw ExtractionException.InvalidExtractedDocument()
        }
    }
}
