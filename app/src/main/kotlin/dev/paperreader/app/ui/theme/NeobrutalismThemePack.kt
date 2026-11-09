package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Built-in Neobrutalism tokens with a paper canvas and cobalt actions. */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFF4EBDD),
        surface = Color(0xFFFFFCF5),
        surfaceMuted = Color(0xFFE9E0D3),
        ink = Color(0xFF1E1A17),
        inkMuted = Color(0xFF5E5750),
        border = Color(0xFF2C261F),
        primary = Color(0xFF2159A6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF8E5A6),
        onPrimaryContainer = Color(0xFF1E1A17),
        secondary = Color(0xFF356B75),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E9EA),
        onSecondaryContainer = Color(0xFF17393D),
        success = Color(0xFF28704C),
        warning = Color(0xFF925500),
        danger = Color(0xFFB42318),
        emptyStateAccent = Color(0xFF2159A6),
        selection = Color(0xFFF6D56F),
        hardShadow = Color(0xFF1E1A17),
        cornerRadius = 8.dp,
        borderWidth = 1.dp,
        shadowOffset = 1.dp,
        titleFont = FontFamily.Serif,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.SansSerif,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF151210),
            surface = Color(0xFF211C17),
            surfaceMuted = Color(0xFF2C251D),
            ink = Color(0xFFFFF7E7),
            inkMuted = Color(0xFFD0C4B4),
            border = Color(0xFFEBDDC6),
            primary = Color(0xFF82AEFF),
            onPrimary = Color(0xFF0E1B31),
            primaryContainer = Color(0xFF5A4614),
            onPrimaryContainer = Color(0xFFFFE9A8),
            secondary = Color(0xFF63B5C2),
            onSecondary = Color(0xFF12262A),
            secondaryContainer = Color(0xFF153F46),
            onSecondaryContainer = Color(0xFFC6F0F3),
            success = Color(0xFF8BD5A5),
            warning = Color(0xFFF0B25D),
            danger = Color(0xFFFF9187),
            emptyStateAccent = Color(0xFF9FC2FF),
            selection = Color(0xFF6B541E),
            hardShadow = Color(0xFF060A11),
        )
    } else {
        light
    }
}
