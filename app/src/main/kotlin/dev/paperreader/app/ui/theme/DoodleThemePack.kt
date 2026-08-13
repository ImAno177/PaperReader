package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Immutable built-in token spec for the illustrative Doodle visual pack. */
internal fun doodleThemeTokens(dark: Boolean): PaperThemeTokens = if (dark) {
    PaperThemeTokens(
        canvas = Color(0xFF0E1B24),
        surface = Color(0xFF132633),
        surfaceMuted = Color(0xFF1C3A4B),
        ink = Color(0xFFEAF8FF),
        inkMuted = Color(0xFFB4D4E1),
        border = Color(0xFF7ED8F4),
        primary = Color(0xFF7DD3FC),
        onPrimary = Color(0xFF08202D),
        primaryContainer = Color(0xFF164B61),
        onPrimaryContainer = Color(0xFFD3F4FF),
        secondary = Color(0xFF9CC5E1),
        onSecondary = Color(0xFF112536),
        secondaryContainer = Color(0xFF29465A),
        onSecondaryContainer = Color(0xFFE0F3FF),
        success = Color(0xFF72D69B),
        warning = Color(0xFFFFC76B),
        danger = Color(0xFFFF8A80),
        emptyStateAccent = Color(0xFF7DD3FC),
        selection = Color(0xFF24566D),
        hardShadow = Color(0xFF071016),
        cornerRadius = 14.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.DOODLE,
    )
} else {
    PaperThemeTokens(
        canvas = Color(0xFFF4FBFE),
        surface = Color.White,
        surfaceMuted = Color(0xFFE6F4FA),
        ink = Color(0xFF172A3A),
        inkMuted = Color(0xFF486277),
        border = DoodleNavy,
        primary = DoodleBlue,
        onPrimary = Color(0xFF082233),
        primaryContainer = Color(0xFFC9EEFC),
        onPrimaryContainer = Color(0xFF0D3548),
        secondary = DoodleNavy,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E5F4),
        onSecondaryContainer = Color(0xFF182D42),
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = Color(0xFF155A7A),
        selection = Color(0xFFA9E3F7),
        hardShadow = Color(0xFFA7C4D2),
        cornerRadius = 14.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.SansSerif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.DOODLE,
    )
}
