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
 * Built-in Neobrutalism tokens, following the canonical palette published at neubrutalism.com and
 * NN/g's guidance: a black-and-white structure (black ink, 3 dp black outlines, square corners,
 * and an unblurred 4 dp offset shadow) on an off-white canvas with white cards, punctuated by
 * three flat accents that always carry black text: yellow actions, sky-blue selection, and coral
 * as the secondary accent. Light accents never serve as text; see [primaryForeground].
 *
 * The references define no dark palette, so dark mode keeps the accents and their black text and
 * inverts the structure: off-white outlines and shadows on a near-black canvas, because a black
 * shadow would vanish there.
 */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFFFFDF5),
        surface = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFEEEADB),
        ink = Color(0xFF000000),
        inkMuted = Color(0xFF3C3C3C),
        border = Color(0xFF000000),
        primary = Color(0xFFFFD23F),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF74B9FF),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFFFF6B6B),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFFFFD6D6),
        onSecondaryContainer = Color(0xFF000000),
        success = Color(0xFF0F7A35),
        warning = Color(0xFF8A4B00),
        danger = Color(0xFFC2161D),
        emptyStateAccent = Color(0xFF000000),
        selection = Color(0xFFFFD23F),
        hardShadow = Color(0xFF000000),
        cornerRadius = 0.dp,
        borderWidth = 3.dp,
        shadowOffset = 4.dp,
        titleFont = SpaceGrotesk,
        bodyFont = FontFamily.SansSerif,
        labelFont = FontFamily.SansSerif,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF111111),
            surface = Color(0xFF1E1E1E),
            surfaceMuted = Color(0xFF2E2E2E),
            ink = Color(0xFFFFFDF5),
            inkMuted = Color(0xFFCFCBBE),
            border = Color(0xFFFFFDF5),
            success = Color(0xFF6FD08C),
            warning = Color(0xFFFFC266),
            danger = Color(0xFFFF8A8A),
            emptyStateAccent = Color(0xFFFFD23F),
            selection = Color(0xFF6B5600),
            hardShadow = Color(0xFFFFFDF5),
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
