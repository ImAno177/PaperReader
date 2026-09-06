package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperProgress
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState

@Composable
internal fun DownloadTaskRow(
    task: PaperTask,
    paperTitle: String?,
    acting: Boolean,
    actionFailed: Boolean,
    onClick: (() -> Unit)?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val availableActions = downloadTaskActions(task)
    val rowClick = onClick.takeIf { availableActions.isEmpty() }
    val kindLabel = stringResource(
        if (task.kind == TaskKind.DOWNLOAD) R.string.task_download else R.string.task_extraction,
    )
    val metadata = buildList {
        if (paperTitle != null) add(kindLabel)
        if (task.attempt > 0) add(stringResource(R.string.task_attempt, task.attempt))
    }
    PaperSurface(onClick = rowClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = paperTitle ?: kindLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (acting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PaperTheme.tokens.ink,
                        strokeWidth = 2.dp,
                    )
                }
                StatusBadge(taskStateLabel(task.state), color = taskStateColor(task.state))
            }
            if (metadata.isNotEmpty()) PaperLabel(metadata.joinToString(" \u2022 "))
            when (task.state) {
                TaskState.QUEUED -> {
                    Text(
                        text = stringResource(R.string.task_waiting_to_start),
                        style = MaterialTheme.typography.labelLarge,
                        color = PaperTheme.tokens.inkMuted,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PaperTheme.tokens.ink,
                        trackColor = PaperTheme.tokens.surfaceMuted,
                    )
                }

                TaskState.RUNNING -> PaperProgress(
                    progress = task.progress.toFloat(),
                    label = stringResource(R.string.task_running),
                )

                TaskState.SUCCEEDED,
                TaskState.FAILED,
                TaskState.CANCELLED -> Unit
            }
            if (task.state == TaskState.FAILED) {
                Text(
                    text = downloadFailureMessage(task.failureCode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.danger,
                )
            }
            if (task.kind == TaskKind.DOWNLOAD && task.state == TaskState.SUCCEEDED) {
                Text(
                    text = stringResource(R.string.task_clear_keeps_file),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
        }
        if (actionFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.task_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = PaperTheme.tokens.danger,
            )
        }
        if (availableActions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onClick != null) {
                    PaperSecondaryButton(
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.task_open_paper)) }
                }
                if (DownloadTaskAction.CANCEL in availableActions) {
                    PaperSecondaryButton(
                        onClick = onCancel,
                        enabled = !acting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.task_cancel_action)) }
                }
                if (DownloadTaskAction.RETRY in availableActions) {
                    PaperPrimaryButton(
                        onClick = onRetry,
                        enabled = !acting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.task_retry_action)) }
                }
                if (DownloadTaskAction.REMOVE in availableActions) {
                    PaperSecondaryButton(
                        onClick = onRemove,
                        enabled = !acting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.task_remove_action)) }
                }
            }
        }
    }
}

internal enum class DownloadTaskAction {
    CANCEL,
    RETRY,
    REMOVE,
}

internal fun downloadTaskActions(task: PaperTask): Set<DownloadTaskAction> {
    if (task.kind != TaskKind.DOWNLOAD) return emptySet()
    return when (task.state) {
        TaskState.QUEUED, TaskState.RUNNING -> setOf(DownloadTaskAction.CANCEL)
        TaskState.FAILED, TaskState.CANCELLED -> setOf(DownloadTaskAction.RETRY, DownloadTaskAction.REMOVE)
        TaskState.SUCCEEDED -> setOf(DownloadTaskAction.REMOVE)
    }
}

@Composable
private fun taskStateLabel(state: TaskState): String = stringResource(
    when (state) {
        TaskState.QUEUED -> R.string.task_queued
        TaskState.RUNNING -> R.string.task_running
        TaskState.SUCCEEDED -> R.string.task_succeeded
        TaskState.FAILED -> R.string.task_failed
        TaskState.CANCELLED -> R.string.task_cancelled
    },
)

@Composable
private fun taskStateColor(state: TaskState) = when (state) {
    TaskState.SUCCEEDED -> PaperTheme.tokens.success
    TaskState.FAILED -> PaperTheme.tokens.danger
    TaskState.CANCELLED -> PaperTheme.tokens.inkMuted
    TaskState.QUEUED -> PaperTheme.tokens.warning
    TaskState.RUNNING -> PaperTheme.tokens.primary
}
