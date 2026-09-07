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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperSectionHeader
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PaperSectionHeader(stringResource(R.string.stats_overview_section)) }
        item {
            StatsMetricRow(
                StatsMetric(stringResource(R.string.stats_saved_papers), snapshot.libraryCount.toString()),
                StatsMetric(stringResource(R.string.stats_reading), snapshot.readingCount.toString()),
                StatsMetric(stringResource(R.string.stats_finished), snapshot.finishedCount.toString()),
            )
        }
        item { PaperSectionHeader(stringResource(R.string.stats_reading_section)) }
        item {
            StatsMetricRow(
                StatsMetric(
                    stringResource(R.string.stats_read_duration),
                    stringResource(R.string.stats_minutes_value, snapshot.totalReadDuration.toMinutes()),
                ),
                StatsMetric(stringResource(R.string.stats_history_entries), snapshot.historyEntryCount.toString()),
                StatsMetric(stringResource(R.string.stats_highlights), snapshot.annotationCount.toString()),
            )
        }
        item { PaperSectionHeader(stringResource(R.string.stats_local_section)) }
        item {
            StatsMetricRow(
                StatsMetric(stringResource(R.string.stats_local_documents), snapshot.localDocumentCount.toString()),
                StatsMetric(stringResource(R.string.stats_collections), snapshot.collectionCount.toString()),
                StatsMetric(stringResource(R.string.stats_pending_tasks), snapshot.pendingTaskCount.toString()),
            )
        }
        item { PaperSectionHeader(stringResource(R.string.stats_discovery_section)) }
        item {
            StatsMetricRow(
                StatsMetric(stringResource(R.string.stats_saved_searches), snapshot.savedSearchCount.toString()),
                StatsMetric(stringResource(R.string.stats_unread), snapshot.unreadCount.toString()),
            )
        }
    }
}

private data class StatsMetric(val label: String, val value: String)

@Composable
private fun StatsMetricRow(vararg metrics: StatsMetric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        metrics.forEach { metric ->
            PaperSurface(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        metric.value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = PaperTheme.tokens.ink,
                    )
                    Text(
                        metric.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
    }
}
