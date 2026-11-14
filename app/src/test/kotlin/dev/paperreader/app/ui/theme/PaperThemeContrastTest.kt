package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import dev.paperreader.app.ui.components.statusBadgeOutline
import java.io.File
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
    fun `component outlines meet WCAG non-text contrast on cards and canvas`() {
        PaperThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val tokens = paperThemeTokens(preset, dark)
                listOf("surface" to tokens.surface, "canvas" to tokens.canvas).forEach { (name, background) ->
                    val ratio = contrastRatio(tokens.border, background)
                    assertTrue(
                        "$preset ${if (dark) "dark" else "light"} border on $name contrast was $ratio",
                        ratio >= WCAG_AA_NON_TEXT,
                    )
                }
            }
        }
    }

    @Test
    fun `accent foregrounds stay readable as text and icons`() {
        PaperThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val tokens = paperThemeTokens(preset, dark)
                listOf(
                    "primary" to tokens.primaryForeground,
                    "secondary" to tokens.secondaryForeground,
                ).forEach { (role, foreground) ->
                    listOf(tokens.canvas, tokens.surface).forEach { background ->
                        val ratio = contrastRatio(foreground, background)
                        assertTrue(
                            "$preset ${if (dark) "dark" else "light"} $role foreground contrast was $ratio",
                            ratio >= WCAG_AA_NORMAL_TEXT,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `empty-state heading keeps its accent contrast in both modes`() {
        listOf(false, true).forEach { dark ->
            PaperThemePreset.entries.forEach { preset ->
                val tokens = paperThemeTokens(preset, dark)
                assertTrue(
                    contrastRatio(tokens.emptyStateAccent, tokens.canvas) >= WCAG_AA_LARGE_TEXT,
                )
            }
        }
    }

    @Test
    fun `neobrutalism status colors meet WCAG AA on both paper surfaces`() {
        listOf(false, true).forEach { dark ->
            val tokens = neobrutalismThemeTokens(dark)
            listOf(
                "success on canvas" to (tokens.success to tokens.canvas),
                "success on surface" to (tokens.success to tokens.surface),
                "warning on canvas" to (tokens.warning to tokens.canvas),
                "warning on surface" to (tokens.warning to tokens.surface),
                "danger on canvas" to (tokens.danger to tokens.canvas),
                "danger on surface" to (tokens.danger to tokens.surface),
            ).forEach { (role, colors) ->
                assertTrue(
                    "$role contrast was ${contrastRatio(colors.first, colors.second)}",
                    contrastRatio(colors.first, colors.second) >= WCAG_AA_NORMAL_TEXT,
                )
            }
        }
    }

    @Test
    fun `neobrutalism reader colors meet WCAG AA in light and dark resources`() {
        listOf("values", "values-night").forEach { folder ->
            val colors = readerColors(File("src/main/res/$folder/colors.xml"))
            listOf(
                "ink" to "canvas",
                "ink" to "surface",
                "ink_muted" to "surface",
                "ink_muted" to "surface_muted",
                "primary" to "canvas",
                "primary" to "surface",
                "on_primary" to "primary",
                "ink" to "selection",
            ).forEach { (foreground, background) ->
                val ratio = contrastRatio(colors.getValue(foreground), colors.getValue(background))
                assertTrue("$folder reader $foreground on $background contrast was $ratio", ratio >= WCAG_AA_NORMAL_TEXT)
            }
            val outline = contrastRatio(colors.getValue("border"), colors.getValue("surface"))
            assertTrue("$folder reader border contrast was $outline", outline >= WCAG_AA_NON_TEXT)
        }
    }

    private fun readerColors(file: File): Map<String, Color> =
        Regex("""<color name="reader_neo_([a-z_]+)">#([0-9A-Fa-f]{6})</color>""")
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to Color(0xFF000000 or match.groupValues[2].toLong(16)) }

    @Test
    fun `status badges outline with ink only on heavy-border themes`() {
        listOf(false, true).forEach { dark ->
            val neobrutalism = paperThemeTokens(PaperThemePreset.NEOBRUTALISM, dark)
            assertEquals(neobrutalism.border, neobrutalism.statusBadgeOutline(neobrutalism.primary))
            val ocean = paperThemeTokens(PaperThemePreset.OCEAN, dark)
            assertEquals(ocean.primary.copy(alpha = 0.70f), ocean.statusBadgeOutline(ocean.primary))
        }
    }

    @Test
    fun `material primary stays readable as text and outlines`() {
        PaperThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val tokens = paperThemeTokens(preset, dark)
                val scheme = tokens.materialScheme(dark)
                assertEquals(tokens.primaryForeground, scheme.primary)
                assertTrue(contrastRatio(scheme.primary, scheme.surface) >= WCAG_AA_NORMAL_TEXT)
                assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= WCAG_AA_NORMAL_TEXT)
            }
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
        const val WCAG_AA_NON_TEXT = 3.0
        const val WCAG_AA_LARGE_TEXT = 3.0
    }
}
