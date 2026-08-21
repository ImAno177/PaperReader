package dev.paperreader.app.reader

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadablePaperRenderingTest {
    private val workId = WorkId("work")
    private val manifestationId = ManifestationId("manifestation")
    private val sha256 = "a".repeat(64)
    private val now = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun `text zoom steps are bounded and reversible at both edges`() {
        assertEquals(200, nextReadableTextZoom(190, increase = true))
        assertEquals(190, nextReadableTextZoom(200, increase = false))
        assertEquals(85, nextReadableTextZoom(85, increase = false))
        assertEquals(100, nextReadableTextZoom(85, increase = true))
    }

    @Test
    fun `section navigation uses the verified document anchor instead of text search`() {
        val script = readableSectionNavigationScript("S3.SS2")

        assertTrue(script.contains("document.getElementById('S3.SS2')"))
        assertTrue(script.contains("scrollIntoView"))
        assertFalse(script.contains("https://"))
    }

    @Test
    fun `selection capture is bounded to one sanitized document block`() {
        val script = readableSelectionCaptureScript()

        assertTrue(script.contains("window.getSelection()"))
        assertTrue(script.contains("[data-paperreader-block-id]"))
        assertTrue(script.contains("startBlock !== endBlock"))
        assertTrue(script.contains("exact.length > 2000"))
        assertTrue(script.contains("full.slice(start, end) !== exact"))
        assertFalse(script.contains("https://"))
        assertFalse(script.contains("fetch("))
    }

    @Test
    fun `annotation renderer emits only structural anchors and never note or quote content`() {
        val annotation = annotation(
            id = "ann-safe",
            note = "'); fetch('https://tracker.invalid'); //",
            quote = "User-selected <script>alert(1)</script>",
        )

        val script = readableAnnotationRenderScript(listOf(annotation))

        assertTrue(script.contains("id:'ann-safe'"))
        assertTrue(script.contains("blockId:'prx-b00012'"))
        assertTrue(script.contains("start:4,end:17"))
        assertTrue(script.contains(".paperreader-highlight"))
        assertTrue(script.contains("https://appassets.androidplatform.net/annotation/"))
        assertTrue(script.contains("hasNote:true"))
        assertFalse(script.contains(annotation.note!!))
        assertFalse(script.contains(annotation.quoteExact))
        assertFalse(script.contains("tracker.invalid"))
    }

    @Test
    fun `annotation renderer drops unsafe identifiers and navigation rejects them`() {
        val unsafe = annotation(id = "ann-'unsafe", note = null, quote = "vulnerability")

        val script = readableAnnotationRenderScript(listOf(unsafe))

        assertFalse(script.contains("ann-'unsafe"))
        assertTrue(script.contains("const annotations = [];"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            readableAnnotationNavigationScript("ann-'unsafe")
        }
    }

    @Test
    fun `note-only annotation updates do not trigger a structural rerender`() {
        val original = annotation(id = "ann-safe", note = "First note", quote = "vulnerability")
        val noteOnly = original.copy(note = "Revised note", updatedAt = now.plusSeconds(10))
        val moved = original.copy(startOffset = 5, endOffset = 18)

        assertTrue(listOf(original).hasSameRenderedAnchors(listOf(noteOnly)))
        assertFalse(listOf(original).hasSameRenderedAnchors(listOf(moved)))
        assertFalse(listOf(original).hasSameRenderedAnchors(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `section navigation rejects an anchor that can change the local URL`() {
        readableSectionNavigationScript("section#https://tracker.invalid")
    }

    @Test
    fun `renderer is single column local only and supports wide scientific blocks`() {
        val html = renderReadablePaperHtml(
            sanitizedBodyHtml = "<article><h1>Paper</h1><math><mi>x</mi></math><table><tr><td>1</td></tr></table></article>",
            palette = palette(),
            dark = false,
        )

        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("img-src data:"))
        assertTrue(html.contains("width: min(100%, 48rem)"))
        assertTrue(html.contains(".paperreader-table-scroll"))
        assertTrue(html.contains("overflow-x: auto"))
        assertTrue(html.contains("background: var(--surface)"))
        assertFalse(html.contains("background: white"))
        assertTrue(html.contains("@media (max-width: 520px)"))
        assertTrue(html.contains("font-size: 18px"))
        assertTrue(html.contains("--reader-line-height: 1.68"))
        assertTrue(html.contains("--reader-side-margin: 20px"))
        assertTrue(html.contains("<math><mi>x</mi></math>"))
        assertFalse(html.contains("<script", ignoreCase = true))
    }

    @Test
    fun `renderer keeps author metadata in two columns and hides footnote implementation payloads`() {
        val html = renderReadablePaperHtml(
            sanitizedBodyHtml = """
                <article><div class="ltx_authors"><span class="ltx_creator paperreader-author">
                  <span class="ltx_personname paperreader-author-name">Noam Shazeer</span>
                  <span class="ltx_author_notes paperreader-author-details">Google Brain</span>
                  <span class="ltx_role_footnotemark"><sup class="ltx_note_mark">1</sup><span class="ltx_note_outer">footnotemark: 1</span></span>
                </span></div></article>
            """.trimIndent(),
            palette = palette(),
            dark = false,
        )

        assertTrue(html.contains(".ltx_creator, .paperreader-author"))
        assertTrue(html.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"))
        assertTrue(html.contains(".ltx_author_notes, .paperreader-author-details"))
        assertTrue(html.contains(".ltx_role_footnotemark .ltx_note_outer"))
        assertTrue(html.contains("display: block"))
    }

    @Test
    fun `renderer rewrites only bounded bibliography links to the app-owned citation route`() {
        val source = """<p><a href = '#bib.bib12'>[12]</a> <a href="#bib.bib13">[13]</a> <a href="#S3.SS2">section</a> <a href="https://example.org">web</a></p>"""

        val rewritten = rewriteBibliographyLinks(source)

        assertTrue(rewritten.contains("href='paperreader-citation://anchor/bib.bib12'"))
        assertTrue(rewritten.contains("href=\"paperreader-citation://anchor/bib.bib13\""))
        assertTrue(rewritten.contains("href=\"#S3.SS2\""))
        assertTrue(rewritten.contains("href=\"https://example.org\""))
    }

    @Test
    fun `renderer applies only bounded readable layout presets`() {
        val html = renderReadablePaperHtml(
            sanitizedBodyHtml = "<article><p>Paper</p></article>",
            palette = palette(),
            dark = false,
            layout = ReadablePaperLayout(
                spacing = ReadableTextSpacing.RELAXED,
                sideMargin = ReadableSideMargin.WIDE,
            ),
        )

        assertTrue(html.contains("--reader-line-height: 1.85"))
        assertTrue(html.contains("--reader-paragraph-margin: 1.18em"))
        assertTrue(html.contains("--reader-side-margin: 28px"))
        assertEquals(ReadableTextSpacing.COMFORTABLE, ReadableTextSpacing.fromStorageKey("invalid"))
        assertEquals(ReadableSideMargin.COMFORTABLE, ReadableSideMargin.fromStorageKey(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `renderer rejects a color value that could escape css`() {
        renderReadablePaperHtml(
            sanitizedBodyHtml = "<article>Paper</article>",
            palette = palette().copy(link = "red; background:url(https://tracker.invalid)"),
            dark = false,
        )
    }

    @Test
    fun `reflow progress restores only the exact manifestation and sanitized document`() {
        val existing = ReadingState(
            workId = workId,
            manifestationId = manifestationId,
            locator = ReadingLocator(documentSha256 = sha256.uppercase(), pageIndex = 8, progression = 0.62),
            status = ReadingStatus.UNREAD,
            updatedAt = Instant.EPOCH,
        )

        val opened = readableStateForOpen(existing, workId, manifestationId, sha256, now)
        val moved = readableStateForProgress(opened, workId, manifestationId, sha256, 0.71, now)

        assertNull(opened.locator.pageIndex)
        assertEquals(0.62, restorableReadableProgress(opened, manifestationId, sha256)!!, 0.0)
        assertEquals(0.71, moved.locator.progression, 0.0)
        assertEquals(ReadingStatus.READING, moved.status)
        assertNull(restorableReadableProgress(opened, ManifestationId("other"), sha256))
        assertNull(restorableReadableProgress(opened, manifestationId, "b".repeat(64)))
    }

    private fun palette() = ReadablePaperPalette(
        background = "#FFFFFF",
        surface = "#F4F4F4",
        text = "#111111",
        mutedText = "#555555",
        border = "#222222",
        link = "#0044AA",
        selection = "#FFEE99",
    )

    private fun annotation(
        id: String,
        note: String?,
        quote: String,
    ) = Annotation(
        id = id,
        workId = workId,
        documentSha256 = sha256,
        blockId = "prx-b00012",
        startOffset = 4,
        endOffset = 17,
        quotePrefix = "The ",
        quoteExact = quote,
        quoteSuffix = " model",
        pageIndex = null,
        note = note,
        color = "highlight",
        createdAt = now,
        updatedAt = now,
    )
}
