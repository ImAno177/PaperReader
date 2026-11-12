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
 * Built-in Neobrutalism tokens, taken from neobrutalism.dev's default (Blue) theme in
 * ekmas/neobrutalism-components `src/styling/globals.css`: black ink, 2 dp black borders, 5 dp
 * corners, and an unblurred 4 dp x 4 dp black shadow; a pale-blue background with white cards;
 * a single `main` blue that always carries black text; and DM Sans at weight 500 for text and 700
 * for headings. Dark values are that file's `.dark` block before the site dropped dark mode
 * (commit e55e9d09): navy background, near-black cards, light text, and black shadows. Its black
 * dark borders are replaced with a light outline to meet WCAG non-text contrast. The secondary
 * yellow is the theme's own chart color.
 *
 * The blue fails as text on white, so labels and icons use [primaryForeground] (ink) instead.
 */
internal fun neobrutalismThemeTokens(dark: Boolean): PaperThemeTokens {
    val light = PaperThemeTokens(
        canvas = Color(0xFFDCEBFE),
        surface = Color(0xFFFFFFFF),
        surfaceMuted = Color(0xFFD3E1F5),
        ink = Color(0xFF000000),
        inkMuted = Color(0xFF374151),
        border = Color(0xFF000000),
        primary = Color(0xFF5294FF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF5294FF),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFFFACC00),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFFFFEB99),
        onSecondaryContainer = Color(0xFF000000),
        success = Color(0xFF06652F),
        warning = Color(0xFF7A4100),
        danger = Color(0xFFB0141C),
        emptyStateAccent = Color(0xFF000000),
        selection = Color(0xFFFACC00),
        hardShadow = Color(0xFF000000),
        cornerRadius = 5.dp,
        borderWidth = 2.dp,
        shadowOffset = 4.dp,
        titleFont = DmSans,
        bodyFont = DmSans,
        labelFont = DmSans,
        decoration = PaperDecoration.NONE,
    )
    return if (dark) {
        light.copy(
            canvas = Color(0xFF20294B),
            surface = Color(0xFF1F1F1F),
            surfaceMuted = Color(0xFF2E2E2E),
            ink = Color(0xFFE6E6E6),
            inkMuted = Color(0xFFB9BDC8),
            // Upstream keeps black borders in dark mode, but black on these cards is about 1.3:1,
            // below WCAG 1.4.11's 3:1 for component boundaries; a light outline keeps fields and
            // secondary buttons visible while the shadows stay black.
            border = Color(0xFFA6ADC2),
            success = Color(0xFF05E17A),
            warning = Color(0xFFFACC00),
            danger = Color(0xFFFF6B6E),
            emptyStateAccent = Color(0xFFE6E6E6),
            selection = Color(0xFF6B5600),
        )
    } else {
        light
    }
}

// The variable font defaults to its 9 pt caption design; 14 pt is the text design the site renders.
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
