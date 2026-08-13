package dev.paperreader.logic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperCollectionTest {
    @Test
    fun `collection names are trimmed and internal whitespace is collapsed`() {
        assertEquals("Systematic Reviews", normalizeCollectionName("  Systematic\n  Reviews  "))
    }

    @Test
    fun `blank and overlong collection names are rejected`() {
        assertNull(normalizeCollectionName(" \t "))
        assertNull(normalizeCollectionName("x".repeat(MAX_COLLECTION_NAME_LENGTH + 1)))
    }
}
