package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.backup.MetadataRestoreSessionStore
import dev.paperreader.app.ui.PaperReaderMetadataBackupController
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.backup.MetadataBackupExport
import kotlinx.coroutines.flow.StateFlow

sealed interface DataBackupAction {
    data object ConfirmRestore : DataBackupAction
    data object Dismiss : DataBackupAction
}

internal class DataBackupViewModel(
    logic: PaperReaderLogic,
    sessionStore: MetadataRestoreSessionStore,
) : ViewModel() {
    private val controller = PaperReaderMetadataBackupController(logic, sessionStore, viewModelScope)
    val uiState: StateFlow<MetadataBackupUiState> = controller.state

    fun createBackup(write: suspend (MetadataBackupExport) -> Unit) = controller.create(write)

    fun previewRestore(read: suspend () -> ByteArray) = controller.previewRestore(read)

    fun onAction(action: DataBackupAction) {
        when (action) {
            DataBackupAction.ConfirmRestore -> controller.confirmRestore()
            DataBackupAction.Dismiss -> controller.dismiss()
        }
    }
}
