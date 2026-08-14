package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Built-in Doodle tokens: sky blue, navy ink, soft cards, and hand-drawn accents. */
internal fun doodleThemeTokens(dark: Boolean): PaperThemeTokens = if (dark) {
    PaperThemeTokens(
        canvas = Color(0xFF0F1D26),
        surface = Color(0xFF132633),
        surfaceMuted = Color(0xFF1C3A4B),
        ink = Color(0xFFF8FAFC),
        inkMuted = Color(0xFFCBD5E1),
        border = Color(0xFF7ED8F4),
        primary = DoodleBlue,
        onPrimary = Color(0xFF082233),
        primaryContainer = Color(0xFF1C5067),
        onPrimaryContainer = Color(0xFFD6F1FB),
        secondary = Color(0xFF9CC5E1),
        onSecondary = Color(0xFF112536),
        secondaryContainer = Color(0xFF29465A),
        onSecondaryContainer = Color(0xFFE6F4FA),
        success = Color(0xFF4ADE80),
        warning = Color(0xFFF59E0B),
        danger = Color(0xFFFF7A75),
        emptyStateAccent = DoodleBlue,
        selection = Color(0xFF24566D),
        hardShadow = Color(0xFF071016),
        cornerRadius = 14.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.Cursive,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.DOODLE,
    )
} else {
    PaperThemeTokens(
        canvas = Color(0xFFF7FCFE),
        surface = Color.White,
        surfaceMuted = Color(0xFFE6F4FA),
        ink = Color(0xFF111827),
        inkMuted = Color(0xFF4B5563),
        border = DoodleNavy,
        primary = DoodleBlue,
        onPrimary = Color(0xFF082233),
        primaryContainer = Color(0xFFD6F1FB),
        onPrimaryContainer = Color(0xFF0B2A3A),
        secondary = DoodleNavy,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDDE7EF),
        onSecondaryContainer = Color(0xFF122033),
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = DoodleNavy,
        selection = Color(0xFFBFEAF8),
        hardShadow = Color(0xFFA7C4D2),
        cornerRadius = 14.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.Cursive,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.DOODLE,
    )
}
