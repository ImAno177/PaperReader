package dev.paperreader.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPresentationTest {
    private val entries = listOf(
        PaperSettingEntry("library", "Library", "Layout and collection organization"),
        PaperSettingEntry("data", "Data and backup", "Metadata backup and restore"),
        PaperSettingEntry("about", "About", "A mobile reader for scholarly papers."),
    )

    @Test
    fun `query matching only a hidden subtitle returns the row that owns it`() {
        val matches = filterPaperSettings(entries, "restore")

        assertEquals(listOf("data"), matches.map(PaperSettingEntry::key))
    }

    @Test
    fun `query matching no title or subtitle returns nothing`() {
        assertEquals(emptyList<PaperSettingEntry>(), filterPaperSettings(entries, "telemetry"))
    }

    @Test
    fun `blank query returns every row`() {
        assertEquals(entries, filterPaperSettings(entries, "   "))
    }
}
