package dev.paperreader.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperReaderApplicationTest {
    @Test
    fun `host startup runs only in the main application process`() {
        assertTrue(isMainApplicationProcess("dev.paperreader.app", "dev.paperreader.app"))
        assertFalse(
            isMainApplicationProcess(
                "dev.paperreader.app:androidx.pdf.service.PdfDocumentServiceImpl",
                "dev.paperreader.app",
            ),
        )
        assertFalse(isMainApplicationProcess("", "dev.paperreader.app"))
    }
}
