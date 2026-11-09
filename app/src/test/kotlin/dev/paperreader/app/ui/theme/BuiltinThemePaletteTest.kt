package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinThemePaletteTest {
    @Test
    fun `error foreground follows the supplied theme color contrast`() {
        assertEquals(Color.Black, contrastForeground(Color(0xFFFFD84D)))
        assertEquals(Color.White, contrastForeground(Color(0xFF6B1020)))
    }

    @Test
    fun `only supported built-in presets are selectable`() {
        assertEquals(
            setOf(
                PaperThemePreset.NEOBRUTALISM,
                PaperThemePreset.OCEAN,
                PaperThemePreset.FOREST,
                PaperThemePreset.VIOLET,
                PaperThemePreset.ROSE,
                PaperThemePreset.SUNSET,
            ),
            PaperThemePreset.entries.toSet(),
        )
    }
}
