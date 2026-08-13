package dev.paperreader.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.reader.PdfReaderActivity
import dev.paperreader.app.reader.ReadablePaperActivity
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.ManifestationUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.model.safeWebUrlOrNull
import dev.paperreader.app.ui.model.toEnglishDisplayDate
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.task.DeleteDownloadResult
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: LoadState<PaperUi?>,
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    themePreset: PaperThemePreset = PaperThemePreset.NEOBRUTALISM,
    downloadTasks: List<PaperTask> = emptyList(),
    requestingManifestations: Set<String> = emptySet(),
    failedManifestations: Set<String> = emptySet(),
    onBack: () -> Unit,
    onStatusChange: (ReadingStatus) -> Unit,
    onRequestDownload: (String) -> Unit = {},
    onGetDownloadedPaper: suspend (String) -> DownloadedPaper? = { null },
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
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
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
                icon = Icons.Outlined.ErrorOutline,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            is LoadState.Ready -> if (state.value == null) {
                PaperStatePanel(
                    title = stringResource(R.string.paper_not_found_title),
                    body = stringResource(R.string.paper_not_found_body),
                    icon = Icons.Outlined.ErrorOutline,
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
                    padding = padding,
                    downloadTasks = downloadTasks,
                    requestingManifestations = requestingManifestations,
                    failedManifestations = failedManifestations,
                    onStatusChange = onStatusChange,
                    onRequestDownload = onRequestDownload,
                    onGetDownloadedPaper = onGetDownloadedPaper,
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
private fun PaperActionsMenu(
    paper: PaperUi,
    onManageCollections: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        paper.primaryIdentifier?.let { identifier ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_identifier)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            paper.title,
                            identifier.displayValue(),
                        ),
                    )
                    expanded = false
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.manage_collections)) },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            onClick = {
                expanded = false
                onManageCollections()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_paper), color = PaperTheme.tokens.danger) },
            leadingIcon = {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = PaperTheme.tokens.danger)
            },
            onClick = {
                expanded = false
                onRequestRemove()
            },
        )
    }
}

