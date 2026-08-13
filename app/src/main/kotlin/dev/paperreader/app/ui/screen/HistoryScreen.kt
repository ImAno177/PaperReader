package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperProgress
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: LoadState<List<ReadingHistoryUi>>,
    onOpenPaper: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.history_title)) },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        when (state) {
            LoadState.Loading -> PaperStatePanel(
                title = stringResource(R.string.history_loading_title),
                body = stringResource(R.string.history_loading_body),
                loading = true,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            LoadState.Failed -> PaperStatePanel(
                title = stringResource(R.string.data_error_title),
                body = stringResource(R.string.data_error_body),
                icon = PaperIconKey.ERROR,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            is LoadState.Ready -> if (state.value.isEmpty()) {
                PaperStatePanel(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    icon = PaperIconKey.HISTORY,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.value, key = ReadingHistoryUi::workId) { entry ->
                        HistoryRow(entry, onOpenPaper, onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: ReadingHistoryUi,
    onOpenPaper: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    PaperSurface(onClick = { onOpenPaper(entry.workId) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(entry.lastReadAt.toEnglishDisplayDateTime(), color = PaperTheme.tokens.inkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperLabel(
                        pluralStringResource(
                            R.plurals.reading_session_count,
                            entry.sessionCount,
                            entry.sessionCount,
                        ),
                    )
                    PaperLabel(stringResource(R.string.reading_minutes, entry.totalReadDuration.toMinutes()))
                }
            }
            IconButton(onClick = { onRemove(entry.workId) }, modifier = Modifier.size(48.dp)) {
                PaperIcon(PaperIconKey.DELETE, contentDescription = stringResource(R.string.remove_history))
            }
        }
        if (entry.progression > 0f) {
            Spacer(Modifier.height(10.dp))
            PaperProgress(entry.progression)
        }
    }
}
