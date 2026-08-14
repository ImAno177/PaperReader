package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Built-in Neobrutalism tokens: yellow and violet accents, hard outlines, and offset shadows. */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens = if (dark) {
    PaperThemeTokens(
        canvas = Color(0xFF121316),
        surface = Color(0xFF1D2129),
        surfaceMuted = Color(0xFF303642),
        ink = Color(0xFFF7F0E0),
        inkMuted = Color(0xFFC7C1B4),
        border = Color(0xFFF7F0E0),
        primary = NeoMustard,
        onPrimary = Color(0xFF241F00),
        primaryContainer = Color(0xFF6A5600),
        onPrimaryContainer = Color(0xFFFFF0A5),
        secondary = Color(0xFF9C8BFF),
        onSecondary = Color(0xFF1E115D),
        secondaryContainer = Color(0xFF403783),
        onSecondaryContainer = Color(0xFFE4DEFF),
        success = Color(0xFF4ADE80),
        warning = Color(0xFFF59E0B),
        danger = Color(0xFFFF7A75),
        emptyStateAccent = NeoMustard,
        selection = Color(0xFF5A4E00),
        hardShadow = Color(0xFF000000),
        cornerRadius = 4.dp,
        borderWidth = 2.dp,
        shadowOffset = 3.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.NONE,
    )
} else {
    PaperThemeTokens(
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
        emptyStateAccent = Color(0xFF6B5300),
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
}
