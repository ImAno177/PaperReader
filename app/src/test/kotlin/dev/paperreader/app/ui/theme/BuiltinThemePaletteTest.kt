package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinThemePaletteTest {
    @Test
    fun `neobrutalism keeps the skill's mustard and violet contract`() {
        val tokens = neobrutalismThemeTokens(dark = false)

        assertEquals(Color(0xFFFDC800), tokens.primary)
        assertEquals(Color(0xFF432DD7), tokens.secondary)
        assertEquals(Color(0xFFFBFBF9), tokens.surface)
        assertEquals(Color(0xFF1C293C), tokens.ink)
        assertEquals(PaperDecoration.NONE, tokens.decoration)
    }

    @Test
    fun `only supported built-in presets are selectable`() {
        assertEquals(
            setOf(PaperThemePreset.NEOBRUTALISM),
            PaperThemePreset.entries.toSet(),
        )
    }
}
