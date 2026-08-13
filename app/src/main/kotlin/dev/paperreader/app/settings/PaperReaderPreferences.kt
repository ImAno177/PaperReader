package dev.paperreader.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.paperReaderSettings by preferencesDataStore(name = "paper-reader-settings")

class PaperReaderPreferences(context: Context) {
    private val dataStore = context.applicationContext.paperReaderSettings
    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val themeKey: Flow<String> = preferences.map { values ->
        values[THEME_KEY] ?: PaperThemePreset.NEOBRUTALISM.storageKey
    }

    val theme: Flow<PaperThemePreset> = themeKey.map(PaperThemePreset::fromStorageKey)

    val themeMode: Flow<PaperThemeMode> = preferences.map { values ->
        PaperThemeMode.fromStorageKey(values[THEME_MODE_KEY])
    }

    val libraryLayout: Flow<LibraryLayout> = preferences.map { values ->
        LibraryLayout.fromStorageKey(values[LIBRARY_LAYOUT_KEY])
    }

    val automaticSavedSearchRefreshEnabled: Flow<Boolean> = preferences.map { values ->
        values[AUTOMATIC_SAVED_SEARCH_REFRESH_KEY] ?: false
    }

    suspend fun setTheme(preset: PaperThemePreset) {
        setThemeKey(preset.storageKey)
    }

    suspend fun setThemeKey(storageKey: String) {
        require(
            storageKey in PaperThemePreset.entries.map(PaperThemePreset::storageKey) ||
                storageKey.matches(Regex("community:[a-zA-Z0-9._-]+:[a-z0-9][a-z0-9._-]{1,63}")),
        ) { "Invalid theme storage key" }
        dataStore.edit { values -> values[THEME_KEY] = storageKey }
    }

    suspend fun setThemeMode(mode: PaperThemeMode) {
        dataStore.edit { values -> values[THEME_MODE_KEY] = mode.storageKey }
    }

    suspend fun setLibraryLayout(layout: LibraryLayout) {
        dataStore.edit { values -> values[LIBRARY_LAYOUT_KEY] = layout.storageKey }
    }

    suspend fun setAutomaticSavedSearchRefreshEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[AUTOMATIC_SAVED_SEARCH_REFRESH_KEY] = enabled }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preset")
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val LIBRARY_LAYOUT_KEY = stringPreferencesKey("library_layout")
        val AUTOMATIC_SAVED_SEARCH_REFRESH_KEY = booleanPreferencesKey("automatic_saved_search_refresh")
    }
}
