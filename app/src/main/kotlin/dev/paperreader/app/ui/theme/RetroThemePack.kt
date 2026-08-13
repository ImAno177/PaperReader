package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Immutable built-in token spec for the vintage Retro visual pack. */
internal fun retroThemeTokens(dark: Boolean): PaperThemeTokens = if (dark) {
    PaperThemeTokens(
        canvas = Color(0xFF1A1410),
        surface = Color(0xFF261D17),
        surfaceMuted = Color(0xFF3A2B20),
        ink = Color(0xFFFFF4DE),
        inkMuted = Color(0xFFD7BFA5),
        border = Color(0xFFF0BE7A),
        primary = Color(0xFF8DC6E8),
        onPrimary = Color(0xFF0D2737),
        primaryContainer = Color(0xFF244D62),
        onPrimaryContainer = Color(0xFFDDF3FF),
        secondary = Color(0xFFF29A72),
        onSecondary = Color(0xFF3A1307),
        secondaryContainer = Color(0xFF6D321F),
        onSecondaryContainer = Color(0xFFFFE4D7),
        success = Color(0xFF72D69B),
        warning = Color(0xFFFFC76B),
        danger = Color(0xFFFF8A80),
        emptyStateAccent = Color(0xFFF2B56B),
        selection = Color(0xFF4C3E2F),
        hardShadow = Color(0xFF080604),
        cornerRadius = 4.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.Serif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.RETRO_GRID,
    )
} else {
    PaperThemeTokens(
        canvas = Color(0xFFFFF7E6),
        surface = Color(0xFFFFFCF2),
        surfaceMuted = Color(0xFFF8D69A),
        ink = Color(0xFF2C1B12),
        inkMuted = Color(0xFF6F5443),
        border = Color(0xFF5B2F20),
        primary = RetroBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEAF0),
        onPrimaryContainer = Color(0xFF12384E),
        secondary = RetroBrick,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF7D4C4),
        onSecondaryContainer = Color(0xFF5B2414),
        success = BuiltinSuccess,
        warning = BuiltinWarning,
        danger = BuiltinDanger,
        emptyStateAccent = Color(0xFF8F3D18),
        selection = Color(0xFFC7DFEA),
        hardShadow = Color(0xFFD19A3A),
        cornerRadius = 4.dp,
        borderWidth = 1.dp,
        shadowOffset = 0.dp,
        titleFont = FontFamily.Serif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.Monospace,
        decoration = PaperDecoration.RETRO_GRID,
    )
}
