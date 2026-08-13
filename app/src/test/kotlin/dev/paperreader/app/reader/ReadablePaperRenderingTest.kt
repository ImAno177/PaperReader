package dev.paperreader.app.reader

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
    fun `renderer is single column local only and supports wide scientific blocks`() {
        val html = renderReadablePaperHtml(
            sanitizedBodyHtml = "<article><h1>Paper</h1><math><mi>x</mi></math><table><tr><td>1</td></tr></table></article>",
            palette = palette(),
            dark = false,
        )

        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("img-src data:"))
        assertTrue(html.contains("width: min(100%, 48rem)"))
        assertTrue(html.contains("table {"))
        assertTrue(html.contains("overflow-x: auto"))
        assertTrue(html.contains("font-size: 18px"))
        assertTrue(html.contains("<math><mi>x</mi></math>"))
        assertFalse(html.contains("<script", ignoreCase = true))
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
}
