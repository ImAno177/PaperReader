package dev.paperreader.logic.reader

import java.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentLoaderTest {
    private val source = PdfSource("content://papers/a.pdf", "a".repeat(64), 10)
    private val configuration = ExtractionConfiguration(1, "pdf-inspector@abc", "markdown-v1")

    @Test
    fun `loads reflow content when local extraction succeeds`() = runTest {
        val loader = loader(
            ExtractedDocument(
                markdown = "Readable",
                blocks = listOf(DocumentBlock("b1", "Readable", "Readable", listOf(SourceRange(0, 0, 8)))),
                pageCount = 1,
            ),
        )

        val result = loader.load(source, configuration)

        assertTrue(result is ReaderDocument.Reflow)
        assertFalse((result as ReaderDocument.Reflow).prepared.fromCache)
    }

    @Test
    fun `empty extraction explicitly falls back to original PDF`() = runTest {
        val loader = loader(ExtractedDocument("", emptyList(), 1, setOf("needs-ocr")))

        val result = loader.load(source, configuration) as ReaderDocument.OriginalPdfFallback

        assertEquals(FallbackReason.EMPTY_TEXT, result.reason)
        assertEquals(source, result.source)
    }

    private fun loader(document: ExtractedDocument): ReaderDocumentLoader {
        val extractor = object : PdfTextExtractor {
            override suspend fun extract(source: PdfSource, options: ParseOptions) = document
        }
        val store = object : ExtractionArtifactStore {
            override suspend fun read(cacheDigest: String): PreparedDocument? = null
            override suspend fun write(document: PreparedDocument) = Unit
        }
        return ReaderDocumentLoader(ExtractionService(extractor, store, Clock.systemUTC()))
    }
}
