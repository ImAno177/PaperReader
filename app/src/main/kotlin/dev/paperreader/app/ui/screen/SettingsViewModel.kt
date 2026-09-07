package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val query: String = "",
)

sealed interface SettingsAction {
    data class SetQuery(val value: String) : SettingsAction
}

class SettingsViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetQuery -> mutableUiState.value = mutableUiState.value.copy(query = action.value)
        }
    }
}
