package dev.paperreader.app.reader

import dev.paperreader.logic.reader.ReadablePaperDocument
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadablePaperHtmlExportTest {
    @Test
    fun `export keeps sanitized anchors and records immutable provenance`() {
        val document = document(
            title = "Attention <All> You Need / v2",
            bodyHtml = "<article><h1>Paper</h1><p><a href=\"#bib.bib1\">[1]</a></p></article>",
        )

        val html = renderReadablePaperExportHtml(document)

        assertTrue(html.contains("href=\"#bib.bib1\""))
        assertFalse(html.contains("paperreader-citation://"))
        assertTrue(html.contains("name=\"paperreader-source-url\""))
        assertTrue(html.contains("https://arxiv.org/html/1706.03762v2"))
        assertTrue(html.contains("name=\"paperreader-document-sha256\""))
        assertTrue(html.contains("arxiv-html-sanitizer-10"))
        assertTrue(html.contains("mobile-html-7"))
        assertTrue(html.contains("Paper Reader HTML export"))
        assertFalse(html.contains("<script", ignoreCase = true))
    }

    @Test
    fun `export escapes metadata and creates a safe deterministic filename`() {
        val document = document(
            title = "A \"quoted\" paper: <safe>?",
            sourceVersion = "v2/unsafe",
            sourceUrl = "https://arxiv.org/html/1706.03762v2?q=1&x=2",
            license = "CC BY <3",
        )

        val html = renderReadablePaperExportHtml(document)

        assertTrue(html.contains("A &quot;quoted&quot; paper"))
        assertTrue(html.contains("CC BY &lt;3"))
        assertTrue(html.contains("q=1&amp;x=2"))
        assertTrue(readablePaperHtmlFileName(document).matches(Regex("[\\p{L}\\p{N}._-]+-[\\p{L}\\p{N}._-]+\\.html")))
        assertFalse(readablePaperHtmlFileName(document).contains('/'))
        assertFalse(readablePaperHtmlFileName(document).contains('\\'))
    }

    private fun document(
        title: String = "Paper",
        bodyHtml: String = "<article><p>Paper text</p></article>",
        sourceVersion: String = "v2",
        sourceUrl: String = "https://arxiv.org/html/1706.03762v2",
        license: String? = "CC BY 4.0",
    ) = ReadablePaperDocument(
        bodyHtml = bodyHtml,
        title = title,
        sourceUrl = sourceUrl,
        sourceProvider = "arxiv",
        sourceVersion = sourceVersion,
        license = license,
        sourceSha256 = "a".repeat(64),
        documentSha256 = "b".repeat(64),
        retrievedAt = Instant.parse("2026-08-14T00:00:00Z"),
        servedFromCache = false,
        sections = emptyList(),
        warnings = emptySet(),
    )
}
