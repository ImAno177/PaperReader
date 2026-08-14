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
        assertEquals(1, document.select("div.paperreader-table-scroll > table").size)
        assertTrue(document.select("img[src^=data:image/png;base64,]").isNotEmpty())
        assertEquals("Architecture overview", document.selectFirst("img")?.attr("alt"))
        assertEquals(5, document.select("span.paperreader-figure-unavailable[role=img]").size)
        assertEquals(
            "Attention patterns for difficult examples",
            document.selectFirst("span.paperreader-figure-unavailable")?.attr("aria-label"),
        )
        assertTrue(result.warnings.contains(ReadablePaperWarning.FIGURE_UNAVAILABLE))
        assertTrue(document.select("script,style,svg,object,iframe,form").isEmpty())
        assertTrue(document.select("a[href^=javascript]").isEmpty())
        assertFalse(result.bodyHtml.contains("href=\"http:"))
        assertFalse(result.bodyHtml.contains("onerror", ignoreCase = true))
        assertFalse(result.bodyHtml.contains("https://tracker.invalid", ignoreCase = true))
        assertFalse(result.bodyHtml.contains("\\raisebox{0.1pt}"))
        assertTrue(result.bodyHtml.contains("\\raisebox{1em}{unknown}"))
        assertTrue(document.body().text().contains("Step 10⃝"))
        assertTrue(result.warnings.contains(ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED))
        val blockIds = document.select("[data-paperreader-block-id]").map { it.attr("data-paperreader-block-id") }
        assertTrue(blockIds.isNotEmpty())
        assertEquals(blockIds.size, blockIds.distinct().size)
        assertTrue(blockIds.all { it.matches(Regex("prx-b[0-9]{5}")) })
    }

    @Test
    fun `sanitizer makes real arxiv author metadata readable on a narrow screen`() = runTest {
        val result = ArxivHtmlSanitizer().sanitize(
            rawHtml = authorLayoutFixtureHtml(),
            sourceUrl = SOURCE_URL,
            fetchAsset = { _, _ -> error("No assets expected") },
        ) ?: error("Expected readable HTML")
        val document = Jsoup.parseBodyFragment(result.bodyHtml, SOURCE_URL)

        assertEquals(1, document.select(".paperreader-author").size)
        assertTrue(document.select(".paperreader-author-details .ltx_contact").size >= 2)
        assertTrue(document.text().contains("Noam Shazeer"))
        assertTrue(document.text().contains("Google Brain"))
        assertTrue(document.text().contains("noam@example.org"))
        assertTrue(document.text().contains("Body footnote remains"))
        assertEquals("1", document.selectFirst(".ltx_role_footnotemark .ltx_note_mark")?.text())
        assertTrue(document.select(".ltx_role_footnotemark .ltx_note_outer").isEmpty())
        assertFalse(document.text().contains("footnotemark:"))
        assertFalse(result.bodyHtml.contains("footnotemark:"))
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
    fun `attention paper loads its exact v7 html with sections equations and bibliography`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val loader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { request ->
                requestedUrls += request.url
                ReadableRemoteResult.Success(
                    ReadableRemoteResource(attentionFixtureHtml().toByteArray(), "text/html; charset=utf-8"),
                )
            },
            cache = ReadablePaperCache(Files.createTempDirectory("attention-readable-paper")),
        )

        val result = loader.load("Attention Is All You Need", attentionManifestation()) as ReadablePaperResult.Ready
        val document = Jsoup.parseBodyFragment(result.document.bodyHtml, result.document.sourceUrl)

        assertEquals(listOf("https://arxiv.org/html/1706.03762v7"), requestedUrls)
        assertEquals("v7", result.document.sourceVersion)
        assertTrue(
            result.document.sections.toString(),
            result.document.sections.any { it.title.contains("Model Architecture") },
        )
        assertTrue(document.select("math annotation[encoding=application/x-tex]").isNotEmpty())
        assertTrue(document.select("section.ltx_bibliography li#bib-bib1").isNotEmpty())
        assertEquals("#bib-bib1", document.selectFirst("a.ltx_ref")?.attr("href"))
        assertTrue(result.document.bodyHtml.length > 1_000)
    }

    @Test
    fun `sanitizer policy v9 bypasses a document cached under v7`() = runTest {
        val directory = Files.createTempDirectory("readable-paper-policy")
        val cache = ReadablePaperCache(directory)
        val v7Key = ReadablePaperCache.keyFor(
            sourceUrl = SOURCE_URL,
            sanitizerPolicyVersion = "arxiv-html-sanitizer-7",
            rendererContractVersion = "mobile-html-5",
        )
        cache.write(v7Key, cachedRecord("stale-v7"))
        var calls = 0
        val loader = ArxivReadablePaperLoader(
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
            cache = cache,
        )

        val result = loader.load("CGP-Tuning", manifestation()) as ReadablePaperResult.Ready

        assertFalse(result.document.servedFromCache)
        assertEquals(2, calls)
        assertTrue(result.document.bodyHtml.contains("paperreader-figure-unavailable"))
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
    fun `loader canonicalizes prefixed IDs and keeps an explicitly versioned ID`() = runTest {
        val requested = mutableListOf<String>()
        val loader = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { request ->
                requested += request.url
                if (request.accept.startsWith("text/html")) {
                    ReadableRemoteResult.Success(
                        ReadableRemoteResource(fixtureHtml().toByteArray(), "text/html"),
                    )
                } else {
                    ReadableRemoteResult.Success(ReadableRemoteResource(byteArrayOf(1), "image/png"))
                }
            },
            cache = ReadablePaperCache(Files.createTempDirectory("readable-paper-id-normalization")),
        )

        val prefixed = loader.load(
            "Paper",
            manifestation().copy(sourceProvider = "ARXIV", sourceRecordId = "arXiv:2501.04510", version = "v3"),
        ) as ReadablePaperResult.Ready
        assertEquals("v3", prefixed.document.sourceVersion)
        assertEquals("https://arxiv.org/html/2501.04510v3", requested.first())

        requested.clear()
        val explicit = loader.load(
            "Paper",
            manifestation().copy(sourceRecordId = "2501.04510v4", version = null),
        ) as ReadablePaperResult.Ready
        assertEquals("v4", explicit.document.sourceVersion)
        assertEquals("https://arxiv.org/html/2501.04510v4", requested.first())
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

    @Test
    fun `loader maps source and transport failures without attempting unsafe requests`() = runTest {
        val directory = Files.createTempDirectory("readable-paper-failures")
        val unsupported = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { error("must not fetch") },
            cache = ReadablePaperCache(directory),
        ).load("Title", manifestation().copy(sourceProvider = "crossref"))
        assertEquals(ReadablePaperFailure.UNSUPPORTED_SOURCE, (unsupported as ReadablePaperResult.Unavailable).reason)

        val outcomes = listOf(
            ReadableRemoteResult.NotFound to ReadablePaperFailure.SOURCE_NOT_FOUND,
            ReadableRemoteResult.RateLimited(2_000) to ReadablePaperFailure.RATE_LIMITED,
            ReadableRemoteResult.Unavailable to ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE,
            ReadableRemoteResult.TooLarge to ReadablePaperFailure.RESPONSE_TOO_LARGE,
            ReadableRemoteResult.Invalid to ReadablePaperFailure.INVALID_RESPONSE,
        )
        outcomes.forEachIndexed { index, (remote, expected) ->
            val result = ArxivReadablePaperLoader(
                fetcher = ReadableResourceFetcher { remote },
                cache = ReadablePaperCache(directory.resolve("failure-$index")),
            ).load("Title", manifestation()) as ReadablePaperResult.Unavailable
            assertEquals(expected, result.reason)
            if (remote is ReadableRemoteResult.RateLimited) assertEquals(2_000L, result.retryAfterMillis)
        }
    }

    @Test
    fun `loader rejects non-html responses and malformed versions`() = runTest {
        val directory = Files.createTempDirectory("readable-paper-media")
        val nonHtml = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher {
                ReadableRemoteResult.Success(ReadableRemoteResource("pdf".toByteArray(), "application/pdf"))
            },
            cache = ReadablePaperCache(directory.resolve("non-html")),
        ).load("Title", manifestation())
        assertEquals(ReadablePaperFailure.INVALID_RESPONSE, (nonHtml as ReadablePaperResult.Unavailable).reason)

        val malformed = manifestation().copy(sourceRecordId = "2501.04510", version = "bad")
        val result = ArxivReadablePaperLoader(
            fetcher = ReadableResourceFetcher { error("must not fetch") },
            cache = ReadablePaperCache(directory.resolve("malformed-version")),
        ).load("Title", malformed)
        assertEquals(ReadablePaperFailure.UNVERSIONED_SOURCE, (result as ReadablePaperResult.Unavailable).reason)
    }

    @Test
    fun `sanitizer fails closed for invalid origins missing articles and short content`() = runTest {
        val sanitizer = ArxivHtmlSanitizer()
        val valid = longArticleHtml()
        listOf(
            "http://arxiv.org/html/2501.04510v2",
            "https://evil.example/html/2501.04510v2",
            "https://arxiv.org/html/2501.04510v2#fragment",
        ).forEach { url ->
            assertEquals(null, sanitizer.sanitize(valid, url) { _, _ -> error("no asset") })
        }
        assertEquals(
            null,
            sanitizer.sanitize("<article class='other'>${"text ".repeat(100)}</article>", SOURCE_URL) { _, _ ->
                error("no asset")
            },
        )
        assertEquals(
            null,
            sanitizer.sanitize("<article class='ltx_document'>short</article>", SOURCE_URL) { _, _ ->
                error("no asset")
            },
        )
    }

    @Test
    fun `sanitizer handles unsafe figures limits captions and link policies`() = runTest {
        val html = longArticleHtml(
            body = """
                <nav class="ltx_TOC"><ul>
                  <li class="ltx_tocentry_subsection"><a href="#S1">Subsection</a></li>
                  <li class="ltx_tocentry_subsubsection"><a href="#S1">Duplicate</a></li>
                  <li><a href="#bad'anchor">Unsafe</a></li>
                  <li><a href="#">Blank</a></li>
                </ul></nav>
                <p><a href="#S1">local</a>
                  <a href="https://arxiv.org/html/2501.04510v2#S1">same</a>
                  <a href="https://example.org/out">external</a>
                  <a href="mailto:paper@example.org">mail</a>
                  <a href="http://insecure.example">http</a>
                  <a href="https://user:pass@example.org/private">userinfo</a>
                  <a href="%%%">malformed</a></p>
                <section id="S1"><h2>Section</h2><p>${"content ".repeat(60)}</p></section>
                <figure><img src="https://evil.example/figure.png" alt="" /></figure>
                <figure><img src="2501.04510v2/bad.bmp" alt="[uncaptioned image]" /></figure>
                <figure><img src="2501.04510v2/empty.png" alt="Figure 3" /></figure>
                <figure><img src="2501.04510v2/good.png" alt="Refer to caption" srcset="bad" /><figcaption>Good caption</figcaption></figure>
                <figure><img src="2501.04510v2/limited.png" alt="Figure 5" /></figure>
            """.trimIndent(),
        )
        val result = ArxivHtmlSanitizer(
            maximumFigureCount = 1,
            maximumFigureBytes = 1,
            maximumSingleFigureBytes = 4,
        ).sanitize(
            rawHtml = html,
            sourceUrl = SOURCE_URL,
            fetchAsset = { url, _ ->
                when {
                    url.endsWith("bad.bmp") -> ReadableRemoteResult.Success(
                        ReadableRemoteResource(byteArrayOf(1), "image/bmp"),
                    )
                    url.endsWith("empty.png") -> ReadableRemoteResult.Success(
                        ReadableRemoteResource(byteArrayOf(), "image/png"),
                    )
                    url.endsWith("good.png") -> ReadableRemoteResult.Success(
                        ReadableRemoteResource(byteArrayOf(7), "image/png"),
                    )
                    else -> ReadableRemoteResult.Unavailable
                }
            },
        ) ?: error("Expected readable document")

        val document = Jsoup.parseBodyFragment(result.bodyHtml, SOURCE_URL)
        assertTrue(result.warnings.contains(ReadablePaperWarning.FIGURE_LIMIT_REACHED))
        assertTrue(result.warnings.contains(ReadablePaperWarning.FIGURE_UNAVAILABLE))
        assertEquals(1, document.select("img").size)
        assertEquals("Good caption", document.selectFirst("img")?.attr("alt"))
        assertTrue(document.select("a[href='#S1']").size >= 2)
        assertTrue(document.select("a[rel*='external']").isNotEmpty())
        assertTrue(document.select("a[href^='mailto:']").isNotEmpty())
        assertTrue(document.select("a[href^='http:']").isEmpty())
        assertTrue(document.select("a[href*='user:pass']").isEmpty())
        assertTrue(document.select("a[href='%']").isEmpty())
        assertEquals(1, result.sections.size)
        assertEquals(2, result.sections.single().level)
    }

    @Test
    fun `sanitizer rejects unsupported executable output and accepts documents without a toc`() = runTest {
        val noToc = longArticleHtml(
            body = "<section><h2>No TOC</h2><p>${"content ".repeat(80)}</p></section>",
        )
        val result = ArxivHtmlSanitizer().sanitize(noToc, SOURCE_URL) { _, _ -> error("no asset") }
            ?: error("Expected readable document")
        assertTrue(result.warnings.contains(ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING))

        val executable = longArticleHtml(
            body = """<p>${"content ".repeat(80)}</p><button onclick="doBadThing()">bad</button>""",
        )
        val cleaned = ArxivHtmlSanitizer().sanitize(executable, SOURCE_URL) { _, _ -> error("no asset") }
            ?: error("Expected sanitizer to keep safe paper text")
        assertTrue(Jsoup.parseBodyFragment(cleaned.bodyHtml).select("button").isEmpty())
    }

    private fun longArticleHtml(body: String = "<p>${"content ".repeat(80)}</p>"): String =
        "<html><body><article class='ltx_document'><h1>Title</h1>$body</article></body></html>"

    private fun authorLayoutFixtureHtml(): String {
        val body = "The mobile reader must preserve author identity, affiliations, links, references, and footnotes without exposing conversion internals. ".repeat(8)
        return """
            <html><body><article class="ltx_document">
              <h1>Attention Is All You Need</h1>
              <div class="ltx_authors">
                <span class="ltx_creator ltx_role_author">
                  <span class="ltx_personname">Noam Shazeer
                    <span class="ltx_note ltx_role_footnotemark"><sup class="ltx_note_mark">1</sup>
                      <span class="ltx_note_outer"><span class="ltx_note_content">
                        <span class="ltx_note_type">footnotemark: </span><span class="ltx_tag">1</span>
                      </span></span>
                    </span>
                  </span>
                  <span class="ltx_author_notes"><span class="ltx_author_notes_content">
                    <span class="ltx_contact ltx_role_affiliation"><span class="ltx_contact_name">Affiliation: </span>Google Brain</span>
                    <span class="ltx_contact ltx_role_email">Email: <a href="mailto:noam@example.org">noam@example.org</a></span>
                  </span></span>
                </span>
              </div>
              <p>$body<span class="ltx_note ltx_role_footnote"><sup class="ltx_note_mark">1</sup>
                <span class="ltx_note_outer"><span class="ltx_note_content">Body footnote remains.</span></span>
              </span></p>
            </article></body></html>
        """.trimIndent()
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

    private fun attentionManifestation() = PaperManifestation(
        id = ManifestationId("attention-v7"),
        workId = WorkId("attention"),
        type = ManifestationType.PREPRINT,
        sourceProvider = "arxiv",
        sourceRecordId = "1706.03762v7",
        version = "v7",
        landingPageUrl = "https://arxiv.org/abs/1706.03762v7",
        pdfUrl = "https://arxiv.org/pdf/1706.03762v7",
        license = "https://arxiv.org/licenses/nonexclusive-distrib/1.0/",
        updatedAt = Instant.parse("2023-08-02T00:00:00Z"),
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
                <figure>
                  <object type="image/svg+xml" data="one.svg"></object>
                  <object type="image/svg+xml" data="two.svg"></object>
                  <object type="image/svg+xml" data="three.svg"></object>
                  <object type="image/svg+xml" data="four.svg"></object>
                  <object type=" IMAGE/SVG+XML ; charset=utf-8 " data="five.svg"></object>
                  <figcaption>Attention patterns for difficult examples</figcaption>
                </figure>
                <p><a href="javascript:alert(1)">bad</a> <a href="http://insecure.invalid">insecure</a> <a href="https://example.org/reference">reference</a></p>
                <math display="block" alttext="x squared"><semantics><msup><mi>x</mi><mn>2</mn></msup><annotation encoding="application/x-tex">x^2</annotation></semantics></math>
                <table><thead><tr><th scope="col">Model</th></tr></thead><tbody><tr><td>CGP</td></tr></tbody></table>
                <svg onload="steal()"><script>steal()</script></svg>
              </article>
            </body></html>
        """.trimIndent()
    }

    private fun attentionFixtureHtml(): String {
        val body = "The Transformer follows an encoder-decoder architecture using stacked self-attention and point-wise, fully connected layers. "
            .repeat(12)
        return """
            <!doctype html><html><body>
              <nav class="ltx_TOC"><ol><li class="ltx_tocentry_section"><a href="#S3">Model Architecture</a></li></ol></nav>
              <a id="license-tr" href="https://info.arxiv.org/help/license/">License: arXiv.org perpetual non-exclusive license</a>
              <article class="ltx_document">
                <h1 class="ltx_title">Attention Is All You Need</h1>
                <section id="S3" class="ltx_section">
                  <h2 class="ltx_title ltx_title_section">3 Model Architecture</h2>
                  <p>$body <a class="ltx_ref" href="#bib-bib1">[1]</a></p>
                  <table class="ltx_equation"><tbody><tr><td>
                    <math display="block" alttext="Attention(Q,K,V)">
                      <semantics><mrow><mi>Attention</mi><mo>(</mo><mi>Q</mi><mo>,</mo><mi>K</mi><mo>,</mo><mi>V</mi><mo>)</mo></mrow>
                      <annotation encoding="application/x-tex">Attention(Q,K,V)</annotation></semantics>
                    </math>
                  </td></tr></tbody></table>
                </section>
                <section class="ltx_bibliography" id="bib"><h2>References</h2>
                  <ul class="ltx_biblist"><li id="bib-bib1" class="ltx_bibitem">A referenced work.</li></ul>
                </section>
              </article>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val SOURCE_URL = "https://arxiv.org/html/2501.04510v2"
    }
}
