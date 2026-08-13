package dev.paperreader.app.ui.theme

import android.view.Window
import androidx.core.view.WindowCompat

internal enum class SystemBarAppearance {
    LIGHT_BACKGROUND,
    DARK_BACKGROUND,
}

internal fun resolveSystemBarAppearance(
    mode: PaperThemeMode,
    systemDark: Boolean,
): SystemBarAppearance = if (mode.resolveDarkTheme(systemDark)) {
    SystemBarAppearance.DARK_BACKGROUND
} else {
    SystemBarAppearance.LIGHT_BACKGROUND
}

internal fun Window.setSystemBarAppearance(appearance: SystemBarAppearance) {
    val useDarkIcons = appearance == SystemBarAppearance.LIGHT_BACKGROUND
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
}