@Composable
private fun CollectionAssignmentDialog(
    paper: PaperUi,
    collections: LoadState<List<PaperCollectionUi>>,
    onDismiss: () -> Unit,
    onSave: suspend (Set<Long>) -> SetPaperCollectionsResult,
) {
    val available = (collections as? LoadState.Ready)?.value.orEmpty()
    var selectedIds by rememberSaveable(
        paper.id,
        stateSaver = listSaver(
            save = { ids -> ids.sorted() },
            restore = { ids -> ids.toSet() },
        ),
    ) { mutableStateOf(paper.collectionIds) }
    var saving by remember { mutableStateOf(false) }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(collections) {
        if (collections is LoadState.Ready) {
            selectedIds = selectedIds.intersect(available.mapTo(mutableSetOf(), PaperCollectionUi::id))
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.paper_collections_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (collections) {
                    LoadState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.collections_loading))
                    }

                    LoadState.Failed -> Text(
                        stringResource(R.string.paper_collections_load_failed),
                        color = PaperTheme.tokens.danger,
                    )

                    is LoadState.Ready -> if (available.isEmpty()) {
                        Text(
                            stringResource(R.string.paper_collections_empty),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        ) {
                            available.forEach { collection ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(
                                            selected = collection.id in selectedIds,
                                            role = Role.Checkbox,
                                            onClick = {
                                                selectedIds = if (collection.id in selectedIds) {
                                                    selectedIds - collection.id
                                                } else {
                                                    selectedIds + collection.id
                                                }
                                            },
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(
                                        checked = collection.id in selectedIds,
                                        onCheckedChange = null,
                                    )
                                    Text(collection.name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                errorRes?.let { value ->
                    Text(stringResource(value), color = PaperTheme.tokens.danger)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = collections is LoadState.Ready && available.isNotEmpty() && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        errorRes = null
                        try {
                            when (onSave(selectedIds)) {
                                SetPaperCollectionsResult.Updated -> onDismiss()
                                SetPaperCollectionsResult.PaperNotFound,
                                is SetPaperCollectionsResult.CollectionNotFound,
                                -> errorRes = R.string.paper_collections_save_failed
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            errorRes = R.string.paper_collections_save_failed
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.save))
            }
        },
    )
}

@Composable
private fun PaperDetailContent(
    paper: PaperUi,
    assignedCollections: List<PaperCollectionUi>,
    themePreset: PaperThemePreset,
    padding: PaddingValues,
    downloadTasks: List<PaperTask>,
    requestingManifestations: Set<String>,
    failedManifestations: Set<String>,
    onStatusChange: (ReadingStatus) -> Unit,
    onRequestDownload: (String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> DownloadedPaper?,
    onDeleteDownload: suspend (String) -> DeleteDownloadResult,
) {
    var abstractExpanded by rememberSaveable(paper.id) { mutableStateOf(false) }
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
        }
        item { ReadingStatusCard(paper.status, onStatusChange) }
        item {
            PaperSurface {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = PaperTheme.tokens.warning)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val hasLocalCopy = paper.manifestations.any { it.localCopy != null }
                        val isImportedLocalPdf = paper.manifestations.any {
                            it.localCopy != null && it.source == LOCAL_PDF_SOURCE_ID
                        }
                        Text(
                            stringResource(
                                if (hasLocalCopy) R.string.reader_local_ready_title
                                else R.string.reader_unavailable_title,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                if (isImportedLocalPdf) R.string.reader_imported_local_ready_body
                                else if (hasLocalCopy) R.string.reader_local_ready_body
                                else R.string.reader_unavailable_body,
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                }
            }
        }
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
                    manifestation = manifestation,
                    task = downloadTasks.firstOrNull { it.targetKey == manifestation.id },
                    requesting = manifestation.id in requestingManifestations,
                    requestFailed = manifestation.id in failedManifestations,
                    onRequestDownload = { onRequestDownload(manifestation.id) },
                    onGetDownloadedPaper = { onGetDownloadedPaper(manifestation.id) },
                    onDeleteDownload = { onDeleteDownload(manifestation.id) },
                )
            }
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
        item { PaperSectionHeader(stringResource(R.string.identifiers_title)) }
        items(paper.identifiers) { identifier ->
            PaperSurface(contentPadding = PaddingValues(12.dp)) {
                Text(identifier.displayValue(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ReadingStatusCard(
    status: ReadingStatus,
    onStatusChange: (ReadingStatus) -> Unit,
) {
    PaperSurface {
        PaperSectionHeader(stringResource(R.string.reading_status_title))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReadingStatus.entries.forEach { option ->
                val selected = option == status
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(readingStatusLabel(option), style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

@Composable
private fun ManifestationCard(
    workId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
    manifestation: ManifestationUi,
    task: PaperTask?,
    requesting: Boolean,
    requestFailed: Boolean,
    onRequestDownload: () -> Unit,
    onGetDownloadedPaper: suspend () -> DownloadedPaper?,
    onDeleteDownload: suspend () -> DeleteDownloadResult,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landingPageUrl = manifestation.landingPageUrl?.safeWebUrlOrNull()
    val pdfUrl = manifestation.pdfUrl?.safeWebUrlOrNull()
    var opening by remember(manifestation.id) { mutableStateOf(false) }
    var deleting by remember(manifestation.id) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(manifestation.id) { mutableStateOf(false) }
    var actionErrorRes by rememberSaveable(manifestation.id) { mutableStateOf<Int?>(null) }
    val active = requesting || task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
    val downloadFailed = requestFailed || task?.state == TaskState.FAILED
    val isImportedLocalPdf = manifestation.source == LOCAL_PDF_SOURCE_ID
    val supportsMobileReading = manifestation.source.equals("arxiv", ignoreCase = true)
    val openLocalCopy: () -> Unit = {
        scope.launch {
            opening = true
            actionErrorRes = null
            try {
                val downloaded = onGetDownloadedPaper()
                if (downloaded == null) {
                    actionErrorRes = if (isImportedLocalPdf) {
                        R.string.imported_local_pdf_missing
                    } else {
                        R.string.local_pdf_missing
                    }
                } else {
                    openDownloadedPdf(
                        context = context,
                        downloadedPaper = downloaded,
                        workId = workId,
                        paperTitle = paperTitle,
                        themePreset = themePreset,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionErrorRes = R.string.open_local_pdf_failed
            } finally {
                opening = false
            }
        }
    }
    val localCopyButtonLabel = stringResource(
        if (opening) R.string.opening_pdf
        else if (isImportedLocalPdf) R.string.open_imported_local_pdf
        else R.string.open_downloaded_pdf,
    )
    val localCopyButtonContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(Icons.Outlined.OfflinePin, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(localCopyButtonLabel)
    }
    PaperSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (isImportedLocalPdf) stringResource(R.string.manifestation_imported_pdf)
                    else manifestationTypeLabel(manifestation.type),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(manifestation.source.displayProviderName(), color = PaperTheme.tokens.inkMuted)
                manifestation.version?.let {
                    Text(stringResource(R.string.version_label, it), color = PaperTheme.tokens.inkMuted)
                }
                manifestation.publishedDate?.let { date ->
                    Text(date.toEnglishDisplayDate(), color = PaperTheme.tokens.inkMuted)
                }
                StatusBadge(
                    text = manifestation.license ?: stringResource(R.string.license_unknown),
                    color = if (manifestation.license == null) PaperTheme.tokens.warning else PaperTheme.tokens.success,
                )
                if (supportsMobileReading) {
                    StatusBadge(
                        text = stringResource(R.string.mobile_reading_available),
                        color = PaperTheme.tokens.primary,
                    )
                }
                manifestation.localCopy?.let { localCopy ->
                    StatusBadge(
                        text = stringResource(
                            if (isImportedLocalPdf) R.string.local_file_size else R.string.downloaded_file_size,
                            formatFileSize(localCopy.byteLength),
                        ),
                        color = PaperTheme.tokens.success,
                    )
                }
            }
        }
        if (active) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (task?.state == TaskState.RUNNING) {
                        stringResource(R.string.download_running, (task.progress * 100).toInt())
                    } else {
                        stringResource(R.string.download_queued)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = PaperTheme.tokens.inkMuted,
                )
                if (task?.state == TaskState.RUNNING) {
                    LinearProgressIndicator(
                        progress = { task.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (downloadFailed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = downloadFailureMessage(task?.failureCode),
                color = PaperTheme.tokens.danger,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        actionErrorRes?.let { errorRes ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(errorRes), color = PaperTheme.tokens.danger)
        }
        if (supportsMobileReading || landingPageUrl != null || pdfUrl != null || manifestation.localCopy != null) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (supportsMobileReading) {
                    PaperPrimaryButton(
                        onClick = {
                            context.startActivity(
                                ReadablePaperActivity.createIntent(
                                    context = context,
                                    workId = WorkId(workId),
                                    manifestationId = ManifestationId(manifestation.id),
                                    title = paperTitle,
                                    themePreset = themePreset,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.read_mobile_version))
                    }
                }
                if (manifestation.localCopy != null) {
                    if (supportsMobileReading) {
                        PaperSecondaryButton(
                            enabled = !opening && !deleting,
                            onClick = openLocalCopy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = localCopyButtonContent,
                        )
                    } else {
                        PaperPrimaryButton(
                            enabled = !opening && !deleting,
                            onClick = openLocalCopy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = localCopyButtonContent,
                        )
                    }
                    PaperSecondaryButton(
                        enabled = !opening && !deleting,
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.delete_local_copy))
                    }
                } else if (pdfUrl != null) {
                    val downloadAction = {
                        actionErrorRes = null
                        onRequestDownload()
                    }
                    val downloadContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(
                                when {
                                    active -> R.string.download_in_progress
                                    downloadFailed -> R.string.retry_pdf_download
                                    else -> R.string.download_pdf
                                },
                            ),
                        )
                    }
                    if (supportsMobileReading) {
                        PaperSecondaryButton(
                            enabled = !active,
                            onClick = downloadAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = downloadContent,
                        )
                    } else {
                        PaperPrimaryButton(
                            enabled = !active,
                            onClick = downloadAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = downloadContent,
                        )
                    }
                }
                pdfUrl?.let { url ->
                    PaperSecondaryButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.open_pdf_source))
                    }
                }
                landingPageUrl?.let { url ->
                    PaperSecondaryButton(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.open_landing_page))
                    }
                }
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_local_copy_title)) },
            text = {
                Text(
                    stringResource(
                        if (isImportedLocalPdf) R.string.delete_imported_local_copy_body
                        else R.string.delete_local_copy_body,
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, enabled = !deleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            actionErrorRes = null
                            try {
                                when (onDeleteDownload()) {
                                    DeleteDownloadResult.Deleted,
                                    DeleteDownloadResult.NotFound,
                                    -> showDeleteDialog = false

                                    DeleteDownloadResult.ActiveTask -> {
                                        actionErrorRes = R.string.delete_download_active
                                        showDeleteDialog = false
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                actionErrorRes = R.string.delete_local_copy_failed
                                showDeleteDialog = false
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

private fun openDownloadedPdf(
    context: android.content.Context,
    downloadedPaper: DownloadedPaper,
    workId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
) {
    context.startActivity(
        PdfReaderActivity.createIntent(
            context = context,
            downloadedPaper = downloadedPaper,
            workId = WorkId(workId),
            title = paperTitle,
            themePreset = themePreset,
        ),
    )
}

private fun formatFileSize(byteLength: Long): String = when {
    byteLength >= 1024L * 1024L -> String.format(Locale.ENGLISH, "%.1f MB", byteLength / (1024.0 * 1024.0))
    byteLength >= 1024L -> String.format(Locale.ENGLISH, "%.1f KB", byteLength / 1024.0)
    else -> "$byteLength B"
}

@Composable
internal fun downloadFailureMessage(failureCode: String?): String = when {
    failureCode == "PDF_TOO_LARGE" -> stringResource(R.string.download_failed_too_large)
    failureCode == "INVALID_PDF" -> stringResource(R.string.download_failed_invalid_pdf)
    failureCode == "PDF_UNAVAILABLE" -> stringResource(R.string.download_failed_unavailable)
    failureCode == "HTTP_401" -> stringResource(R.string.download_failed_auth_required)
    failureCode == "HTTP_403" -> stringResource(R.string.download_failed_forbidden)
    failureCode in setOf("HTTP_404", "HTTP_410") -> stringResource(R.string.download_failed_missing)
    failureCode?.endsWith("_RETRY_EXHAUSTED") == true -> stringResource(R.string.download_failed_retry_exhausted)
    failureCode?.startsWith("HTTP_") == true -> stringResource(
        R.string.download_failed_http,
        failureCode.removePrefix("HTTP_"),
    )
    else -> stringResource(R.string.download_failed_generic)
}

@Composable
private fun manifestationTypeLabel(type: ManifestationType): String = stringResource(
    when (type) {
        ManifestationType.PREPRINT -> R.string.manifestation_preprint
        ManifestationType.ACCEPTED_MANUSCRIPT -> R.string.manifestation_accepted
        ManifestationType.VERSION_OF_RECORD -> R.string.manifestation_record
        ManifestationType.OTHER -> R.string.manifestation_other
    },
)
