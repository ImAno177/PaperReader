package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.DownloadActionUiState
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState

/** A focused queue surface with live state from the task repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    tasks: LoadState<List<PaperTask>>,
    library: LoadState<List<PaperUi>> = LoadState.Loading,
    actions: DownloadActionUiState = DownloadActionUiState(),
    onOpenPaper: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
    onRetry: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val paperTitles = (library as? LoadState.Ready)
        ?.value
        ?.associate { it.id to it.title }
        .orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.download_queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        PaperIcon(
                            PaperIconKey.BACK,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tasks) {
                LoadState.Loading -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.download_queue_loading),
                        loading = true,
                        compact = true,
                    )
                }

                LoadState.Failed -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.data_error_title),
                        body = stringResource(R.string.data_error_body),
                        icon = PaperIconKey.ERROR,
                        compact = true,
                    )
                }

                is LoadState.Ready -> {
                    val queue = tasks.value.sortedWith(downloadQueueComparator)
                    if (queue.isEmpty()) {
                        item {
                            PaperStatePanel(
                                title = stringResource(R.string.download_queue_empty_title),
                                body = stringResource(R.string.download_queue_empty_body),
                                icon = PaperIconKey.SYNC,
                                compact = true,
                            )
                        }
                    } else {
                        item { DownloadQueueSummary(queue) }
                        val active = queue.filter { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
                        val attention = queue.filter { it.state == TaskState.FAILED || it.state == TaskState.CANCELLED }
                        val completed = queue.filter { it.state == TaskState.SUCCEEDED }
                        if (active.isNotEmpty()) {
                            item { PaperSectionHeader(title = stringResource(R.string.download_queue_active_section)) }
                            items(active, key = { "active-${it.id.value}" }) { task ->
                                QueueTaskRow(task, paperTitles, actions, onOpenPaper, onCancel, onRetry, onRemove)
                            }
                        }
                        if (attention.isNotEmpty()) {
                            item { PaperSectionHeader(title = stringResource(R.string.download_queue_attention_section)) }
                            items(attention, key = { "attention-${it.id.value}" }) { task ->
                                QueueTaskRow(task, paperTitles, actions, onOpenPaper, onCancel, onRetry, onRemove)
                            }
                        }
                        if (completed.isNotEmpty()) {
                            item { PaperSectionHeader(title = stringResource(R.string.download_queue_completed_section)) }
                            items(completed, key = { "completed-${it.id.value}" }) { task ->
                                QueueTaskRow(task, paperTitles, actions, onOpenPaper, onCancel, onRetry, onRemove)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueSummary(tasks: List<PaperTask>) {
    val active = tasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
    val running = tasks.count { it.state == TaskState.RUNNING }
    val failed = tasks.count { it.state == TaskState.FAILED }
    val completed = tasks.count { it.state == TaskState.SUCCEEDED }
    val activeTasks = tasks.filter { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
    val progress = activeTasks.takeIf { it.isNotEmpty() }?.map(PaperTask::progress)?.average()?.toFloat()
    PaperSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.download_queue_live_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                PaperLabel(
                    pluralStringResource(
                        R.plurals.download_queue_total,
                        tasks.size,
                        tasks.size,
                    ),
                )
            }
            Text(
                text = stringResource(
                    R.string.download_queue_live_summary,
                    active,
                    running,
                    failed,
                    completed,
                ),
                color = PaperTheme.tokens.inkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (progress != null) {
                val progressPercent = (progress * 100).toInt()
                val progressDescription = stringResource(
                    R.string.download_queue_progress_description,
                    progressPercent,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = progressDescription
                        },
                    color = PaperTheme.tokens.primary,
                    trackColor = PaperTheme.tokens.surfaceMuted,
                )
                Text(
                    text = stringResource(
                        R.string.download_queue_progress_value,
                        progressPercent,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun QueueTaskRow(
    task: PaperTask,
    paperTitles: Map<String, String>,
    actions: DownloadActionUiState,
    onOpenPaper: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val workId = task.workId?.value
    DownloadTaskRow(
        task = task,
        paperTitle = workId?.let(paperTitles::get),
        acting = task.id.value in actions.actingTaskIds,
        actionFailed = task.id.value in actions.failedTaskIds,
        onClick = workId?.let { id -> { onOpenPaper(id) } },
        onCancel = { onCancel(task.id.value) },
        onRetry = { onRetry(task.id.value) },
        onRemove = { onRemove(task.id.value) },
    )
}

private val downloadQueueComparator = compareBy<PaperTask>(
    { task ->
        when (task.state) {
            TaskState.RUNNING -> 0
            TaskState.QUEUED -> 1
            TaskState.FAILED -> 2
            TaskState.CANCELLED -> 3
            TaskState.SUCCEEDED -> 4
        }
    },
    { it.createdAt },
)
