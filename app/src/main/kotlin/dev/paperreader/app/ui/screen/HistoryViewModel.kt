package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.toReadingHistoryUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.WorkId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HistoryAction {
    data class Remove(val workId: String) : HistoryAction
}

class HistoryViewModel(
    private val logic: PaperReaderLogic,
) : ViewModel() {
    val uiState: StateFlow<LoadState<List<ReadingHistoryUi>>> = logic.useCases.observeReadingHistory
        .subscribe()
        .asLoadState(viewModelScope) { entries -> entries.map { it.toReadingHistoryUi() } }

    fun onAction(action: HistoryAction) {
        when (action) {
            is HistoryAction.Remove -> viewModelScope.launch {
                logic.useCases.removeReadingHistory.await(WorkId(action.workId))
            }
        }
    }
}
