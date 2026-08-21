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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

enum class PaperThemePreset(
    val storageKey: String,
) {
    NEOBRUTALISM("neobrutalism"),
    ;

    companion object {
        fun fromStorageKey(value: String?): PaperThemePreset =
            entries.firstOrNull { it.storageKey == value } ?: NEOBRUTALISM
    }
}

enum class PaperThemeMode(
    val storageKey: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun resolveDarkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStorageKey(value: String?): PaperThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

enum class PaperDecoration {
    NONE,
    /** Legacy community-extension decoration; no built-in preset uses it. */
    DOODLE,
    /** Legacy community-extension decoration; it is not a selectable built-in preset. */
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

internal fun paperThemeTokens(preset: PaperThemePreset, dark: Boolean): PaperThemeTokens = when (preset) {
    PaperThemePreset.NEOBRUTALISM -> neobrutalismThemeTokens(dark)
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
        tertiary = secondary,
        onTertiary = onSecondary,
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = onSecondaryContainer,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = inkMuted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceMuted,
        surfaceContainerHigh = surfaceMuted,
        surfaceContainerHighest = surfaceMuted,
        surfaceDim = canvas,
        surfaceBright = surface,
        outline = border,
        outlineVariant = border.copy(alpha = 0.65f),
        error = danger,
        onError = Color.White,
        errorContainer = surfaceMuted,
        onErrorContainer = ink,
        inverseSurface = ink,
        inverseOnSurface = surface,
        inversePrimary = primary,
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
        tertiary = secondary,
        onTertiary = onSecondary,
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = onSecondaryContainer,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = inkMuted,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceMuted,
        surfaceContainerHigh = surfaceMuted,
        surfaceContainerHighest = surfaceMuted,
        surfaceDim = canvas,
        surfaceBright = surface,
        outline = border,
        outlineVariant = border.copy(alpha = 0.65f),
        error = danger,
        onError = Color.White,
        errorContainer = surfaceMuted,
        onErrorContainer = ink,
        inverseSurface = ink,
        inverseOnSurface = surface,
        inversePrimary = primary,
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
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = bodyFont,
            fontWeight = FontWeight.Medium,
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
    communityTheme: CommunityPaperTheme? = null,
    themeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
    val tokens = communityTheme?.tokens(darkTheme) ?: paperThemeTokens(preset, darkTheme)
    val icons = communityTheme?.let { PaperIconSet.community(it.iconPaths) } ?: paperIconSet(preset)
    MaterialTheme(
        colorScheme = tokens.materialScheme(darkTheme),
        typography = tokens.materialTypography(),
        shapes = tokens.materialShapes(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPaperTheme provides tokens,
            LocalPaperIcons provides icons,
        ) {
            content()
        }
    }
}
