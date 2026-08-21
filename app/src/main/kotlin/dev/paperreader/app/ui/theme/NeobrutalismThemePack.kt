package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Built-in Neobrutalism tokens with a warm paper canvas and restrained accents. */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFFFF4DD),
        surface = Color.White,
        surfaceMuted = Color(0xFFFFF4DD),
        ink = Color.Black,
        inkMuted = Color(0xFF525252),
        border = Color.Black,
        primary = NeoAmber,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFFFFF0BD),
        onPrimaryContainer = Color.Black,
        secondary = NeoBlue,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFFDCEBFE),
        onSecondaryContainer = Color.Black,
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = NeoViolet,
        selection = NeoAmber,
        hardShadow = Color.Black,
        cornerRadius = 5.dp,
        borderWidth = 2.dp,
        shadowOffset = 1.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color.Black,
            surface = Color.Black,
            surfaceMuted = Color.Black,
            ink = Color.White,
            inkMuted = Color(0xFFB8B8B8),
            border = Color.White,
            hardShadow = Color.White,
        )
    } else {
        light
    }
}
