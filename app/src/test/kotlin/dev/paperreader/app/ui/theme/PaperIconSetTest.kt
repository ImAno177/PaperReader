package dev.paperreader.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaperIconSetTest {
    @Test
    fun `doodle and retro use Tabler icons`() {
        assertEquals(PaperIconFamily.TABLER, paperIconSet(PaperThemePreset.DOODLE).family)
        assertEquals(PaperIconFamily.TABLER, paperIconSet(PaperThemePreset.RETRO).family)
    }

    @Test
    fun `non illustrative themes use Material Symbols`() {
        assertEquals(
            PaperIconFamily.MATERIAL_SYMBOLS,
            paperIconSet(PaperThemePreset.NEOBRUTALISM).family,
        )
    }

    @Test
    fun `every semantic icon has a resource in every family`() {
        requireCompletePaperIconSets()
        listOf(PaperIconFamily.TABLER, PaperIconFamily.MATERIAL_SYMBOLS).forEach { family ->
            val iconSet = PaperIconSet(family)
            PaperIconKey.entries.forEach { key ->
                assertNotEquals("Missing $family resource for $key", 0, iconSet.resource(key))
            }
        }
    }

    @Test
    fun `community icon family rejects an incomplete semantic set`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaperIconSet.community(mapOf(PaperIconKey.SEARCH to "M0 0Z"))
        }
    }
}
