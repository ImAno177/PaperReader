package dev.paperreader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

enum class PaperThemePreset(
    val storageKey: String,
) {
    DOODLE("doodle"),
    RETRO("retro"),
    NEOBRUTALISM("neobrutalism"),
    ;

    companion object {
        fun fromStorageKey(value: String?): PaperThemePreset =
            entries.firstOrNull { it.storageKey == value } ?: NEOBRUTALISM
    }
}

enum class PaperDecoration {
    NONE,
    DOODLE,
    RETRO_GRID,
}

@Immutable
data class PaperThemeTokens(
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val ink: Color,
    val inkMuted: Color,
    val border: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val emptyStateAccent: Color,
    val selection: Color,
    val hardShadow: Color,
    val cornerRadius: Dp,
    val borderWidth: Dp,
    val shadowOffset: Dp,
    val titleFont: FontFamily,
    val bodyFont: FontFamily,
    val labelFont: FontFamily,
    val decoration: PaperDecoration,
)

private val DoodleBlue = Color(0xFF49B6E5)
private val DoodleNavy = Color(0xFF263D5B)
private val RetroBlue = Color(0xFF1F5F8B)
private val RetroBrick = Color(0xFFB84C2A)
private val NeoMustard = Color(0xFFFDC800)
private val NeoViolet = Color(0xFF432DD7)
private val Success = Color(0xFF16A34A)
private val Warning = Color(0xFFD97706)
private val Danger = Color(0xFFDC2626)

internal fun paperThemeTokens(preset: PaperThemePreset, dark: Boolean): PaperThemeTokens = when (preset) {
    PaperThemePreset.DOODLE -> if (dark) {
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
            success = Success,
            warning = Warning,
            danger = Danger,
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

    PaperThemePreset.RETRO -> if (dark) {
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
            success = Success,
            warning = Warning,
            danger = Danger,
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

    PaperThemePreset.NEOBRUTALISM -> if (dark) {
        PaperThemeTokens(
            canvas = Color(0xFF121316),
            surface = Color(0xFF1D2129),
            surfaceMuted = Color(0xFF303642),
            ink = Color(0xFFF7F0E0),
            inkMuted = Color(0xFFC7C1B4),
            border = Color(0xFFF7F0E0),
            primary = Color(0xFFFFD84A),
            onPrimary = Color(0xFF241F00),
            primaryContainer = Color(0xFF6A5600),
            onPrimaryContainer = Color(0xFFFFF0A5),
            secondary = Color(0xFF9C8BFF),
            onSecondary = Color(0xFF1E115D),
            secondaryContainer = Color(0xFF403783),
            onSecondaryContainer = Color(0xFFE4DEFF),
            success = Color(0xFF72D69B),
            warning = Color(0xFFFFC76B),
            danger = Color(0xFFFF8A80),
            emptyStateAccent = Color(0xFFFFD84A),
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
            success = Success,
            warning = Warning,
            danger = Danger,
            emptyStateAccent = Color(0xFF574800),
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
}

private fun PaperThemeTokens.materialScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = inkMuted,
        outline = border,
        error = danger,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = inkMuted,
        outline = border,
        error = danger,
        onError = Color.White,
    )
}

private fun PaperThemeTokens.materialTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(
            fontFamily = titleFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = bodyFont,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = bodyFont,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.2.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = labelFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.1.sp,
        ),
    )
}

private fun PaperThemeTokens.materialShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(cornerRadius / 2),
    small = RoundedCornerShape(cornerRadius),
    medium = RoundedCornerShape(cornerRadius),
    large = RoundedCornerShape(cornerRadius * 1.5f),
    extraLarge = RoundedCornerShape(cornerRadius * 2),
)

val LocalPaperTheme = staticCompositionLocalOf<PaperThemeTokens> {
    error("PaperReaderTheme must be applied before reading PaperTheme.tokens")
}

object PaperTheme {
    val tokens: PaperThemeTokens
        @Composable
        get() = LocalPaperTheme.current
}

@Composable
fun PaperReaderTheme(
    preset: PaperThemePreset,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = paperThemeTokens(preset, darkTheme)
    MaterialTheme(
        colorScheme = tokens.materialScheme(darkTheme),
        typography = tokens.materialTypography(),
        shapes = tokens.materialShapes(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPaperTheme provides tokens,
            LocalPaperIcons provides paperIconSet(preset),
        ) {
            content()
        }
    }
}
