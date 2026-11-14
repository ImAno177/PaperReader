package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R

internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFFFF4DD),
        surface = Color(0xFFFFFDF7),
        surfaceMuted = Color(0xFFF1E3C4),
        ink = Color(0xFF26201A),
        inkMuted = Color(0xFF5C5347),
        border = Color(0xFF26201A),
        primary = Color(0xFFFF5C2B),
        onPrimary = Color(0xFF26201A),
        primaryContainer = Color(0xFFFFD84D),
        onPrimaryContainer = Color(0xFF26201A),
        secondary = Color(0xFFA5DCFF),
        onSecondary = Color(0xFF26201A),
        secondaryContainer = Color(0xFFFFF0BD),
        onSecondaryContainer = Color(0xFF26201A),
        success = Color(0xFF2F6B1F),
        warning = Color(0xFF7A4100),
        danger = Color(0xFFB0141C),
        emptyStateAccent = Color(0xFF26201A),
        selection = Color(0xFFFFD84D),
        hardShadow = Color(0xFF26201A),
        cornerRadius = 12.dp,
        borderWidth = 2.dp,
        shadowOffset = 0.dp,
        titleFont = DmSans,
        bodyFont = DmSans,
        labelFont = DmSans,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF15110C),
            surface = Color(0xFF241E18),
            surfaceMuted = Color(0xFF41392E),
            ink = Color(0xFFF1E6D6),
            inkMuted = Color(0xFFBBAFA0),
            border = Color(0xFFBBAFA0),
            primary = Color(0xFFF65B25),
            onPrimary = Color(0xFF22110C),
            primaryContainer = Color(0xFFD8A438),
            onPrimaryContainer = Color(0xFF241803),
            secondary = Color(0xFF5FACD3),
            onSecondary = Color(0xFF071822),
            secondaryContainer = Color(0xFF3F2909),
            onSecondaryContainer = Color(0xFFF1E6D6),
            success = Color(0xFF7FD46A),
            warning = Color(0xFFE8B84A),
            danger = Color(0xFFFF7A6B),
            emptyStateAccent = Color(0xFFF1E6D6),
            selection = Color(0xFF6B4E14),
            hardShadow = Color(0xFF000000),
        )
    } else {
        light
    }
}

@OptIn(ExperimentalTextApi::class)
private val TextOpticalSize = FontVariation.Setting("opsz", 14f)

@OptIn(ExperimentalTextApi::class)
private val DmSans = FontFamily(
    Font(
        R.font.dm_sans,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), TextOpticalSize),
    ),
    Font(
        R.font.dm_sans,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), TextOpticalSize),
    ),
    Font(
        R.font.dm_sans,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), TextOpticalSize),
    ),
)
