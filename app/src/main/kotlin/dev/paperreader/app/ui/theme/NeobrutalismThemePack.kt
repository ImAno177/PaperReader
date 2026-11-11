package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R

/**
 * Built-in Neobrutalism tokens: black ink, 2 dp black outlines, and opaque 4 dp offset shadows on
 * flat, saturated fills. Violet carries actions and reads as text; highlighter yellow marks
 * selection, the way a reader marks a page.
 */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFEEEEFF),
        surface = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFDDDDF3),
        ink = Color(0xFF0A0A0A),
        inkMuted = Color(0xFF3D3D52),
        border = Color(0xFF000000),
        primary = Color(0xFF5B2EFF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE14D),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFFC2136E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFC6E4),
        onSecondaryContainer = Color(0xFF000000),
        success = Color(0xFF0A7A3A),
        warning = Color(0xFF8F5200),
        danger = Color(0xFFC8102E),
        emptyStateAccent = Color(0xFF5B2EFF),
        selection = Color(0xFFFFE14D),
        hardShadow = Color(0xFF000000),
        cornerRadius = 4.dp,
        borderWidth = 2.dp,
        shadowOffset = 4.dp,
        titleFont = SpaceGrotesk,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.SansSerif,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF13131C),
            surface = Color(0xFF22222F),
            surfaceMuted = Color(0xFF30303F),
            ink = Color(0xFFFFFFFF),
            inkMuted = Color(0xFFCACADB),
            border = Color(0xFFEDEDF5),
            primary = Color(0xFF9478FF),
            onPrimary = Color(0xFF000000),
            primaryContainer = Color(0xFFFFE14D),
            onPrimaryContainer = Color(0xFF000000),
            secondary = Color(0xFFFF6CB6),
            onSecondary = Color(0xFF000000),
            secondaryContainer = Color(0xFF5C1A3F),
            onSecondaryContainer = Color(0xFFFFD6EA),
            success = Color(0xFF5BE08D),
            warning = Color(0xFFFFC24D),
            danger = Color(0xFFFF7A85),
            emptyStateAccent = Color(0xFFB39CFF),
            selection = Color(0xFF7A6200),
            // Ink inverts in dark mode: outlines and hard shadows are both light, as both are
            // black on the light canvas. A black shadow would vanish into the dark canvas.
            hardShadow = Color(0xFFEDEDF5),
        )
    } else {
        light
    }
}

@OptIn(ExperimentalTextApi::class)
private val SpaceGrotesk = FontFamily(
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.space_grotesk,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)
