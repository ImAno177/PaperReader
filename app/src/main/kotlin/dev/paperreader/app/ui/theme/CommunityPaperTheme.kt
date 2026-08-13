package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.paperreader.extensions.api.CommunityTheme
import dev.paperreader.extensions.api.ThemeDecoration
import dev.paperreader.extensions.api.ThemeFontFamily
import dev.paperreader.extensions.api.ThemePalette

data class CommunityPaperTheme(
    val packageName: String,
    val definition: CommunityTheme,
    val iconPaths: Map<PaperIconKey, String>,
) {
    init {
        require(iconPaths.keys == PaperIconKey.entries.toSet())
        listOf(
            "light" to definition.lightPalette,
            "dark" to definition.darkPalette,
        ).forEach { (mode, palette) ->
            val failures = palette.accessibilityContrastFailures()
            require(failures.isEmpty()) {
                "Community theme $mode palette has insufficient contrast: ${failures.joinToString()}"
            }
        }
    }

    val storageKey: String = "community:$packageName:${definition.themeId}"
    val displayName: String = definition.displayName

    fun tokens(dark: Boolean): PaperThemeTokens {
        val palette = if (dark) definition.darkPalette else definition.lightPalette
        return PaperThemeTokens(
            canvas = palette.canvas.color(),
            surface = palette.surface.color(),
            surfaceMuted = palette.surfaceMuted.color(),
            ink = palette.ink.color(),
            inkMuted = palette.inkMuted.color(),
            border = palette.border.color(),
            primary = palette.primary.color(),
            onPrimary = palette.onPrimary.color(),
            primaryContainer = palette.primaryContainer.color(),
            onPrimaryContainer = palette.onPrimaryContainer.color(),
            secondary = palette.secondary.color(),
            onSecondary = palette.onSecondary.color(),
            secondaryContainer = palette.secondaryContainer.color(),
            onSecondaryContainer = palette.onSecondaryContainer.color(),
            success = palette.success.color(),
            warning = palette.warning.color(),
            danger = palette.danger.color(),
            emptyStateAccent = palette.emptyStateAccent.color(),
            selection = palette.selection.color(),
            hardShadow = palette.hardShadow.color(),
            cornerRadius = definition.cornerRadiusDp.dp,
            borderWidth = definition.borderWidthDp.dp,
            shadowOffset = definition.shadowOffsetDp.dp,
            titleFont = definition.titleFont.fontFamily(),
            bodyFont = definition.bodyFont.fontFamily(),
            labelFont = definition.labelFont.fontFamily(),
            decoration = definition.decoration.paperDecoration(),
        )
    }

    fun palette(dark: Boolean): ThemePalette =
        if (dark) definition.darkPalette else definition.lightPalette
}

private fun Int.color(): Color = Color(this)

private fun ThemeFontFamily.fontFamily(): FontFamily = when (this) {
    ThemeFontFamily.SYSTEM_SANS -> FontFamily.SansSerif
    ThemeFontFamily.SYSTEM_SERIF -> FontFamily.Serif
    ThemeFontFamily.SYSTEM_MONOSPACE -> FontFamily.Monospace
}

private fun ThemeDecoration.paperDecoration(): PaperDecoration = when (this) {
    ThemeDecoration.NONE -> PaperDecoration.NONE
    ThemeDecoration.DOODLE -> PaperDecoration.DOODLE
    ThemeDecoration.RETRO_GRID -> PaperDecoration.RETRO_GRID
}
