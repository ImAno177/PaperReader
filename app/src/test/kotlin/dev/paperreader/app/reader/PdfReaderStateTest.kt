package dev.paperreader.app.reader

import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
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

class PdfReaderStateTest {
    private val workId = WorkId("work")
    private val manifestationId = ManifestationId("manifestation")
    private val sha256 = "a".repeat(64)
    private val now = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun `visible page maps to bounded zero-based locator and progress`() {
        assertEquals(ReaderPosition(0, 0.1), calculateReaderPosition(-2, 10))
        assertEquals(ReaderPosition(4, 0.5), calculateReaderPosition(4, 10))
        assertEquals(ReaderPosition(9, 1.0), calculateReaderPosition(20, 10))
        assertNull(calculateReaderPosition(0, 0))
    }

    @Test
    fun `dominant visible page wins over a barely visible earlier page`() {
        val position = calculateDominantReaderPosition(
            firstVisiblePage = 0,
            pageCount = 4,
            viewportWidth = 1_080,
            viewportHeight = 2_012,
            pageLocations = listOf(
                ReaderPageLocation(0, 0f, 0f, 1_080f, 270f),
                ReaderPageLocation(1, 0f, 307f, 1_080f, 1_704f),
                ReaderPageLocation(2, 0f, 1_741f, 1_080f, 3_138f),
            ),
        )

        assertEquals(ReaderPosition(1, 0.5), position)
    }

    @Test
    fun `dominant page calculation ignores malformed or offscreen geometry`() {
        assertEquals(
            ReaderPosition(2, 0.75),
            calculateDominantReaderPosition(
                firstVisiblePage = 2,
                pageCount = 4,
                viewportWidth = 1_080,
                viewportHeight = 2_012,
                pageLocations = listOf(
                    ReaderPageLocation(0, Float.NaN, 0f, 1_080f, 2_012f),
                    ReaderPageLocation(1, 0f, -3_000f, 1_080f, -2_000f),
                    ReaderPageLocation(8, 0f, 0f, 1_080f, 2_012f),
                ),
            ),
        )
    }

    @Test
    fun `initial state never replaces an observed dominant page with a visible sliver`() {
        val observed = ReaderPosition(pageIndex = 1, progression = 0.5)

        assertEquals(
            observed,
            selectInitialReaderPosition(
                observedPosition = observed,
                firstVisiblePage = 0,
                pageCount = 4,
            ),
        )
        assertEquals(
            ReaderPosition(pageIndex = 0, progression = 0.25),
            selectInitialReaderPosition(
                observedPosition = null,
                firstVisiblePage = 0,
                pageCount = 4,
            ),
        )
    }

    @Test
    fun `jump page input accepts only a nonblank in-range one-based page`() {
        assertEquals(0, readerPageIndexFromInput("1", 10))
        assertEquals(9, readerPageIndexFromInput(" 10 ", 10))
        assertNull(readerPageIndexFromInput("", 10))
        assertNull(readerPageIndexFromInput("0", 10))
        assertNull(readerPageIndexFromInput("-1", 10))
        assertNull(readerPageIndexFromInput("11", 10))
        assertNull(readerPageIndexFromInput("abc", 10))
        assertNull(readerPageIndexFromInput("1", 0))
    }

    @Test
    fun `open restores only the exact manifestation and document hash`() {
        val existing = ReadingState(
            workId = workId,
            manifestationId = manifestationId,
            locator = ReadingLocator(documentSha256 = sha256.uppercase(), pageIndex = 7, progression = 0.8),
            status = ReadingStatus.UNREAD,
            updatedAt = Instant.EPOCH,
        )

        val opened = readerStateForOpen(existing, workId, manifestationId, sha256, now)

        assertEquals(7, opened.locator.pageIndex)
        assertEquals(ReadingStatus.READING, opened.status)
        assertEquals(7, restorableReaderPage(opened, manifestationId, sha256, 10))
        assertNull(restorableReaderPage(opened, ManifestationId("other"), sha256, 10))
        assertNull(restorableReaderPage(opened, manifestationId, "b".repeat(64), 10))
    }

    @Test
    fun `new document starts a new locator and finished status is never downgraded`() {
        val existing = ReadingState(
            workId = workId,
            manifestationId = ManifestationId("old"),
            locator = ReadingLocator(documentSha256 = "b".repeat(64), pageIndex = 9, progression = 1.0),
            status = ReadingStatus.FINISHED,
            updatedAt = Instant.EPOCH,
        )

        val opened = readerStateForOpen(existing, workId, manifestationId, sha256, now)
        val moved = readerStateForPosition(
            opened,
            workId,
            manifestationId,
            sha256,
            ReaderPosition(2, 0.3),
            now,
        )

        assertNull(opened.locator.pageIndex)
        assertEquals(sha256, opened.locator.documentSha256)
        assertEquals(ReadingStatus.FINISHED, moved.status)
        assertEquals(2, moved.locator.pageIndex)
    }

    @Test
    fun `reader accepts only the app file provider authority`() {
        assertTrue(isTrustedLocalPdfLocation("content", "dev.paperreader.app.files", "dev.paperreader.app.files"))
        assertFalse(isTrustedLocalPdfLocation("file", "dev.paperreader.app.files", "dev.paperreader.app.files"))
        assertFalse(isTrustedLocalPdfLocation("content", "other.files", "dev.paperreader.app.files"))
    }

    @Test
    fun `bookmark helpers expose only pages from the loaded exact document`() {
        val pageNine = bookmark(pageIndex = 9, idDigit = "9")
        val pageTwo = bookmark(pageIndex = 2, idDigit = "2")
        val outOfBounds = bookmark(pageIndex = 10, idDigit = "a")

        assertTrue(isReaderPageBookmarked(listOf(pageNine, pageTwo), 2))
        assertFalse(isReaderPageBookmarked(listOf(pageNine, pageTwo), -1))
        assertFalse(isReaderPageBookmarked(listOf(pageNine, pageTwo), 4))
        assertEquals(listOf(pageTwo, pageNine), boundedReaderBookmarks(listOf(pageNine, outOfBounds, pageTwo), 10))
        assertTrue(boundedReaderBookmarks(listOf(pageTwo), 0).isEmpty())
    }

    @Test
    fun `reader session survives configuration pauses without counting paused time`() {
        val session = ReaderSessionViewModel()

        session.resume(1_000L)
        session.pause(4_000L)
        session.resume(100_000L)
        session.pause(102_500L)

        assertEquals(5_500L, session.drain(minimumMillis = 1_000L)?.toMillis())
        assertNull(session.drain(minimumMillis = 1_000L))
    }

    @Test
    fun `short reader session is discarded and reset`() {
        val session = ReaderSessionViewModel()

        session.resume(10L)
        session.resume(20L)
        session.pause(500L)
        session.pause(800L)

        assertNull(session.drain(minimumMillis = 1_000L))
        session.resume(1_000L)
        session.pause(2_200L)
        assertEquals(1_200L, session.drain(minimumMillis = 1_000L)?.toMillis())
    }

    private fun bookmark(pageIndex: Int, idDigit: String) = ReadingBookmark(
        id = ReadingBookmarkId(idDigit.repeat(64)),
        workId = workId,
        manifestationId = manifestationId,
        documentSha256 = sha256,
        pageIndex = pageIndex,
        createdAt = now,
    )
}
