package dev.paperreader.app.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableReaderNavigationTest {
    @Test
    fun `only bibliography anchors offer a return to reading`() {
        assertTrue(isBibliographyAnchor("bib.bib13"))
        assertTrue(isBibliographyAnchor("BIB.bib7"))
        assertFalse(isBibliographyAnchor("S3.SS2"))
        assertFalse(isBibliographyAnchor("bib.bad/path"))
        assertFalse(isBibliographyAnchor("bib."))
        assertFalse(isBibliographyAnchor(null))
    }

    @Test
    fun `citation target must stay inside the local renderer`() {
        assertTrue(bibliographyAnchorFromCitationTarget("paperreader-citation://anchor/bib.bib12") == "bib.bib12")
        assertTrue(bibliographyAnchorFromCitationTarget("paperreader-citation://anchor/S3.SS2") == null)
        assertTrue(bibliographyAnchorFromCitationTarget("paperreader-citation://anchor/bib.bad/path") == null)
        assertTrue(bibliographyAnchorFromCitationTarget("https://example.org/document.html#bib.bib12") == null)
        assertTrue(bibliographyAnchorFromCitationTarget("javascript:alert(1)#bib.bib12") == null)
        assertTrue(
            annotationIdFromReaderTarget("https://appassets.androidplatform.net/annotation/ann-safe") == "ann-safe",
        )
        assertTrue(annotationIdFromReaderTarget("https://example.org/annotation/ann-safe") == null)
        assertTrue(annotationIdFromReaderTarget("https://appassets.androidplatform.net/annotation/ann-safe?q=1") == null)
    }
}
