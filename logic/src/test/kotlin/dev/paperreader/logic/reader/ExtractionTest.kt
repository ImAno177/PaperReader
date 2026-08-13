package dev.paperreader.logic.reader

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExtractionTest {
    private val pdfHash = "a".repeat(64)

    @Test
    fun `cache digest is stable and changes with parser inputs`() {
        val options = ParseOptions()
        val first = key(parserVersion = "pdf-inspector@abc", options = options)
        val same = key(parserVersion = "pdf-inspector@abc", options = options)
        val newerParser = key(parserVersion = "pdf-inspector@def", options = options)
        val differentOptions = key(parserVersion = "pdf-inspector@abc", options = options.copy(extractImages = true))

        assertEquals(first.digest, same.digest)
        assertNotEquals(first.digest, newerParser.digest)
        assertNotEquals(first.digest, differentOptions.digest)
    }

    @Test
    fun `planner uses only an exact extraction manifest`() {
        val requested = key(parserVersion = "pdf-inspector@abc", options = ParseOptions())
        val cached = ExtractionManifest(
            cacheKey = requested,
            markdownSha256 = "b".repeat(64),
            pageCount = 4,
            hasOcr = false,
            hasLayoutWarnings = false,
            createdAt = Instant.EPOCH,
        )

        val hit = ExtractionPlanner.decide(requested, cached)
        val miss = ExtractionPlanner.decide(
            requested.copy(rendererVersion = "2"),
            cached,
        )

        assertSame(cached, (hit as ExtractionDecision.UseCached).manifest)
        assertEquals(requested.copy(rendererVersion = "2"), (miss as ExtractionDecision.Extract).key)
    }

    private fun key(parserVersion: String, options: ParseOptions) = ExtractionCacheKey(
        pdfSha256 = pdfHash,
        extractionSchema = 1,
        parserVersion = parserVersion,
        parseOptionsHash = ExtractionCacheKey.optionsHash(options),
        rendererVersion = "1",
    )
}
