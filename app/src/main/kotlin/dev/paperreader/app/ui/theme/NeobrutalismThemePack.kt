package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Built-in Neobrutalism tokens: yellow and violet accents, hard outlines, and offset shadows. */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFF1EFE8),
        surface = Color(0xFFFBFBF9),
        surfaceMuted = Color(0xFFFFF1A8),
        ink = Color(0xFF1C293C),
        inkMuted = Color(0xFF526174),
        border = Color(0xFF1C293C),
        primary = NeoMustard,
        onPrimary = Color(0xFF1C293C),
        primaryContainer = Color(0xFFFFE895),
        onPrimaryContainer = Color(0xFF3E3100),
        secondary = NeoViolet,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2DDFF),
        onSecondaryContainer = Color(0xFF1C0F6A),
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = NeoViolet,
        selection = Color(0xFFFFE066),
        hardShadow = Color(0xFF1C293C),
        cornerRadius = 4.dp,
        borderWidth = 2.dp,
        shadowOffset = 3.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color.Black,
            surface = Color.Black,
            surfaceMuted = light.surfaceMuted,
            ink = Color.White,
            inkMuted = Color.White,
            border = Color.White,
            emptyStateAccent = NeoViolet,
            hardShadow = Color.Black,
        )
    } else {
        light
    }
}
