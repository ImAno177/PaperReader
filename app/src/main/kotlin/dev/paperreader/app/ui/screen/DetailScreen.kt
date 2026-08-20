package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.ManifestationUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.task.DeleteDownloadResult
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.task.PaperTask
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: LoadState<PaperUi?>,
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    themePreset: PaperThemePreset = PaperThemePreset.NEOBRUTALISM,
    themeKey: String = themePreset.storageKey,
    themeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
    downloadTasks: List<PaperTask> = emptyList(),
    requestingManifestations: Set<String> = emptySet(),
    failedManifestations: Set<String> = emptySet(),
    onBack: () -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onRepairSavedPaper: (String) -> Unit = {},
    onRequestDownload: (String) -> Unit = {},
    onGetDownloadedPaper: suspend (String) -> DownloadedPaper? = { null },
    onLoadReadablePaper: suspend (String) -> ReadablePaperResult = {
        ReadablePaperResult.Unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
    },
    onDeleteDownload: suspend (String) -> DeleteDownloadResult = { DeleteDownloadResult.NotFound },
    onRemove: suspend () -> RemovePaperResult,
    onSetCollections: suspend (Set<Long>) -> SetPaperCollectionsResult = {
        SetPaperCollectionsResult.PaperNotFound
    },
    onRemoved: () -> Unit,
) {
    var showRemovalDialog by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var removalErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var showCollectionDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        bottomBar = {
            val paper = (state as? LoadState.Ready)?.value
            val manifestation = paper?.manifestations?.firstOrNull {
                it.source.equals("arxiv", ignoreCase = true)
            }
            if (paper != null && manifestation != null) {
                Surface(color = PaperTheme.tokens.canvas, tonalElevation = 2.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        MobileReadAction(
                            workId = paper.id,
                            paperTitle = paper.title,
                            themePreset = themePreset,
                            themeKey = themeKey,
                            themeMode = themeMode,
                            manifestationId = manifestation.id,
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        PaperIcon(PaperIconKey.BACK, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { PaperAppBarTitle(stringResource(R.string.paper_detail_title)) },
                actions = {
                    val paper = (state as? LoadState.Ready)?.value
                    if (paper != null) {
                        PaperActionsMenu(
                            paper = paper,
                            onManageCollections = { showCollectionDialog = true },
                            onRequestRemove = {
                                removalErrorRes = null
                                showRemovalDialog = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PaperTheme.tokens.canvas,
                    titleContentColor = PaperTheme.tokens.ink,
                    navigationIconContentColor = PaperTheme.tokens.ink,
                    actionIconContentColor = PaperTheme.tokens.ink,
                ),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        when (state) {
            LoadState.Loading -> PaperStatePanel(
                title = stringResource(R.string.library_loading_title),
                body = stringResource(R.string.library_loading_body),
                loading = true,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            LoadState.Failed -> PaperStatePanel(
                title = stringResource(R.string.data_error_title),
                body = stringResource(R.string.data_error_body),
                icon = PaperIconKey.ERROR,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            is LoadState.Ready -> if (state.value == null) {
                PaperStatePanel(
                    title = stringResource(R.string.paper_not_found_title),
                    body = stringResource(R.string.paper_not_found_body),
                    icon = PaperIconKey.ERROR,
                    actionLabel = stringResource(R.string.back),
                    onAction = onBack,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                PaperDetailContent(
                    paper = state.value,
                    assignedCollections = (collections as? LoadState.Ready)?.value
                        ?.filter { it.id in state.value.collectionIds }
                        .orEmpty(),
                    themePreset = themePreset,
                    themeKey = themeKey,
                    themeMode = themeMode,
                    padding = padding,
                    downloadTasks = downloadTasks,
                    requestingManifestations = requestingManifestations,
                    failedManifestations = failedManifestations,
                    onStatusChange = onStatusChange,
                    onRepairSavedPaper = onRepairSavedPaper,
                    onRequestDownload = onRequestDownload,
                    onGetDownloadedPaper = onGetDownloadedPaper,
                    onLoadReadablePaper = onLoadReadablePaper,
                    onDeleteDownload = onDeleteDownload,
                )
            }
        }
    }
    if (showRemovalDialog) {
        AlertDialog(
            onDismissRequest = { if (!removing) showRemovalDialog = false },
            title = { Text(stringResource(R.string.remove_paper_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_paper_body))
                    removalErrorRes?.let { errorRes ->
                        Text(stringResource(errorRes), color = PaperTheme.tokens.danger)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRemovalDialog = false },
                    enabled = !removing,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !removing,
                    onClick = {
                        scope.launch {
                            removing = true
                            removalErrorRes = null
                            try {
                                when (onRemove()) {
                                    RemovePaperResult.Removed,
                                    RemovePaperResult.NotFound,
                                    -> {
                                        showRemovalDialog = false
                                        onRemoved()
                                    }

                                    RemovePaperResult.HasLocalArtifacts -> {
                                        removalErrorRes = R.string.remove_paper_has_local_files
                                    }

                                    RemovePaperResult.HasActiveTasks -> {
                                        removalErrorRes = R.string.remove_paper_has_active_tasks
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                removalErrorRes = R.string.remove_paper_failed
                            } finally {
                                removing = false
                            }
                        }
                    },
                ) {
                    if (removing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.remove_paper_confirm))
                }
            },
        )
    }
    val paper = (state as? LoadState.Ready)?.value
    if (showCollectionDialog && paper != null) {
        CollectionAssignmentDialog(
            paper = paper,
            collections = collections,
            onDismiss = { showCollectionDialog = false },
            onSave = onSetCollections,
        )
    }
}


@Composable
private fun PaperDetailContent(
    paper: PaperUi,
    assignedCollections: List<PaperCollectionUi>,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    padding: PaddingValues,
    downloadTasks: List<PaperTask>,
    requestingManifestations: Set<String>,
    failedManifestations: Set<String>,
    onStatusChange: (ReadingStatus) -> Unit,
    onRepairSavedPaper: (String) -> Unit,
    onRequestDownload: (String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> DownloadedPaper?,
    onLoadReadablePaper: suspend (String) -> ReadablePaperResult,
    onDeleteDownload: suspend (String) -> DeleteDownloadResult,
) {
    var abstractExpanded by rememberSaveable(paper.id) { mutableStateOf(false) }
    LaunchedEffect(paper.id, paper.manifestations.isEmpty()) {
        if (paper.manifestations.isEmpty()) onRepairSavedPaper(paper.id)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PaperMetaRow(
                source = paper.sources.joinToString { it.displayProviderName() }.ifBlank { null },
                year = paper.publishedDate?.year?.toString(),
                identifier = paper.primaryIdentifier?.displayValue(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = paper.title,
                style = MaterialTheme.typography.headlineSmall,
                color = PaperTheme.tokens.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = paper.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
                style = MaterialTheme.typography.bodyLarge,
                color = PaperTheme.tokens.inkMuted,
            )
            if (assignedCollections.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(assignedCollections, key = PaperCollectionUi::id) { collection ->
                        PaperLabel(collection.name)
                    }
                }
            }
            if (paper.subjects.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paper.subjects) { subject -> PaperLabel(subject) }
                }
            }
        }
        val primaryReadableManifestation = paper.manifestations.firstOrNull {
            it.source.equals("arxiv", ignoreCase = true)
        }
        item {
            PaperSurface {
                PaperSectionHeader(
                    title = stringResource(R.string.abstract_title),
                    action = if (paper.abstractText != null && paper.abstractText.length > 600) {
                        {
                            TextButton(onClick = { abstractExpanded = !abstractExpanded }) {
                                Text(
                                    stringResource(
                                        if (abstractExpanded) R.string.abstract_collapse else R.string.abstract_expand,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = paper.abstractText ?: stringResource(R.string.abstract_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (paper.abstractText == null) PaperTheme.tokens.inkMuted else PaperTheme.tokens.ink,
                    maxLines = if (abstractExpanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item { ReadingStatusCard(paper.status, onStatusChange) }
        item { PaperSectionHeader(stringResource(R.string.manifestations_title)) }
        if (paper.manifestations.isEmpty()) {
            item {
                Text(stringResource(R.string.manifestations_missing), color = PaperTheme.tokens.inkMuted)
            }
        } else {
            items(paper.manifestations, key = ManifestationUi::id) { manifestation ->
                ManifestationCard(
                    workId = paper.id,
                    paperTitle = paper.title,
                    themePreset = themePreset,
                    themeKey = themeKey,
                    themeMode = themeMode,
                    manifestation = manifestation,
                    task = downloadTasks.firstOrNull { it.targetKey == manifestation.id },
                    requesting = manifestation.id in requestingManifestations,
                    requestFailed = manifestation.id in failedManifestations,
                    showMobileReadAction = manifestation.id != primaryReadableManifestation?.id,
                    onRequestDownload = { onRequestDownload(manifestation.id) },
                    onGetDownloadedPaper = { onGetDownloadedPaper(manifestation.id) },
                    onLoadReadablePaper = { onLoadReadablePaper(manifestation.id) },
                    onDeleteDownload = { onDeleteDownload(manifestation.id) },
                )
            }
        }
        if (paper.identifiers.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.identifiers_title)) }
            item {
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        paper.identifiers.joinToString(separator = "\n") { it.displayValue() },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingStatusCard(
    status: ReadingStatus,
    onStatusChange: (ReadingStatus) -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.reading_status_title),
            style = MaterialTheme.typography.labelLarge,
            color = PaperTheme.tokens.inkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReadingStatus.entries.forEach { option ->
                val selected = option == status
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .selectable(
                            selected = selected,
                            onClick = { if (!selected) onStatusChange(option) },
                            role = Role.RadioButton,
                        ),
                    shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
                    color = if (selected) PaperTheme.tokens.primaryContainer else PaperTheme.tokens.surface,
                    contentColor = if (selected) PaperTheme.tokens.onPrimaryContainer else PaperTheme.tokens.inkMuted,
                    border = BorderStroke(
                        PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp),
                        if (selected) PaperTheme.tokens.border else PaperTheme.tokens.border.copy(alpha = 0.55f),
                    ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = readingStatusLabel(option),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = stringResource(
    when (status) {
        ReadingStatus.UNREAD -> R.string.status_unread
        ReadingStatus.READING -> R.string.status_reading
        ReadingStatus.FINISHED -> R.string.status_finished
    },
)
