package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private data class ColorThemePalette(
    val primary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val surfaceMuted: Color,
)

/** Built-in color choices that share a calm reading surface, independent from Neobrutalism. */
internal fun colorThemeTokens(preset: PaperThemePreset, dark: Boolean): PaperThemeTokens {
    val palette = when (preset) {
        PaperThemePreset.OCEAN -> ColorThemePalette(
            primary = Color(0xFF075985),
            primaryContainer = Color(0xFFCFFAFE),
            secondary = Color(0xFF1D4ED8),
            secondaryContainer = Color(0xFFDBEAFE),
            surfaceMuted = Color(0xFFEFF8FF),
        )
        PaperThemePreset.FOREST -> ColorThemePalette(
            primary = Color(0xFF166534),
            primaryContainer = Color(0xFFDCFCE7),
            secondary = Color(0xFF047857),
            secondaryContainer = Color(0xFFD1FAE5),
            surfaceMuted = Color(0xFFF0FDF4),
        )
        PaperThemePreset.VIOLET -> ColorThemePalette(
            primary = Color(0xFF6D28D9),
            primaryContainer = Color(0xFFEDE9FE),
            secondary = Color(0xFF7C3AED),
            secondaryContainer = Color(0xFFF3E8FF),
            surfaceMuted = Color(0xFFFAF7FF),
        )
        PaperThemePreset.ROSE -> ColorThemePalette(
            primary = Color(0xFF9F1239),
            primaryContainer = Color(0xFFFFE4E6),
            secondary = Color(0xFFBE123C),
            secondaryContainer = Color(0xFFFFE4E6),
            surfaceMuted = Color(0xFFFFF7F8),
        )
        PaperThemePreset.SUNSET -> ColorThemePalette(
            primary = Color(0xFFC2410C),
            primaryContainer = Color(0xFFFFEDD5),
            secondary = Color(0xFFB45309),
            secondaryContainer = Color(0xFFFEF3C7),
            surfaceMuted = Color(0xFFFFFAF5),
        )
        PaperThemePreset.NEOBRUTALISM -> error("Neobrutalism uses its original token pack")
    }
    val light = PaperThemeTokens(
        canvas = Color(0xFFFFFDF8),
        surface = Color.White,
        surfaceMuted = palette.surfaceMuted,
        ink = Color(0xFF171717),
        inkMuted = Color(0xFF525252),
        border = Color(0xFF737373),
        primary = palette.primary,
        onPrimary = Color.White,
        primaryContainer = palette.primaryContainer,
        onPrimaryContainer = Color(0xFF171717),
        secondary = palette.secondary,
        onSecondary = Color.White,
        secondaryContainer = palette.secondaryContainer,
        onSecondaryContainer = Color(0xFF171717),
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = NeoViolet,
        selection = palette.primaryContainer,
        hardShadow = Color(0xFF171717),
        cornerRadius = 12.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF171717),
            surface = Color(0xFF242424),
            surfaceMuted = Color(0xFF303030),
            ink = Color(0xFFF5F5F5),
            inkMuted = Color(0xFFC7C7C7),
            border = Color(0xFFB8B8B8),
            hardShadow = Color.Black,
        )
    } else {
        light
    }
}
