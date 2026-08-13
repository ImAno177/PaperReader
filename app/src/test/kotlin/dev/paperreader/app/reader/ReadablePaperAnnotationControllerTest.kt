package dev.paperreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadablePaperAnnotationControllerTest {
    @Test
    fun labelNormalizesWhitespaceAndCapsQuoteLength() {
        val quote = "word\n\n" + "x".repeat(100)

        assertEquals("word " + "x".repeat(67), readableAnnotationLabel(quote, null))
    }

    @Test
    fun labelAddsNoteMarkerOnlyForNonBlankNotes() {
        assertEquals("quoted", readableAnnotationLabel("quoted", "   "))
        assertEquals("quoted — note", readableAnnotationLabel("quoted", "note") { "$it — note" })
    }
}
