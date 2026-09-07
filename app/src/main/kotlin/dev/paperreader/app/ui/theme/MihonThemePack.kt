package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** A calm Material 3 reading palette inspired by Mihon's restrained surfaces. */
internal fun mihonThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFFFFBFE),
        surface = Color(0xFFFFFBFE),
        surfaceMuted = Color(0xFFF3EDF7),
        ink = Color(0xFF1D1B20),
        inkMuted = Color(0xFF49454F),
        border = Color(0xFF79747E),
        primary = Color(0xFF6750A4),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8DEF8),
        onSecondaryContainer = Color(0xFF1D192B),
        success = Color(0xFF386A20),
        warning = Color(0xFF765B00),
        danger = Color(0xFFBA1A1A),
        emptyStateAccent = NeoViolet,
        selection = Color(0xFFEADDFF),
        hardShadow = Color(0xFF1D1B20),
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
            canvas = Color(0xFF141218),
            surface = Color(0xFF1D1B20),
            surfaceMuted = Color(0xFF211F26),
            ink = Color(0xFFE6E1E5),
            inkMuted = Color(0xFFCAC4D0),
            border = Color(0xFF938F99),
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC),
            onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
            success = Color(0xFF9CD67D),
            warning = Color(0xFFE6C25D),
            danger = Color(0xFFFFB4AB),
            emptyStateAccent = NeoViolet,
            selection = Color(0xFF4F378B),
            hardShadow = Color.Black,
        )
    } else {
        light
    }
}
