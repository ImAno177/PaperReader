package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.ui.PaperReaderLocalPdfImportController
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.logic.PaperReaderLogic
import kotlinx.coroutines.flow.StateFlow

sealed interface ReadingImportsAction {
    data class Prepare(val sourceUri: String) : ReadingImportsAction
    data class Confirm(val title: String) : ReadingImportsAction
    data object Dismiss : ReadingImportsAction
}

class ReadingImportsViewModel(
    logic: PaperReaderLogic,
) : ViewModel() {
    private val controller = PaperReaderLocalPdfImportController(logic, viewModelScope)
    val uiState: StateFlow<LocalPdfImportUiState> = controller.state

    fun onAction(action: ReadingImportsAction): Boolean = when (action) {
        is ReadingImportsAction.Prepare -> controller.prepare(action.sourceUri)
        is ReadingImportsAction.Confirm -> {
            controller.confirm(action.title)
            true
        }
        ReadingImportsAction.Dismiss -> {
            controller.dismiss()
            true
        }
    }
}
