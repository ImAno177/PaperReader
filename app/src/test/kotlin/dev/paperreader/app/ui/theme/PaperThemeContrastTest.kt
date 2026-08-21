package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperThemeContrastTest {
    @Test
    fun `every text role meets WCAG AA in light and dark palettes`() {
        PaperThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val tokens = paperThemeTokens(preset, dark)
                val pairs = listOf(
                    "ink on canvas" to (tokens.ink to tokens.canvas),
                    "ink on surface" to (tokens.ink to tokens.surface),
                    "muted ink on canvas" to (tokens.inkMuted to tokens.canvas),
                    "muted ink on surface" to (tokens.inkMuted to tokens.surface),
                    "on-primary" to (tokens.onPrimary to tokens.primary),
                    "on-secondary" to (tokens.onSecondary to tokens.secondary),
                    "on-primary-container" to (tokens.onPrimaryContainer to tokens.primaryContainer),
                    "on-secondary-container" to (tokens.onSecondaryContainer to tokens.secondaryContainer),
                )

                pairs.forEach { (role, colors) ->
                    val ratio = contrastRatio(colors.first, colors.second)
                    assertTrue(
                        "$preset ${if (dark) "dark" else "light"} $role contrast was $ratio",
                        ratio >= WCAG_AA_NORMAL_TEXT,
                    )
                }
            }
        }
    }

    @Test
    fun `empty-state heading keeps its violet identity in both modes`() {
        listOf(false, true).forEach { dark ->
            PaperThemePreset.entries.forEach { preset ->
                val tokens = paperThemeTokens(preset, dark)
                assertEquals(NeoViolet, tokens.emptyStateAccent)
                assertTrue(
                    contrastRatio(tokens.emptyStateAccent, tokens.canvas) >= WCAG_AA_LARGE_TEXT,
                )
            }
        }
    }

    @Test
    fun `material semantic primary preserves the theme accent contract`() {
        listOf(false, true).forEach { dark ->
            val tokens = neobrutalismThemeTokens(dark)
            val scheme = tokens.materialScheme(dark)
            assertEquals(tokens.primary, scheme.primary)
            assertEquals(tokens.onPrimary, scheme.onPrimary)
        }
    }

    @Test
    fun `theme mode resolves an explicit palette without changing system default`() {
        assertEquals(false, PaperThemeMode.SYSTEM.resolveDarkTheme(false))
        assertEquals(true, PaperThemeMode.SYSTEM.resolveDarkTheme(true))
        assertEquals(false, PaperThemeMode.LIGHT.resolveDarkTheme(true))
        assertEquals(true, PaperThemeMode.DARK.resolveDarkTheme(false))
        assertEquals(PaperThemeMode.SYSTEM, PaperThemeMode.fromStorageKey("unknown"))
    }

    @Test
    fun `system bar icon appearance follows the resolved background`() {
        assertEquals(
            SystemBarAppearance.LIGHT_BACKGROUND,
            resolveSystemBarAppearance(PaperThemeMode.SYSTEM, systemDark = false),
        )
        assertEquals(
            SystemBarAppearance.DARK_BACKGROUND,
            resolveSystemBarAppearance(PaperThemeMode.SYSTEM, systemDark = true),
        )
        assertEquals(
            SystemBarAppearance.LIGHT_BACKGROUND,
            resolveSystemBarAppearance(PaperThemeMode.LIGHT, systemDark = true),
        )
        assertEquals(
            SystemBarAppearance.DARK_BACKGROUND,
            resolveSystemBarAppearance(PaperThemeMode.DARK, systemDark = false),
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = first.relativeLuminance()
        val secondLuminance = second.relativeLuminance()
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun Color.relativeLuminance(): Double =
        0.2126 * red.linearized() + 0.7152 * green.linearized() + 0.0722 * blue.linearized()

    private fun Float.linearized(): Double {
        val channel = toDouble()
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        const val WCAG_AA_NORMAL_TEXT = 4.5
        const val WCAG_AA_LARGE_TEXT = 3.0
    }
}
