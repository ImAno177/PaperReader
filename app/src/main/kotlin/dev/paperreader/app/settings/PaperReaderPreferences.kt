package dev.paperreader.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    val disabledProviderIds: Flow<Set<String>> = preferences.map { values ->
        values[DISABLED_PROVIDER_IDS_KEY].orEmpty()
    }

    /** The last few submitted discovery queries, newest first. */
    val recentSearchQueries: Flow<List<String>> = preferences.map { values ->
        values[RECENT_SEARCH_QUERIES_KEY]
            .orEmpty()
            .split(RECENT_SEARCH_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(MAX_RECENT_SEARCHES)
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

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        require(providerId.isNotBlank())
        dataStore.edit { values ->
            val disabled = values[DISABLED_PROVIDER_IDS_KEY].orEmpty().toMutableSet()
            if (enabled) disabled.remove(providerId) else disabled.add(providerId)
            values[DISABLED_PROVIDER_IDS_KEY] = disabled
        }
    }

    suspend fun setRecentSearchQueries(queries: List<String>) {
        val normalized = queries
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(MAX_RECENT_SEARCHES)
        dataStore.edit { values ->
            if (normalized.isEmpty()) {
                values.remove(RECENT_SEARCH_QUERIES_KEY)
            } else {
                values[RECENT_SEARCH_QUERIES_KEY] = normalized.joinToString(RECENT_SEARCH_SEPARATOR)
            }
        }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preset")
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val LIBRARY_LAYOUT_KEY = stringPreferencesKey("library_layout")
        val AUTOMATIC_SAVED_SEARCH_REFRESH_KEY = booleanPreferencesKey("automatic_saved_search_refresh")
        val DISABLED_PROVIDER_IDS_KEY = stringSetPreferencesKey("disabled_provider_ids")
        val RECENT_SEARCH_QUERIES_KEY = stringPreferencesKey("recent_search_queries")
        const val RECENT_SEARCH_SEPARATOR = "\u0000"
        const val MAX_RECENT_SEARCHES = 8
    }
}
