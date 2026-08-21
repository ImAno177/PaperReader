package dev.paperreader.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPaperCardsTest {
    @Test
    fun `grid title keeps only the leading clause before a colon`() {
        assertEquals("CGP-Tuning", compactLibraryGridTitle("CGP-Tuning: Structure-aware optimization"))
        assertEquals("Attention Is All You Need", compactLibraryGridTitle("Attention Is All You Need"))
        assertEquals(": malformed", compactLibraryGridTitle(": malformed"))
    }
}
