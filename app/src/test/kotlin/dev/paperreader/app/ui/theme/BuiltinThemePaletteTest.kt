package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinThemePaletteTest {
    @Test
    fun `neobrutalism uses the restrained sun palette and geometry`() {
        val tokens = neobrutalismThemeTokens(dark = false)

        assertEquals(Color(0xFFFFD84D), tokens.primary)
        assertEquals(Color(0xFF0099FF), tokens.secondary)
        assertEquals(Color(0xFFFFD84D), tokens.warning)
        assertEquals(Color(0xFFFFF4DD), tokens.canvas)
        assertEquals(Color.White, tokens.surface)
        assertEquals(Color.Black, tokens.ink)
        assertEquals(5.dp, tokens.cornerRadius)
        assertEquals(2.dp, tokens.borderWidth)
        assertEquals(1.dp, tokens.shadowOffset)
        assertEquals(PaperDecoration.NONE, tokens.decoration)
        assertEquals(Color(0xFF7357FF), tokens.emptyStateAccent)
    }

    @Test
    fun `dark mode inverts neutral roles without recoloring accents`() {
        val light = neobrutalismThemeTokens(dark = false)
        val dark = neobrutalismThemeTokens(dark = true)

        assertEquals(light.primary, dark.primary)
        assertEquals(light.secondary, dark.secondary)
        assertEquals(light.primaryContainer, dark.primaryContainer)
        assertEquals(light.secondaryContainer, dark.secondaryContainer)
        assertEquals(light.success, dark.success)
        assertEquals(light.warning, dark.warning)
        assertEquals(light.danger, dark.danger)
        assertEquals(light.selection, dark.selection)
        assertEquals(light.emptyStateAccent, dark.emptyStateAccent)
        assertEquals(Color.Black, dark.canvas)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color.Black, dark.surfaceMuted)
        assertEquals(Color.White, dark.ink)
        assertEquals(Color(0xFFB8B8B8), dark.inkMuted)
        assertEquals(Color.White, dark.border)
        assertEquals(Color.White, dark.hardShadow)
    }

    @Test
    fun `error foreground follows the supplied theme color contrast`() {
        assertEquals(Color.Black, contrastForeground(Color(0xFFFFD84D)))
        assertEquals(Color.White, contrastForeground(Color(0xFF6B1020)))
    }

    @Test
    fun `only supported built-in presets are selectable`() {
        assertEquals(
            setOf(PaperThemePreset.NEOBRUTALISM),
            PaperThemePreset.entries.toSet(),
        )
    }
}
