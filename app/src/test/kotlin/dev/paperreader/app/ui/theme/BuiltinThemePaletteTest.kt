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
        assertEquals(Color(0xFF432DD7), tokens.emptyStateAccent)
    }

    @Test
    fun `dark mode flips neutral roles without recoloring theme accents`() {
        val light = neobrutalismThemeTokens(dark = false)
        val dark = neobrutalismThemeTokens(dark = true)

        assertEquals(light.primary, dark.primary)
        assertEquals(light.secondary, dark.secondary)
        assertEquals(light.primaryContainer, dark.primaryContainer)
        assertEquals(light.secondaryContainer, dark.secondaryContainer)
        assertEquals(light.selection, dark.selection)
        assertEquals(light.emptyStateAccent, dark.emptyStateAccent)
        assertEquals(light.surfaceMuted, dark.surfaceMuted)
        assertEquals(light.success, dark.success)
        assertEquals(light.warning, dark.warning)
        assertEquals(light.danger, dark.danger)
        assertEquals(Color.Black, dark.canvas)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color.White, dark.ink)
        assertEquals(Color.White, dark.inkMuted)
        assertEquals(Color.White, dark.border)
    }

    @Test
    fun `only supported built-in presets are selectable`() {
        assertEquals(
            setOf(PaperThemePreset.NEOBRUTALISM),
            PaperThemePreset.entries.toSet(),
        )
    }
}
