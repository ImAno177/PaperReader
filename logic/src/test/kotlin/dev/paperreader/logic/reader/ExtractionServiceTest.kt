package dev.paperreader.logic.reader

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionServiceTest {
    private val source = PdfSource("content://papers/example.pdf", "a".repeat(64), 42)
    private val configuration = ExtractionConfiguration(1, "pdf-inspector@abc", "markdown-v1")
    private val extracted = ExtractedDocument(
        markdown = "# A paper\n\nReadable text.",
        blocks = listOf(
            DocumentBlock(
                id = "page-0-block-0",
                markdown = "Readable text.",
                plainText = "Readable text.",
                sourceRanges = listOf(SourceRange(0, 0, 14)),
            ),
        ),
        pageCount = 1,
    )

    @Test
    fun `extracts writes and then reuses the exact artifact`() = runTest {
        var extractionCount = 0
        val store = MemoryStore()
        val service = ExtractionService(
            extractor = object : PdfTextExtractor {
                override suspend fun extract(source: PdfSource, options: ParseOptions): ExtractedDocument {
                    extractionCount += 1
                    return extracted
                }
            },
            store = store,
            clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
        )

        val first = service.prepare(source, configuration)
        val second = service.prepare(source, configuration)

        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(1, extractionCount)
        assertEquals(first.manifest, second.manifest)
        assertEquals(Instant.parse("2026-08-11T00:00:00Z"), first.manifest.createdAt)
    }

    @Test
    fun `extraction manifest records OCR and layout warning categories`() = runTest {
        val warned = extracted.copy(warnings = setOf("OCR fallback used", "reading order uncertain"))
        val store = MemoryStore()
        val service = ExtractionService(
            extractor = object : PdfTextExtractor {
                override suspend fun extract(source: PdfSource, options: ParseOptions) = warned
            },
            store = store,
            clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
        )

        val prepared = service.prepare(source, configuration)

        assertTrue(prepared.manifest.hasOcr)
        assertTrue(prepared.manifest.hasLayoutWarnings)
        assertEquals(warned, prepared.document)
    }

    @Test
    fun `blank extraction fails instead of inventing readable content`() = runTest {
        val service = ExtractionService(
            extractor = object : PdfTextExtractor {
                override suspend fun extract(source: PdfSource, options: ParseOptions) =
                    ExtractedDocument("", emptyList(), 1, setOf("needs-ocr"))
            },
            store = MemoryStore(),
            clock = Clock.systemUTC(),
        )

        var failedAsExpected = false
        try {
            service.prepare(source, configuration)
        } catch (_: ExtractionException.EmptyDocument) {
            failedAsExpected = true
        }
        assertTrue(failedAsExpected)
    }

    @Test
    fun `matching cache key cannot hide a tampered markdown payload`() = runTest {
        val key = configuration.cacheKey(source.sha256)
        val cached = PreparedDocument(
            document = extracted,
            manifest = ExtractionManifest(
                cacheKey = key,
                markdownSha256 = "b".repeat(64),
                pageCount = extracted.pageCount,
                hasOcr = false,
                hasLayoutWarnings = false,
                createdAt = Instant.EPOCH,
            ),
            fromCache = false,
        )
        val store = MemoryStore().apply { put(key.digest, cached) }
        val service = ExtractionService(
            extractor = errorExtractor(),
            store = store,
            clock = Clock.systemUTC(),
        )

        var failedAsExpected = false
        try {
            service.prepare(source, configuration)
        } catch (_: ExtractionException.InvalidCachedArtifact) {
            failedAsExpected = true
        }
        assertTrue(failedAsExpected)
    }

    @Test
    fun `matching cache key cannot hide a manifest page-count mismatch`() = runTest {
        val key = configuration.cacheKey(source.sha256)
        val cached = PreparedDocument(
            document = extracted,
            manifest = ExtractionManifest(
                cacheKey = key,
                markdownSha256 = ExtractionCacheKey.contentHash(extracted.markdown),
                pageCount = extracted.pageCount + 1,
                hasOcr = false,
                hasLayoutWarnings = false,
                createdAt = Instant.EPOCH,
            ),
            fromCache = false,
        )
        val store = MemoryStore().apply { put(key.digest, cached) }
        val service = ExtractionService(errorExtractor(), store, Clock.systemUTC())

        var failedAsExpected = false
        try {
            service.prepare(source, configuration)
        } catch (_: ExtractionException.InvalidCachedArtifact) {
            failedAsExpected = true
        }
        assertTrue(failedAsExpected)
    }

    @Test
    fun `matching cache key cannot hide extraction quality warnings`() = runTest {
        val key = configuration.cacheKey(source.sha256)
        val warnedDocument = extracted.copy(warnings = setOf("OCR fallback used", "layout order uncertain"))
        val cached = PreparedDocument(
            document = warnedDocument,
            manifest = ExtractionManifest(
                cacheKey = key,
                markdownSha256 = ExtractionCacheKey.contentHash(warnedDocument.markdown),
                pageCount = warnedDocument.pageCount,
                hasOcr = false,
                hasLayoutWarnings = false,
                createdAt = Instant.EPOCH,
            ),
            fromCache = false,
        )
        val service = ExtractionService(
            extractor = errorExtractor(),
            store = MemoryStore().apply { put(key.digest, cached) },
            clock = Clock.systemUTC(),
        )

        var failedAsExpected = false
        try {
            service.prepare(source, configuration)
        } catch (_: ExtractionException.InvalidCachedArtifact) {
            failedAsExpected = true
        }
        assertTrue(failedAsExpected)
    }

    @Test
    fun `structurally invalid parser output is rejected before cache write`() = runTest {
        val malformed = listOf(
            extracted.copy(
                blocks = listOf(extracted.blocks.single(), extracted.blocks.single()),
            ),
            extracted.copy(
                blocks = listOf(extracted.blocks.single().copy(sourceRanges = emptyList())),
            ),
            extracted.copy(
                blocks = listOf(extracted.blocks.single().copy(sourceRanges = listOf(SourceRange(1, 0, 1)))),
            ),
            extracted.copy(
                blocks = listOf(extracted.blocks.single().copy(sourceRanges = listOf(SourceRange(0, 0, 15)))),
            ),
        )

        malformed.forEach { document ->
            val store = MemoryStore()
            val service = ExtractionService(
                extractor = object : PdfTextExtractor {
                    override suspend fun extract(source: PdfSource, options: ParseOptions) = document
                },
                store = store,
                clock = Clock.systemUTC(),
            )

            var failedAsExpected = false
            try {
                service.prepare(source, configuration)
            } catch (_: ExtractionException.InvalidExtractedDocument) {
                failedAsExpected = true
            }
            assertTrue(failedAsExpected)
            assertTrue(store.isEmpty())
        }
    }

    @Test
    fun `extractor cancellation is propagated`() = runTest {
        val service = ExtractionService(
            extractor = object : PdfTextExtractor {
                override suspend fun extract(source: PdfSource, options: ParseOptions): ExtractedDocument =
                    throw CancellationException("cancelled")
            },
            store = MemoryStore(),
            clock = Clock.systemUTC(),
        )

        var cancelled = false
        try {
            service.prepare(source, configuration)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private fun errorExtractor() = object : PdfTextExtractor {
        override suspend fun extract(source: PdfSource, options: ParseOptions): ExtractedDocument =
            error("cache hit must not invoke extractor")
    }

    private class MemoryStore : ExtractionArtifactStore {
        private val values = mutableMapOf<String, PreparedDocument>()

        override suspend fun read(cacheDigest: String): PreparedDocument? = values[cacheDigest]

        override suspend fun write(document: PreparedDocument) {
            values[document.manifest.cacheKey.digest] = document
        }

        fun put(cacheDigest: String, document: PreparedDocument) {
            values[cacheDigest] = document
        }

        fun isEmpty(): Boolean = values.isEmpty()
    }
}
