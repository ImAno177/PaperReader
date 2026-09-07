package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.TabletUiMode
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.model.PaperDateFormat
import dev.paperreader.app.ui.theme.PaperThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AppearanceUiState(
    val themeKey: String = "neobrutalism",
    val themeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    val dateFormat: PaperDateFormat = PaperDateFormat.DEFAULT,
    val relativeTimeEnabled: Boolean = false,
    val tabletUiMode: TabletUiMode = TabletUiMode.AUTOMATIC,
    val showImagesInDescription: Boolean = true,
)

sealed interface AppearanceAction {
    data class SetThemeKey(val value: String) : AppearanceAction
    data class SetThemeMode(val value: PaperThemeMode) : AppearanceAction
    data class SetLibraryLayout(val value: LibraryLayout) : AppearanceAction
    data class SetDateFormat(val value: PaperDateFormat) : AppearanceAction
    data class SetRelativeTime(val value: Boolean) : AppearanceAction
    data class SetTabletUiMode(val value: TabletUiMode) : AppearanceAction
    data class SetShowImagesInDescription(val value: Boolean) : AppearanceAction
}

class AppearanceViewModel(
    private val preferences: PaperReaderPreferences,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AppearanceUiState())
    val uiState: StateFlow<AppearanceUiState> = mutableUiState.asStateFlow()

    init {
        collect(preferences.themeKey) { copy(themeKey = it) }
        collect(preferences.themeMode) { copy(themeMode = it) }
        collect(preferences.libraryLayout) { copy(libraryLayout = it) }
        collect(preferences.dateFormat) { copy(dateFormat = it) }
        collect(preferences.relativeTime) { copy(relativeTimeEnabled = it) }
        collect(preferences.tabletUiMode) { copy(tabletUiMode = it) }
        collect(preferences.showImagesInDescription) { copy(showImagesInDescription = it) }
    }

    fun onAction(action: AppearanceAction) {
        when (action) {
            is AppearanceAction.SetThemeKey -> set { preferences.setThemeKey(action.value) }
            is AppearanceAction.SetThemeMode -> set { preferences.setThemeMode(action.value) }
            is AppearanceAction.SetLibraryLayout -> set { preferences.setLibraryLayout(action.value) }
            is AppearanceAction.SetDateFormat -> set { preferences.setDateFormat(action.value) }
            is AppearanceAction.SetRelativeTime -> set { preferences.setRelativeTime(action.value) }
            is AppearanceAction.SetTabletUiMode -> set { preferences.setTabletUiMode(action.value) }
            is AppearanceAction.SetShowImagesInDescription -> set { preferences.setShowImagesInDescription(action.value) }
        }
    }

    private fun <T> collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        update: AppearanceUiState.(T) -> AppearanceUiState,
    ) {
        viewModelScope.launch {
            flow.collectLatest { value ->
                mutableUiState.value = mutableUiState.value.update(value)
            }
        }
    }

    private fun set(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}
