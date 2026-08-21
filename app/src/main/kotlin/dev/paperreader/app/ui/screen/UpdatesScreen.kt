package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.DownloadActionUiState
import dev.paperreader.app.ui.SavedSearchActionUiState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperProgress
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.PaperTextButton
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    tasks: LoadState<List<PaperTask>>,
    library: LoadState<List<PaperUi>> = LoadState.Loading,
    providers: ProviderManagerState,
    savedSearches: LoadState<List<SavedSearchFeed>> = LoadState.Ready(emptyList()),
    savedSearchActions: SavedSearchActionUiState = SavedSearchActionUiState(),
    actions: DownloadActionUiState = DownloadActionUiState(),
    onOpenPaper: (String) -> Unit = {},
    onRefreshSearch: (String) -> Unit = {},
    onDeleteSearch: (String) -> Unit = {},
    onMarkHitRead: (String) -> Unit = {},
    onSaveHit: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
    onRetry: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
) {
    val paperTitles = (library as? LoadState.Ready)
        ?.value
        ?.associate { it.id to it.title }
        .orEmpty()
    var pendingDeleteSearch by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeleteFeed = (savedSearches as? LoadState.Ready)
        ?.value
        ?.firstOrNull { it.search.id.value == pendingDeleteSearch }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.updates_title)) },
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
            item {
                PaperSectionHeader(title = stringResource(R.string.saved_searches_title))
            }
            when (savedSearches) {
                LoadState.Loading -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.saved_searches_loading),
                        loading = true,
                        compact = true,
                    )
                }

                LoadState.Failed -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.saved_searches_load_failed),
                        body = stringResource(R.string.data_error_body),
                        icon = PaperIconKey.ERROR,
                        compact = true,
                    )
                }

                is LoadState.Ready -> if (savedSearches.value.isEmpty()) {
                    item {
                        PaperStatePanel(
                            title = stringResource(R.string.saved_searches_empty_title),
                            body = stringResource(R.string.saved_searches_empty_body),
                            icon = PaperIconKey.BOOKMARK_ADD,
                            compact = true,
                        )
                    }
                } else {
                    savedSearches.value.forEach { feed ->
                        item(key = "saved-search-${feed.search.id.value}") {
                            SavedSearchHeader(
                                feed = feed,
                                providers = providers,
                                refreshing = feed.search.id.value in savedSearchActions.refreshingSearchIds,
                                deleting = feed.search.id.value in savedSearchActions.deletingSearchIds,
                                actionFailed = feed.search.id.value in savedSearchActions.failedSearchActionIds,
                                onRefresh = { onRefreshSearch(feed.search.id.value) },
                                onDelete = { pendingDeleteSearch = feed.search.id.value },
                            )
                        }
                        if (feed.hits.isEmpty()) {
                            item(key = "saved-search-empty-${feed.search.id.value}") {
                                PaperStatePanel(
                                    title = stringResource(
                                        if (feed.search.lastCheckedAt == null) {
                                            R.string.saved_search_not_checked_title
                                        } else {
                                            R.string.saved_search_no_results_title
                                        },
                                    ),
                                    body = stringResource(
                                        if (feed.search.lastCheckedAt == null) {
                                            R.string.saved_search_not_checked_body
                                        } else {
                                            R.string.saved_search_no_results_body
                                        },
                                    ),
                                    icon = PaperIconKey.SEARCH,
                                    compact = true,
                                )
                            }
                        } else {
                            items(
                                items = feed.hits,
                                key = { "saved-hit-${it.id.value}" },
                            ) { hit ->
                                SavedSearchHitRow(
                                    hit = hit,
                                    saving = hit.id.value in savedSearchActions.savingHitIds,
                                    actionFailed = hit.id.value in savedSearchActions.failedHitActionIds,
                                    savedWorkId = savedSearchActions.savedHitWorkIds[hit.id.value],
                                    onOpenPaper = onOpenPaper,
                                    onSave = { onSaveHit(hit.id.value) },
                                    onMarkRead = { onMarkHitRead(hit.id.value) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                PaperSectionHeader(title = stringResource(R.string.download_queue_title))
            }
            when (tasks) {
                LoadState.Loading -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.updates_title),
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

                is LoadState.Ready -> if (tasks.value.isEmpty()) {
                    item {
                        PaperStatePanel(
                            title = stringResource(R.string.download_queue_empty_title),
                            icon = PaperIconKey.SYNC,
                            compact = true,
                        )
                    }
                } else {
                    items(tasks.value, key = { it.id.value }) { task ->
                        val workId = task.workId?.value
                        TaskRow(
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
                }
            }
        }
    }
    pendingDeleteFeed?.let { feed ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSearch = null },
            title = { Text(stringResource(R.string.delete_saved_search_title)) },
            text = { Text(stringResource(R.string.delete_saved_search_body, feed.search.queryText)) },
            confirmButton = {
                PaperTextButton(
                    onClick = {
                        pendingDeleteSearch = null
                        onDeleteSearch(feed.search.id.value)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                PaperTextButton(onClick = { pendingDeleteSearch = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SavedSearchHeader(
    feed: SavedSearchFeed,
    providers: ProviderManagerState,
    refreshing: Boolean,
    deleting: Boolean,
    actionFailed: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val providerNames = feed.search.sources.joinToString { source ->
        providers.installed.firstOrNull { it.descriptor.id == source.providerId }
            ?.descriptor
            ?.displayName
            ?: source.providerId.displayProviderName()
    }
    PaperSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PaperLabel(providerNames, color = PaperTheme.tokens.secondary)
                Spacer(Modifier.height(5.dp))
                Text(
                    text = feed.search.queryText,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (feed.unreadCount > 0) {
                StatusBadge(
                    pluralStringResource(
                        R.plurals.saved_search_unread_count,
                        feed.unreadCount,
                        feed.unreadCount,
                    ),
                    color = PaperTheme.tokens.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PaperLabel(
            feed.search.lastCheckedAt?.let { checkedAt ->
                stringResource(R.string.saved_search_last_checked, checkedAt.toEnglishDisplayDateTime())
            } ?: stringResource(R.string.saved_search_never_checked),
        )
        feed.search.sources.forEach { source ->
            source.failure?.let { failure ->
                Spacer(Modifier.height(8.dp))
                Text(
                    savedSearchFailureText(
                        providerName = providers.installed
                            .firstOrNull { it.descriptor.id == source.providerId }
                            ?.descriptor
                            ?.displayName
                            ?: source.providerId.displayProviderName(),
                        kind = failure.kind,
                        retryAfter = failure.retryAfter,
                    ),
                    color = PaperTheme.tokens.danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (refreshing) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = PaperTheme.tokens.ink,
                trackColor = PaperTheme.tokens.surfaceMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperPrimaryButton(
                onClick = onRefresh,
                enabled = !refreshing && !deleting,
                modifier = Modifier.weight(1f),
            ) {
                PaperIcon(PaperIconKey.SYNC, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (refreshing) R.string.refreshing_saved_search else R.string.refresh_saved_search))
            }
            PaperSecondaryButton(
                onClick = onDelete,
                enabled = !refreshing && !deleting,
            ) {
                PaperIcon(PaperIconKey.DELETE, contentDescription = stringResource(R.string.delete_saved_search))
            }
        }
        if (actionFailed) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.saved_search_action_failed), color = PaperTheme.tokens.danger)
        }
    }
}

@Composable
private fun SavedSearchHitRow(
    hit: SavedSearchHit,
    saving: Boolean,
    actionFailed: Boolean,
    savedWorkId: String?,
    onOpenPaper: (String) -> Unit,
    onSave: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val paper = hit.paper
    val workId = hit.linkedWorkId?.value ?: savedWorkId
    PaperSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperLabel(paper.providerId.displayProviderName(), color = PaperTheme.tokens.secondary)
            if (hit.unread) {
                StatusBadge(stringResource(R.string.saved_search_new), color = PaperTheme.tokens.primary)
            }
        }
        Spacer(Modifier.height(5.dp))
        PaperLabel((paper.updatedAt ?: hit.firstSeenAt).toEnglishDisplayDateTime())
        Spacer(Modifier.height(8.dp))
        Text(
            text = paper.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            paper.authors.joinToString { it.displayName }.ifBlank { stringResource(R.string.unknown_authors) },
            color = PaperTheme.tokens.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        paper.manifestations.firstNotNullOfOrNull { it.version }?.let { version ->
            Spacer(Modifier.height(6.dp))
            PaperLabel(stringResource(R.string.version_label, version.removePrefix("v")))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (workId == null) {
                PaperPrimaryButton(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (saving) R.string.saving_paper else R.string.save_paper))
                }
            } else {
                PaperPrimaryButton(
                    onClick = {
                        if (hit.unread) onMarkRead()
                        onOpenPaper(workId)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.open_saved_paper))
                }
            }
            if (hit.unread) {
                PaperSecondaryButton(onClick = onMarkRead, modifier = Modifier.weight(1f)) {
                    PaperIcon(PaperIconKey.MARK_READ, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.mark_update_read))
                }
            }
        }
        if (actionFailed) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.saved_search_hit_action_failed), color = PaperTheme.tokens.danger)
        }
    }
}

@Composable
private fun savedSearchFailureText(
    providerName: String,
    kind: SavedSearchFailureKind,
    retryAfter: java.time.Instant?,
): String = when (kind) {
    SavedSearchFailureKind.RATE_LIMITED -> retryAfter?.let {
        stringResource(R.string.saved_search_rate_limited_until, providerName, it.toEnglishDisplayDateTime())
    } ?: stringResource(R.string.provider_rate_limited, providerName)

    SavedSearchFailureKind.UNAVAILABLE -> stringResource(R.string.provider_unavailable, providerName)
    SavedSearchFailureKind.INVALID_RESPONSE -> stringResource(R.string.provider_invalid_response, providerName)
}

@Composable
private fun TaskRow(
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
            if (metadata.isNotEmpty()) PaperLabel(metadata.joinToString(" · "))
            if (task.state == TaskState.RUNNING) PaperProgress(task.progress.toFloat())
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
