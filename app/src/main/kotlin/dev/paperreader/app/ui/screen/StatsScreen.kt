package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperStatsSnapshot
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.buildStatsSnapshot
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.task.PaperTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    library: LoadState<List<PaperUi>>,
    history: LoadState<List<ReadingHistoryUi>>,
    collections: LoadState<List<PaperCollectionUi>>,
    tasks: LoadState<List<PaperTask>>,
    savedSearches: LoadState<List<SavedSearchFeed>>,
    onBack: () -> Unit,
) {
    val failed = library is LoadState.Failed ||
        history is LoadState.Failed ||
        collections is LoadState.Failed ||
        tasks is LoadState.Failed ||
        savedSearches is LoadState.Failed
    val loading = library is LoadState.Loading ||
        history is LoadState.Loading ||
        collections is LoadState.Loading ||
        tasks is LoadState.Loading ||
        savedSearches is LoadState.Loading
    val snapshot = if (!failed && !loading) {
        buildStatsSnapshot(
            library = (library as LoadState.Ready).value,
            history = (history as LoadState.Ready).value,
            collectionCount = (collections as LoadState.Ready).value.size,
            tasks = (tasks as LoadState.Ready).value,
            savedSearchCount = (savedSearches as LoadState.Ready).value.size,
        )
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        PaperIcon(PaperIconKey.BACK, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        when {
            loading -> PaperStatePanel(
                title = stringResource(R.string.stats_loading),
                loading = true,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            failed -> PaperStatePanel(
                title = stringResource(R.string.stats_unavailable),
                body = stringResource(R.string.data_error_body),
                icon = PaperIconKey.ERROR,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            snapshot != null -> StatsContent(snapshot, padding)
        }
    }
}

@Composable
private fun StatsContent(snapshot: PaperStatsSnapshot, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StatsLead(snapshot)
        }
        item {
            StatsMetricSection(
                title = stringResource(R.string.stats_overview_section),
                icon = PaperIconKey.LIBRARY,
                metrics = listOf(
                    StatsMetric(stringResource(R.string.stats_saved_papers), snapshot.libraryCount.toString()),
                    StatsMetric(stringResource(R.string.stats_reading), snapshot.readingCount.toString()),
                    StatsMetric(stringResource(R.string.stats_finished), snapshot.finishedCount.toString()),
                ),
            )
        }
        item {
            StatsMetricSection(
                title = stringResource(R.string.stats_reading_section),
                icon = PaperIconKey.HISTORY,
                metrics = listOf(
                    StatsMetric(stringResource(R.string.stats_history_entries), snapshot.historyEntryCount.toString()),
                    StatsMetric(stringResource(R.string.stats_highlights), snapshot.annotationCount.toString()),
                ),
            )
        }
        item {
            StatsMetricSection(
                title = stringResource(R.string.stats_local_section),
                icon = PaperIconKey.PDF,
                metrics = listOf(
                    StatsMetric(stringResource(R.string.stats_local_documents), snapshot.localDocumentCount.toString()),
                    StatsMetric(stringResource(R.string.stats_collections), snapshot.collectionCount.toString()),
                    StatsMetric(stringResource(R.string.stats_pending_tasks), snapshot.pendingTaskCount.toString()),
                ),
            )
        }
        item {
            StatsMetricSection(
                title = stringResource(R.string.stats_discovery_section),
                icon = PaperIconKey.SEARCH,
                metrics = listOf(
                    StatsMetric(stringResource(R.string.stats_saved_searches), snapshot.savedSearchCount.toString()),
                    StatsMetric(stringResource(R.string.stats_unread), snapshot.unreadCount.toString()),
                ),
            )
        }
    }
}

@Composable
private fun StatsLead(snapshot: PaperStatsSnapshot) {
    PaperSurface(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.stats_read_duration),
                    style = MaterialTheme.typography.labelLarge,
                    color = PaperTheme.tokens.inkMuted,
                )
                Text(
                    text = stringResource(R.string.stats_minutes_value, snapshot.totalReadDuration.toMinutes()),
                    style = MaterialTheme.typography.displaySmall,
                    color = PaperTheme.tokens.ink,
                    maxLines = 1,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.saved_papers_count,
                        snapshot.libraryCount,
                        snapshot.libraryCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
            PaperIcon(
                key = PaperIconKey.HISTORY,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = PaperTheme.tokens.primary,
            )
        }
    }
}

private data class StatsMetric(val label: String, val value: String)

@Composable
private fun StatsMetricSection(
    title: String,
    icon: PaperIconKey,
    metrics: List<StatsMetric>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(
                key = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = PaperTheme.tokens.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PaperTheme.tokens.ink,
            )
        }
        PaperSurface(contentPadding = PaddingValues(0.dp)) {
            metrics.forEachIndexed { index, metric ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = metric.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperTheme.tokens.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metric.value,
                        style = MaterialTheme.typography.titleMedium,
                        color = PaperTheme.tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index < metrics.lastIndex) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = PaperTheme.tokens.border.copy(alpha = 0.24f),
                    )
                }
            }
        }
    }
}
