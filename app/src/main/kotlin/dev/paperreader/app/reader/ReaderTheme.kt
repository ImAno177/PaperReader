package dev.paperreader.app.reader

import androidx.appcompat.app.AppCompatDelegate
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset

internal fun readerThemeStyle(preset: PaperThemePreset): Int = when (preset) {
    PaperThemePreset.DOODLE -> R.style.Theme_PaperReader_PdfReader_Doodle
    PaperThemePreset.RETRO -> R.style.Theme_PaperReader_PdfReader_Retro
    PaperThemePreset.NEOBRUTALISM -> R.style.Theme_PaperReader_PdfReader_Neobrutalism
}

internal fun PaperThemeMode.toAppCompatNightMode(): Int = when (this) {
    PaperThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    PaperThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    PaperThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
