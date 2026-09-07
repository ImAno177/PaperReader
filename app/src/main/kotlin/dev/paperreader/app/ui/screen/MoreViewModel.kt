package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class MoreUiState(
    val themeKey: String = "neobrutalism",
    val savedPaperCount: LoadState<Int> = LoadState.Loading,
    val activeDownloadCount: LoadState<Int> = LoadState.Loading,
    val failedDownloadCount: LoadState<Int> = LoadState.Loading,
    val collectionCount: LoadState<Int> = LoadState.Loading,
    val providerReviewCount: Int = 0,
    val availableProviderCount: Int = 0,
    val installedProviderCount: Int = 0,
    val localPdfImportState: LocalPdfImportUiState = LocalPdfImportUiState.Idle,
    val backupState: MetadataBackupUiState = MetadataBackupUiState.Idle,
    val automaticRefreshEnabled: Boolean = false,
    val notificationsAvailable: Boolean = true,
)

sealed interface MoreAction {
    data class SetLocalPdfImportState(val state: LocalPdfImportUiState) : MoreAction
    data class SetBackupState(val state: MetadataBackupUiState) : MoreAction
    data class SetNotificationsAvailable(val available: Boolean) : MoreAction
}

class MoreViewModel(
    private val logic: PaperReaderLogic,
    private val preferences: PaperReaderPreferences,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(MoreUiState())
    val uiState: StateFlow<MoreUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            logic.useCases.observeLibrary
                .subscribeCount()
                .asLoadState(viewModelScope) { it }
                .collectLatest { state -> update { copy(savedPaperCount = state) } }
        }
        viewModelScope.launch {
            logic.tasks.tasks
                .asLoadState(viewModelScope) { tasks -> tasks.summary() }
                .collectLatest { summary ->
                    update {
                        copy(
                            activeDownloadCount = summary.map { it.first },
                            failedDownloadCount = summary.map { it.second },
                        )
                    }
                }
        }
        viewModelScope.launch {
            logic.useCases.observeCollections
                .subscribeCount()
                .asLoadState(viewModelScope) { it }
                .collectLatest { state -> update { copy(collectionCount = state) } }
        }
        viewModelScope.launch {
            logic.providers.state.collectLatest { providers ->
                update {
                    copy(
                        providerReviewCount = providers.untrusted.size + providers.orphaned.size,
                        availableProviderCount = providers.available.size,
                        installedProviderCount = providers.installed.size,
                    )
                }
            }
        }
        collectPreference(preferences.themeKey) { copy(themeKey = it) }
        collectPreference(preferences.automaticSavedSearchRefreshEnabled) {
            copy(automaticRefreshEnabled = it)
        }
    }

    fun onAction(action: MoreAction) {
        when (action) {
            is MoreAction.SetLocalPdfImportState -> update { copy(localPdfImportState = action.state) }
            is MoreAction.SetBackupState -> update { copy(backupState = action.state) }
            is MoreAction.SetNotificationsAvailable -> update { copy(notificationsAvailable = action.available) }
        }
    }

    private fun <T> collectPreference(
        flow: kotlinx.coroutines.flow.Flow<T>,
        update: MoreUiState.(T) -> MoreUiState,
    ) {
        viewModelScope.launch {
            flow.collectLatest { value -> mutableUiState.value = mutableUiState.value.update(value) }
        }
    }

    private fun update(block: MoreUiState.() -> MoreUiState) {
        mutableUiState.value = mutableUiState.value.block()
    }
}

private fun List<PaperTask>.summary(): Pair<Int, Int> = count {
    it.state == TaskState.QUEUED || it.state == TaskState.RUNNING
} to count { it.state == TaskState.FAILED }

private fun <T> LoadState<T>.map(transform: (T) -> Int): LoadState<Int> = when (this) {
    LoadState.Loading -> LoadState.Loading
    LoadState.Failed -> LoadState.Failed
    is LoadState.Ready -> LoadState.Ready(transform(value))
}
