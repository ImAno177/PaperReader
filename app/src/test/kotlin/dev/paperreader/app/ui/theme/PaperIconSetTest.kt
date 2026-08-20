package dev.paperreader.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaperIconSetTest {
    @Test
    fun `built-in theme uses Material Symbols`() {
        assertEquals(
            PaperIconFamily.MATERIAL_SYMBOLS,
            paperIconSet(PaperThemePreset.NEOBRUTALISM).family,
        )
    }

    @Test
    fun `every semantic icon has a resource in every family`() {
        requireCompletePaperIconSets()
        val iconSet = PaperIconSet(PaperIconFamily.MATERIAL_SYMBOLS)
        PaperIconKey.entries.forEach { key ->
            assertNotEquals("Missing Material Symbols resource for $key", 0, iconSet.resource(key))
        }
    }

    @Test
    fun `community icon family rejects an incomplete semantic set`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaperIconSet.community(mapOf(PaperIconKey.SEARCH to "M0 0Z"))
        }
    }
}
