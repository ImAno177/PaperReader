package dev.paperreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadablePaperAnnotationControllerTest {
    @Test
    fun labelNormalizesWhitespaceCapsLengthAndPreviewsNotes() {
        val quote = "word\n\n" + "x".repeat(100)

        assertEquals("word " + "x".repeat(67), readableAnnotationLabel(quote, null))
        assertEquals("quoted", readableAnnotationLabel("quoted", "   "))
        assertEquals("quoted\nnote preview", readableAnnotationLabel("quoted", "note\npreview"))
    }
}
