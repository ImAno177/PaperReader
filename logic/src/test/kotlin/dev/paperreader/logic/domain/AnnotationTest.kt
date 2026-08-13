package dev.paperreader.logic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnnotationTest {
    private val selection = AnnotationSelection(
        documentSha256 = "a".repeat(64),
        blockId = "prx-b00012",
        startOffset = 4,
        endOffset = 17,
        quotePrefix = "The ",
        quoteExact = "vulnerability",
        quoteSuffix = " model",
    )

    @Test
    fun `annotation identity is deterministic for one exact document anchor`() {
        val first = annotationId(WorkId("work"), selection)

        assertEquals(first, annotationId(WorkId("work"), selection))
        assertNotEquals(first, annotationId(WorkId("other"), selection))
        assertNotEquals(first, annotationId(WorkId("work"), selection.copy(startOffset = 5, endOffset = 18)))
    }

    @Test
    fun `selection rejects noncanonical or ambiguous anchors`() {
        assertThrows(IllegalArgumentException::class.java) {
            selection.copy(documentSha256 = "A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selection.copy(blockId = "unsafe'block")
        }
        assertThrows(IllegalArgumentException::class.java) {
            selection.copy(endOffset = 18)
        }
    }
}
