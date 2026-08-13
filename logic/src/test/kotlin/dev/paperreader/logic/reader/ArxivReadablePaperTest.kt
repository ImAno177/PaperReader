package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArxivReadablePaperTest {
    @Test
    fun `sanitizer keeps semantic paper content and removes executable markup`() = runTest {
        val sanitizer = ArxivHtmlSanitizer()
        val result = sanitizer.sanitize(
            rawHtml = fixtureHtml(),
            sourceUrl = SOURCE_URL,
            fetchAsset = { requestUrl, _ ->
                assertEquals("https://arxiv.org/html/2501.04510v2/figure.png", requestUrl)
                ReadableRemoteResult.Success(ReadableRemoteResource(byteArrayOf(1, 2, 3), "image/png"))
            },
        ) ?: error("Expected readable HTML")

        val document = Jsoup.parseBodyFragment(result.bodyHtml, SOURCE_URL)
        assertTrue(document.select("nav.ltx_TOC").isEmpty())
        assertEquals(listOf(ReadablePaperSection("S1", "Introduction", 1)), result.sections)
        assertTrue(document.select("details.paperreader-author-notes > summary").isNotEmpty())
        assertFalse(result.bodyHtml.contains("Thanks:"))
        assertEquals("License: arXiv.org perpetual non-exclusive license", result.sourceLicense)
        assertTrue(document.select("article.ltx_document section#S1").isNotEmpty())
        assertTrue(document.select("math annotation[encoding=application/x-tex]").isNotEmpty())
        assertTrue(document.select("table th[scope=col]").isNotEmpty())
        assertTrue(document.select("img[src^=data:image/png;base64,]").isNotEmpty())
        assertEquals("Architecture overview", document.selectFirst("img")?.attr("alt"))
        assertTrue(document.select("script,style,svg,iframe,form").isEmpty())
        assertTrue(document.select("a[href^=javascript]").isEmpty())
        assertFalse(result.bodyHtml.contains("href=\"http:"))
        assertFalse(result.bodyHtml.contains("onerror", ignoreCase = true))
        assertFalse(result.bodyHtml.contains("https://tracker.invalid", ignoreCase = true))
        assertFalse(result.bodyHtml.contains("\\raisebox{0.1pt}"))
        assertTrue(result.bodyHtml.contains("\\raisebox{1em}{unknown}"))
        assertTrue(document.body().text().contains("Step 10⃝"))
        assertTrue(result.warnings.contains(ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED))
    }

    @Test
    fun `loader publishes a verified cache that reopens without network`() = runTest {
        val directory = Files.createTempDirectory("readable-paper-cache")
        var calls = 0
        val firstLoader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { request ->
                calls += 1
                if (request.accept.startsWith("text/html")) {
                    ReadableRemoteResult.Success(
                        ReadableRemoteResource(fixtureHtml().toByteArray(), "text/html; charset=utf-8"),
                    )
                } else {
                    ReadableRemoteResult.Success(ReadableRemoteResource(byteArrayOf(4, 5, 6), "image/png"))
                }
            },
            cache = ReadablePaperCache(directory),
            now = { Instant.parse("2026-08-12T00:00:00Z") },
        )

        val first = firstLoader.load("CGP-Tuning", manifestation()) as ReadablePaperResult.Ready
        assertFalse(first.document.servedFromCache)
        assertEquals(2, calls)
        assertEquals("v2", first.document.sourceVersion)
        assertTrue(first.document.documentSha256.matches(Regex("[0-9a-f]{64}")))

        val offlineLoader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { ReadableRemoteResult.Unavailable },
            cache = ReadablePaperCache(directory),
        )
        val cached = offlineLoader.load("CGP-Tuning", manifestation()) as ReadablePaperResult.Ready
        assertTrue(cached.document.servedFromCache)
        assertEquals(first.document.bodyHtml, cached.document.bodyHtml)
        assertEquals(first.document.documentSha256, cached.document.documentSha256)
        assertEquals(first.document.sections, cached.document.sections)
    }

    @Test
    fun `loader refuses an arxiv record without an exact manifestation version`() = runTest {
        val loader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { error("Network must not be used") },
            cache = ReadablePaperCache(Files.createTempDirectory("readable-paper-unversioned")),
        )

        val result = loader.load(
            "Unversioned",
            manifestation().copy(sourceRecordId = "2501.04510", version = null),
        )

        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.UNVERSIONED_SOURCE),
            result,
        )
    }

    @Test
    fun `loader still returns verified content when the offline cache cannot be written`() = runTest {
        val unavailableCachePath = Files.createTempFile("readable-paper-cache", ".blocked")
        val loader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { request ->
                if (request.accept.startsWith("text/html")) {
                    ReadableRemoteResult.Success(
                        ReadableRemoteResource(fixtureHtml().toByteArray(), "text/html; charset=utf-8"),
                    )
                } else {
                    ReadableRemoteResult.Success(ReadableRemoteResource(byteArrayOf(4, 5, 6), "image/png"))
                }
            },
            cache = ReadablePaperCache(unavailableCachePath),
        )

        val result = loader.load("CGP-Tuning", manifestation())

        assertTrue(result is ReadablePaperResult.Ready)
        assertFalse((result as ReadablePaperResult.Ready).document.servedFromCache)
    }

    @Test
    fun `cache evicts the least recently used complete document within a total byte budget`() {
        val directory = Files.createTempDirectory("readable-paper-budget")
        val cache = ReadablePaperCache(
            directory = directory,
            maximumCachedBodyBytes = 1_024,
            maximumTotalCacheBytes = 1_800,
        )
        val firstKey = "1".repeat(64)
        val secondKey = "2".repeat(64)
        val stalePartial = Files.write(directory.resolve(".interrupted.body.part"), ByteArray(512))
        cache.write(firstKey, cachedRecord("first"))
        val old = FileTime.fromMillis(1)
        Files.setLastModifiedTime(directory.resolve("$firstKey.body.html"), old)
        Files.setLastModifiedTime(directory.resolve("$firstKey.manifest"), old)

        cache.write(secondKey, cachedRecord("second"))

        assertEquals(null, cache.read(firstKey))
        assertEquals("second", cache.read(secondKey)?.sourceLicense)
        assertFalse(Files.exists(stalePartial))
        val cacheBytes = Files.newDirectoryStream(directory).use { paths ->
            paths.filter(Files::isRegularFile).sumOf(Files::size)
        }
        assertTrue(cacheBytes <= 1_800)
    }

    @Test
    fun `sanitizer does not present a short error page as a paper`() = runTest {
        val result = ArxivHtmlSanitizer().sanitize(
            rawHtml = "<html><body><article class='ltx_document'>Not a paper.</article></body></html>",
            sourceUrl = SOURCE_URL,
            fetchAsset = { _, _ -> error("No assets expected") },
        )

        assertEquals(null, result)
    }

    private fun manifestation() = PaperManifestation(
        id = ManifestationId("manifestation-1"),
        workId = WorkId("work-1"),
        type = ManifestationType.PREPRINT,
        sourceProvider = "arxiv",
        sourceRecordId = "2501.04510v2",
        version = "v2",
        landingPageUrl = "https://arxiv.org/abs/2501.04510v2",
        pdfUrl = "https://arxiv.org/pdf/2501.04510v2",
        license = "https://arxiv.org/licenses/nonexclusive-distrib/1.0/",
        updatedAt = Instant.parse("2025-07-21T00:00:00Z"),
    )

    private fun cachedRecord(label: String): CachedReadablePaper {
        val body = "<article>${label.padEnd(700, 'x')}</article>"
        return CachedReadablePaper(
            bodyHtml = body,
            sourceUrl = "https://arxiv.org/html/$label",
            sourceSha256 = "a".repeat(64),
            documentSha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) },
            retrievedAt = Instant.parse("2026-08-12T00:00:00Z"),
            sourceLicense = label,
            sections = emptyList(),
            warnings = emptySet(),
        )
    }

    private fun fixtureHtml(): String {
        val paragraph = "A mobile paper reader must preserve semantic structure, mathematical notation, citations, tables, figures, and a stable reading order. "
            .repeat(4)
        return """
            <!doctype html>
            <html><head>
              <script src="https://tracker.invalid/run.js"></script>
              <style>body { background-image: url(https://tracker.invalid/pixel); }</style>
            </head><body>
              <nav class="ltx_TOC"><ol><li class="ltx_tocentry_section"><a href="#S1">Introduction</a></li></ol></nav>
              <a id="license-tr" href="https://info.arxiv.org/help/license/">License: arXiv.org perpetual non-exclusive license</a>
              <article class="ltx_document">
                <h1>CGP-Tuning</h1>
                <div class="ltx_authors"><span class="ltx_creator">A. Author<span class="ltx_author_notes"><span class="ltx_contact_name">Thanks: </span>Supported by a public grant.</span></span></div>
                <section id="S1"><h2>Introduction</h2><p>$paragraph Step \raisebox{0.1pt}{\scriptsize10}⃝ is preserved, while \raisebox{1em}{unknown} remains literal.</p></section>
                <figure><img src="2501.04510v2/figure.png" alt="Refer to caption" onerror="steal()"><figcaption>Architecture overview</figcaption></figure>
                <p><a href="javascript:alert(1)">bad</a> <a href="http://insecure.invalid">insecure</a> <a href="https://example.org/reference">reference</a></p>
                <math display="block" alttext="x squared"><semantics><msup><mi>x</mi><mn>2</mn></msup><annotation encoding="application/x-tex">x^2</annotation></semantics></math>
                <table><thead><tr><th scope="col">Model</th></tr></thead><tbody><tr><td>CGP</td></tr></tbody></table>
                <svg onload="steal()"><script>steal()</script></svg>
              </article>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val SOURCE_URL = "https://arxiv.org/html/2501.04510v2"
    }
}
