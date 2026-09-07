package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.toPaperCollectionUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.model.toReadingHistoryUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.task.PaperTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class StatsUiState(
    val library: LoadState<List<PaperUi>> = LoadState.Loading,
    val history: LoadState<List<ReadingHistoryUi>> = LoadState.Loading,
    val collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    val tasks: LoadState<List<PaperTask>> = LoadState.Loading,
    val savedSearches: LoadState<List<SavedSearchFeed>> = LoadState.Loading,
)

class StatsViewModel(
    private val logic: PaperReaderLogic,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = mutableUiState.asStateFlow()

    init {
        collect(logic.useCases.observeLibrary.subscribe().asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() } }) {
            copy(library = it)
        }
        collect(logic.useCases.observeReadingHistory.subscribe().asLoadState(viewModelScope) { entries -> entries.map { it.toReadingHistoryUi() } }) {
            copy(history = it)
        }
        collect(logic.useCases.observeCollections.subscribe().asLoadState(viewModelScope) { entries -> entries.map { it.toPaperCollectionUi() } }) {
            copy(collections = it)
        }
        collect(logic.tasks.tasks.asLoadState(viewModelScope) { it }) { copy(tasks = it) }
        collect(logic.useCases.observeSavedSearches.subscribe().asLoadState(viewModelScope) { it }) {
            copy(savedSearches = it)
        }
    }

    private fun <T> collect(
        state: StateFlow<LoadState<T>>,
        update: StatsUiState.(LoadState<T>) -> StatsUiState,
    ) {
        viewModelScope.launch {
            state.collectLatest { value -> mutableUiState.value = mutableUiState.value.update(value) }
        }
    }
}
