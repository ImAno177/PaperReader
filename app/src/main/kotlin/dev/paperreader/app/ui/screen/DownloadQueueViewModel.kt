package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.state.DownloadActionUiState
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
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

data class DownloadQueueUiState(
    val tasks: LoadState<List<PaperTask>> = LoadState.Loading,
    val library: LoadState<List<PaperUi>> = LoadState.Loading,
    val actions: DownloadActionUiState = DownloadActionUiState(),
)

sealed interface DownloadQueueAction {
    data class Cancel(val taskId: String) : DownloadQueueAction
    data class Retry(val taskId: String) : DownloadQueueAction
    data class Remove(val taskId: String) : DownloadQueueAction
}

class DownloadQueueViewModel(
    private val logic: PaperReaderLogic,
    private val downloadWorkScheduler: DownloadWorkScheduler,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DownloadQueueUiState())
    val uiState: StateFlow<DownloadQueueUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            logic.tasks.tasks.asLoadState(viewModelScope) { it }.collectLatest { tasks -> update { copy(tasks = tasks) } }
        }
        viewModelScope.launch {
            logic.useCases.observeLibrary
                .subscribe()
                .asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() } }
                .collectLatest { library -> update { copy(library = library) } }
        }
    }

    fun onAction(action: DownloadQueueAction) {
        when (action) {
            is DownloadQueueAction.Cancel -> cancel(action.taskId)
            is DownloadQueueAction.Retry -> retry(action.taskId)
            is DownloadQueueAction.Remove -> remove(action.taskId)
        }
    }

    private fun cancel(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        when (logic.downloads.cancelDownload(id)) {
            is CancelTaskResult.Cancelled,
            is CancelTaskResult.AlreadyTerminal,
            -> downloadWorkScheduler.cancel(id)

            CancelTaskResult.NotFound -> Unit
        }
    }

    private fun retry(taskId: String) = performTaskAction(taskId) {
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

    private fun remove(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        downloadWorkScheduler.cancel(id)
        if (logic.downloads.removeDownloadTask(id) == RemoveTaskResult.Active) {
            downloadWorkScheduler.enqueue(id)
        }
    }

    private fun performTaskAction(taskId: String, action: suspend () -> Unit) {
        val current = mutableUiState.value.actions
        if (taskId in current.actingTaskIds) return
        update {
            copy(actions = current.copy(actingTaskIds = current.actingTaskIds + taskId, failedTaskIds = current.failedTaskIds - taskId))
        }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update { copy(actions = actions.copy(failedTaskIds = actions.failedTaskIds + taskId)) }
            } finally {
                update { copy(actions = actions.copy(actingTaskIds = actions.actingTaskIds - taskId)) }
            }
        }
    }

    private fun update(block: DownloadQueueUiState.() -> DownloadQueueUiState) {
        mutableUiState.value = mutableUiState.value.block()
    }
}
