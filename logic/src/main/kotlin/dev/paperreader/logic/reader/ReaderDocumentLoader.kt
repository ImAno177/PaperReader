package dev.paperreader.logic.reader

import kotlinx.coroutines.CancellationException

sealed interface ReaderDocument {
    data class Reflow(val prepared: PreparedDocument) : ReaderDocument

    data class OriginalPdfFallback(
        val source: PdfSource,
        val reason: FallbackReason,
        val detail: String?,
    ) : ReaderDocument
}

enum class FallbackReason {
    EMPTY_TEXT,
    INVALID_CACHE,
    EXTRACTION_FAILED,
}

/** Local-first loader strategy. Any extraction problem keeps the original PDF readable. */
class ReaderDocumentLoader(private val extractionService: ExtractionService) {
    suspend fun load(
        source: PdfSource,
        configuration: ExtractionConfiguration,
    ): ReaderDocument = try {
        ReaderDocument.Reflow(extractionService.prepare(source, configuration))
    } catch (_: ExtractionException.EmptyDocument) {
        ReaderDocument.OriginalPdfFallback(source, FallbackReason.EMPTY_TEXT, "No readable text was extracted")
    } catch (_: ExtractionException.InvalidCachedArtifact) {
        ReaderDocument.OriginalPdfFallback(source, FallbackReason.INVALID_CACHE, "Extraction cache is invalid")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ReaderDocument.OriginalPdfFallback(
            source = source,
            reason = FallbackReason.EXTRACTION_FAILED,
            detail = error.message?.take(200),
        )
    }
}
