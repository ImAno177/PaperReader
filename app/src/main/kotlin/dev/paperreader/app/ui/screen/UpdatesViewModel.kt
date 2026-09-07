package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.PaperReaderSearchController
import dev.paperreader.app.ui.SavedSearchActionUiState
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.DownloadActionUiState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.task.CancelTaskResult
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.RemoveTaskResult
import dev.paperreader.logic.task.RetryDownloadResult
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class UpdatesUiState(
    val tasks: LoadState<List<PaperTask>> = LoadState.Loading,
    val library: LoadState<List<PaperUi>> = LoadState.Loading,
    val providers: ProviderManagerState = ProviderManagerState(),
    val savedSearches: LoadState<List<SavedSearchFeed>> = LoadState.Loading,
    val savedSearchActions: SavedSearchActionUiState = SavedSearchActionUiState(),
    val downloadActions: DownloadActionUiState = DownloadActionUiState(),
)

sealed interface UpdatesAction {
    data class RefreshSearch(val searchId: String) : UpdatesAction
    data class DeleteSearch(val searchId: String) : UpdatesAction
    data class MarkHitRead(val hitId: String) : UpdatesAction
    data class SaveHit(val hitId: String) : UpdatesAction
    data class CancelTask(val taskId: String) : UpdatesAction
    data class RetryTask(val taskId: String) : UpdatesAction
    data class RemoveTask(val taskId: String) : UpdatesAction
}

class UpdatesViewModel(
    private val logic: PaperReaderLogic,
    private val downloadWorkScheduler: DownloadWorkScheduler,
    preferences: PaperReaderPreferences,
) : ViewModel() {
    private val library = logic.useCases.observeLibrary
        .subscribe()
        .asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() } }
    private val searchController = PaperReaderSearchController(
        logic = logic,
        scope = viewModelScope,
        providers = logic.providers.state,
        preferences = preferences,
        library = library,
    )
    private val mutableUiState = MutableStateFlow(UpdatesUiState())
    val uiState: StateFlow<UpdatesUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            logic.tasks.tasks
                .asLoadState(viewModelScope) { it }
                .collectLatest { tasks -> update { copy(tasks = tasks) } }
        }
        viewModelScope.launch { library.collectLatest { state -> update { copy(library = state) } } }
        viewModelScope.launch {
            logic.providers.state.collectLatest { providers -> update { copy(providers = providers) } }
        }
        viewModelScope.launch {
            searchController.savedSearches.collectLatest { searches -> update { copy(savedSearches = searches) } }
        }
        viewModelScope.launch {
            searchController.savedSearchActions.collectLatest { actions -> update { copy(savedSearchActions = actions) } }
        }
    }

    fun onAction(action: UpdatesAction) {
        when (action) {
            is UpdatesAction.RefreshSearch -> searchController.refreshSavedSearch(action.searchId)
            is UpdatesAction.DeleteSearch -> searchController.deleteSavedSearch(action.searchId)
            is UpdatesAction.MarkHitRead -> searchController.markSavedSearchHitRead(action.hitId)
            is UpdatesAction.SaveHit -> searchController.saveSavedSearchHit(action.hitId)
            is UpdatesAction.CancelTask -> cancelTask(action.taskId)
            is UpdatesAction.RetryTask -> retryTask(action.taskId)
            is UpdatesAction.RemoveTask -> removeTask(action.taskId)
        }
    }

    private fun cancelTask(taskId: String) = performTaskAction(taskId) {
        when (logic.downloads.cancelDownload(TaskId(taskId))) {
            is CancelTaskResult.Cancelled,
            is CancelTaskResult.AlreadyTerminal,
            -> downloadWorkScheduler.cancel(TaskId(taskId))

            CancelTaskResult.NotFound -> Unit
        }
    }

    private fun retryTask(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        downloadWorkScheduler.cancel(id)
        when (val result = logic.downloads.retryDownload(id)) {
            is RetryDownloadResult.Enqueued -> downloadWorkScheduler.enqueue(result.task.id)
            is RetryDownloadResult.NotRetryable -> if (result.task.state in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
                downloadWorkScheduler.enqueue(result.task.id)
            }
            is RetryDownloadResult.AlreadyDownloaded,
            RetryDownloadResult.NotFound,
            -> Unit
        }
    }

    private fun removeTask(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        downloadWorkScheduler.cancel(id)
        if (logic.downloads.removeDownloadTask(id) == RemoveTaskResult.Active) {
            downloadWorkScheduler.enqueue(id)
        }
    }

    private fun performTaskAction(taskId: String, action: suspend () -> Unit) {
        val current = mutableUiState.value.downloadActions
        if (taskId in current.actingTaskIds) return
        update {
            copy(
                downloadActions = current.copy(
                    actingTaskIds = current.actingTaskIds + taskId,
                    failedTaskIds = current.failedTaskIds - taskId,
                ),
            )
        }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update {
                    copy(downloadActions = downloadActions.copy(failedTaskIds = downloadActions.failedTaskIds + taskId))
                }
            } finally {
                update {
                    copy(downloadActions = downloadActions.copy(actingTaskIds = downloadActions.actingTaskIds - taskId))
                }
            }
        }
    }

    private fun update(block: UpdatesUiState.() -> UpdatesUiState) {
        mutableUiState.value = mutableUiState.value.block()
    }
}
