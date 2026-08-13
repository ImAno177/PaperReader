package dev.paperreader.app.ui.theme

import dev.paperreader.extensions.api.ThemePalette
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityThemeContrastTest {
    @Test
    fun `host rejects unreadable community palette roles`() {
        val failures = palette(foreground = BLACK, background = BLACK).accessibilityContrastFailures()

        assertTrue(failures.contains("ink/canvas"))
        assertTrue(failures.contains("onPrimary/primary"))
        assertTrue(failures.contains("danger/surface"))
    }

    @Test
    fun `host accepts community palette roles that meet normal-text AA`() {
        assertTrue(palette(foreground = BLACK, background = WHITE).accessibilityContrastFailures().isEmpty())
    }

    private fun palette(foreground: Int, background: Int) = ThemePalette(
        canvas = background,
        surface = background,
        surfaceMuted = background,
        ink = foreground,
        inkMuted = foreground,
        border = foreground,
        primary = background,
        onPrimary = foreground,
        primaryContainer = background,
        onPrimaryContainer = foreground,
        secondary = background,
        onSecondary = foreground,
        secondaryContainer = background,
        onSecondaryContainer = foreground,
        success = foreground,
        warning = foreground,
        danger = foreground,
        emptyStateAccent = foreground,
        selection = foreground,
        hardShadow = foreground,
    )

    private companion object {
        const val BLACK = 0xff000000.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}
