package dev.paperreader.logic.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadingBookmarkTest {
    private val workId = WorkId("work")
    private val manifestationId = ManifestationId("manifestation")

    @Test
    fun `bookmark identity is deterministic and canonicalizes document hash`() {
        val lower = readingBookmarkId(workId, manifestationId, "a".repeat(64), 7)
        val upper = readingBookmarkId(workId, manifestationId, "A".repeat(64), 7)

        assertEquals(lower, upper)
        assertNotEquals(lower, readingBookmarkId(workId, manifestationId, "a".repeat(64), 8))
        assertNotEquals(lower, readingBookmarkId(workId, ManifestationId("other"), "a".repeat(64), 7))
    }

    @Test
    fun `bookmark rejects invalid anchors`() {
        assertThrows(IllegalArgumentException::class.java) {
            readingBookmarkId(workId, manifestationId, "invalid", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readingBookmarkId(workId, manifestationId, "a".repeat(64), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadingBookmark(
                id = readingBookmarkId(workId, manifestationId, "a".repeat(64), 0),
                workId = workId,
                manifestationId = manifestationId,
                documentSha256 = "A".repeat(64),
                pageIndex = 0,
                createdAt = Instant.EPOCH,
            )
        }
    }
}
