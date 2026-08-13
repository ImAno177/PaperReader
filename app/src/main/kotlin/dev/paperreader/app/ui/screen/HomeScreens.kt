package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.DownloadActionUiState
import dev.paperreader.app.ui.ProviderFailureKind
import dev.paperreader.app.ui.ProviderSearchFailure
import dev.paperreader.app.ui.SearchUiState
import dev.paperreader.app.ui.SavedSearchActionUiState
import dev.paperreader.app.ui.SavedSearchActionKey
import dev.paperreader.app.ui.SavedSearchCreateFailure
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperProgress
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.LibrarySortOrder
import dev.paperreader.app.ui.model.LibraryStatusFilter
import dev.paperreader.app.ui.model.MetadataBackupFailure
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.MetadataRestoreIssueUi
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.model.filterAndSortLibrary
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.paperThemeTokens
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.MAX_LOCAL_PDF_TITLE_LENGTH
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.domain.normalizeSavedSearchQuery
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LoadState<List<PaperUi>>,
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    onOpenPaper: (String) -> Unit,
    onDiscover: () -> Unit,
) {
    var grid by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf(LibraryStatusFilter.ALL) }
    var selectedCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sortOrder by rememberSaveable { mutableStateOf(LibrarySortOrder.RECENTLY_SAVED) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val papers = (state as? LoadState.Ready)?.value.orEmpty()
    LaunchedEffect(collections, selectedCollectionId) {
        if (
            collections is LoadState.Ready &&
            selectedCollectionId != null &&
            collections.value.none { it.id == selectedCollectionId }
        ) {
            selectedCollectionId = null
        }
    }
    val visiblePapers = remember(papers, query, statusFilter, sortOrder, selectedCollectionId) {
        papers.filterAndSortLibrary(query, statusFilter, sortOrder, selectedCollectionId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.library_title)) },
                actions = {
                    if (papers.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                searchVisible = !searchVisible
                                if (!searchVisible) query = ""
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            PaperIcon(
                                PaperIconKey.SEARCH,
                                contentDescription = stringResource(
                                    if (searchVisible) R.string.library_search_close else R.string.library_search_open,
                                ),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }, modifier = Modifier.size(48.dp)) {
                                PaperIcon(PaperIconKey.SORT, contentDescription = stringResource(R.string.sort_library))
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                LibrarySortOrder.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(librarySortLabel(option)) },
                                        onClick = {
                                            sortOrder = option
                                            sortMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            RadioButton(selected = option == sortOrder, onClick = null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (papers.isNotEmpty()) {
                        IconButton(onClick = { grid = !grid }, modifier = Modifier.size(48.dp)) {
                            PaperIcon(
                                key = if (grid) PaperIconKey.LIST else PaperIconKey.GRID,
                                contentDescription = stringResource(if (grid) R.string.show_list else R.string.show_grid),
                            )
                        }
                    }
                },
                colors = topBarColors(),
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

            is LoadState.Ready -> if (state.value.isEmpty()) {
                PaperStatePanel(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                    icon = PaperIconKey.LIBRARY,
                    actionLabel = stringResource(R.string.find_paper),
                    onAction = onDiscover,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LibraryControls(
                        searchVisible = searchVisible,
                        query = query,
                        onQueryChange = { query = it },
                        statusFilter = statusFilter,
                        onStatusFilterChange = { statusFilter = it },
                        collections = collections,
                        selectedCollectionId = selectedCollectionId,
                        onCollectionChange = { selectedCollectionId = it },
                    )
                    if (visiblePapers.isEmpty()) {
                        PaperStatePanel(
                            title = stringResource(R.string.library_no_matches_title),
                            body = stringResource(R.string.library_no_matches_body),
                            icon = PaperIconKey.SEARCH,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (grid) {
                        LibraryGrid(
                            papers = visiblePapers,
                            padding = PaddingValues(0.dp),
                            onOpenPaper = onOpenPaper,
                            sectionTitle = librarySortLabel(sortOrder),
                            totalCount = papers.size,
                        )
                    } else {
                        LibraryList(
                            papers = visiblePapers,
                            padding = PaddingValues(0.dp),
                            onOpenPaper = onOpenPaper,
                            sectionTitle = librarySortLabel(sortOrder),
                            totalCount = papers.size,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryControls(
    searchVisible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    statusFilter: LibraryStatusFilter,
    onStatusFilterChange: (LibraryStatusFilter) -> Unit,
    collections: LoadState<List<PaperCollectionUi>>,
    selectedCollectionId: Long?,
    onCollectionChange: (Long?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searchVisible) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.library_search_label)) },
                leadingIcon = { PaperIcon(PaperIconKey.SEARCH, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            PaperIcon(PaperIconKey.CLOSE, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        }
        LazyRow(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryStatusFilter.entries, key = { it.name }) { option ->
                LibraryStatusButton(
                    text = libraryStatusFilterLabel(option),
                    selected = option == statusFilter,
                    onClick = { onStatusFilterChange(option) },
                )
            }
        }
        when (collections) {
            LoadState.Loading -> LibraryCollectionsStateRow(
                text = stringResource(R.string.collections_loading),
                loading = true,
            )

            LoadState.Failed -> LibraryCollectionsStateRow(
                text = stringResource(R.string.library_collections_unavailable),
                error = true,
            )

            is LoadState.Ready -> if (collections.value.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.selectableGroup(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all-collections") {
                        LibraryStatusButton(
                            text = stringResource(R.string.all_collections),
                            selected = selectedCollectionId == null,
                            onClick = { onCollectionChange(null) },
                        )
                    }
                    items(collections.value, key = PaperCollectionUi::id) { collection ->
                        LibraryStatusButton(
                            text = collection.name,
                            selected = selectedCollectionId == collection.id,
                            onClick = { onCollectionChange(collection.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionsStateRow(
    text: String,
    loading: Boolean = false,
    error: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (error) {
            PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.danger)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) PaperTheme.tokens.danger else PaperTheme.tokens.inkMuted,
        )
    }
}

@Composable
private fun LibraryStatusButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = PaperTheme.tokens
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(tokens.cornerRadius),
        color = if (selected) tokens.primaryContainer else tokens.surface,
        contentColor = if (selected) tokens.onPrimaryContainer else tokens.ink,
        border = BorderStroke(tokens.borderWidth.coerceAtLeast(1.dp), tokens.border),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LibraryList(
    papers: List<PaperUi>,
    padding: PaddingValues,
    onOpenPaper: (String) -> Unit,
    sectionTitle: String,
    totalCount: Int,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PaperSectionHeader(
                title = sectionTitle,
                action = { LibraryCountLabel(visibleCount = papers.size, totalCount = totalCount) },
            )
        }
        items(papers, key = PaperUi::id) { paper ->
            PaperListCard(paper, onClick = { onOpenPaper(paper.id) })
        }
    }
}

@Composable
private fun LibraryGrid(
    papers: List<PaperUi>,
    padding: PaddingValues,
    onOpenPaper: (String) -> Unit,
    sectionTitle: String,
    totalCount: Int,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 184.dp),
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PaperSectionHeader(
                title = sectionTitle,
                action = { LibraryCountLabel(visibleCount = papers.size, totalCount = totalCount) },
            )
        }
        items(papers, key = PaperUi::id) { paper ->
            PaperGridCard(paper, onClick = { onOpenPaper(paper.id) })
        }
    }
}

@Composable
private fun LibraryCountLabel(visibleCount: Int, totalCount: Int) {
    PaperLabel(
        if (visibleCount == totalCount) {
            pluralStringResource(R.plurals.saved_papers_count, totalCount, totalCount)
        } else {
            stringResource(R.string.library_filtered_count, visibleCount, totalCount)
        },
    )
}

@Composable
private fun librarySortLabel(sortOrder: LibrarySortOrder): String = stringResource(
    when (sortOrder) {
        LibrarySortOrder.RECENTLY_SAVED -> R.string.sort_recently_saved
        LibrarySortOrder.TITLE -> R.string.sort_title
        LibrarySortOrder.NEWEST_PUBLICATION -> R.string.sort_newest_publication
    },
)

@Composable
private fun libraryStatusFilterLabel(filter: LibraryStatusFilter): String = stringResource(
    when (filter) {
        LibraryStatusFilter.ALL -> R.string.filter_all
        LibraryStatusFilter.UNREAD -> R.string.status_unread
        LibraryStatusFilter.READING -> R.string.status_reading
        LibraryStatusFilter.FINISHED -> R.string.status_finished
    },
)

@Composable
private fun PaperListCard(paper: PaperUi, onClick: () -> Unit) {
    PaperSurface(onClick = onClick) {
        PaperMetaRow(
            source = paper.sources.joinToString { it.displayProviderName() }.ifBlank { null },
            year = paper.publishedDate?.year?.toString(),
            identifier = paper.primaryIdentifier?.displayValue(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = paper.title,
            style = MaterialTheme.typography.titleLarge,
            color = PaperTheme.tokens.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = paper.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
            color = PaperTheme.tokens.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (paper.status != ReadingStatus.UNREAD || paper.progress > 0f) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = readingStatusLabel(paper.status),
                    icon = if (paper.status == ReadingStatus.FINISHED) PaperIconKey.DONE else null,
                    color = if (paper.status == ReadingStatus.FINISHED) PaperTheme.tokens.success else PaperTheme.tokens.primary,
                )
            }
            if (paper.progress > 0f) {
                Spacer(Modifier.height(10.dp))
                PaperProgress(paper.progress)
            }
        }
    }
}

@Composable
private fun PaperGridCard(paper: PaperUi, onClick: () -> Unit) {
    PaperSurface(onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        PaperMetaRow(
            source = paper.sources.firstOrNull()?.displayProviderName(),
            year = paper.publishedDate?.year?.toString(),
            identifier = paper.primaryIdentifier?.displayValue(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = paper.title,
            style = MaterialTheme.typography.titleLarge,
            color = PaperTheme.tokens.ink,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = paper.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
            color = PaperTheme.tokens.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (paper.status != ReadingStatus.UNREAD) {
            Spacer(Modifier.height(10.dp))
            StatusBadge(
                text = readingStatusLabel(paper.status),
                icon = if (paper.status == ReadingStatus.FINISHED) PaperIconKey.DONE else null,
                color = if (paper.status == ReadingStatus.FINISHED) PaperTheme.tokens.success else PaperTheme.tokens.primary,
            )
        }
        if (paper.progress > 0f) {
            Spacer(Modifier.height(14.dp))
            PaperProgress(paper.progress)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: SearchUiState,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onSave: (SearchPaperUi) -> Unit,
    onOpenPaper: (String) -> Unit,
    savedSearches: LoadState<List<SavedSearchFeed>> = LoadState.Ready(emptyList()),
    savedSearchActions: SavedSearchActionUiState = SavedSearchActionUiState(),
    onSaveSearch: (String) -> Unit = {},
    onOpenUpdates: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf(state.submittedQuery.orEmpty()) }
    val focusManager = LocalFocusManager.current
    val submitSearch = {
        if (query.isNotBlank() && !state.running) {
            focusManager.clearFocus(force = true)
            onSearch(query)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.discover_title)) },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_label)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        query = ""
                                        onClear()
                                    },
                                ) {
                                    PaperIcon(PaperIconKey.CLOSE, contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    )
                    PaperPrimaryButton(
                        onClick = submitSearch,
                        enabled = query.isNotBlank() && !state.running,
                        modifier = Modifier.size(56.dp),
                    ) {
                        if (state.running) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            PaperIcon(
                                PaperIconKey.SEARCH,
                                contentDescription = stringResource(R.string.search_submit),
                            )
                        }
                    }
                    }
                    Text(
                        text = stringResource(R.string.discover_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
            if (state.running && state.results.isNotEmpty()) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            items(state.failures, key = ProviderSearchFailure::providerId) { failure ->
                ProviderFailureRow(failure)
            }
            state.submittedQuery?.let { submittedQuery ->
                item {
                    SavedSearchDiscoverAction(
                        query = submittedQuery,
                        searches = savedSearches,
                        providerIds = state.providerIds,
                        actions = savedSearchActions,
                        onSaveSearch = onSaveSearch,
                        onOpenUpdates = onOpenUpdates,
                    )
                }
            }
            when {
                state.submittedQuery == null -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_start_title),
                        body = stringResource(R.string.search_start_body),
                        icon = PaperIconKey.SEARCH,
                    )
                }

                state.running && state.results.isEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.searching_title),
                        body = pluralStringResource(
                            R.plurals.searching_provider_count,
                            state.providerCount,
                            state.providerCount,
                        ),
                        loading = true,
                    )
                }

                !state.running && state.results.isEmpty() && state.failures.isNotEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_failed_title),
                        body = stringResource(R.string.search_failed_body),
                        icon = PaperIconKey.ERROR,
                    )
                }

                !state.running && state.results.isEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_no_results_title),
                        body = stringResource(R.string.search_no_results_body),
                        icon = PaperIconKey.SEARCH,
                    )
                }

                else -> {
                    item {
                        PaperSectionHeader(
                            title = stringResource(R.string.search_results_title),
                            action = {
                                PaperLabel(
                                    pluralStringResource(
                                        R.plurals.search_results_count,
                                        state.results.size,
                                        state.results.size,
                                    ),
                                )
                            },
                        )
                    }
                    items(state.results, key = SearchPaperUi::key) { result ->
                        SearchResultCard(
                            result = result,
                            saving = result.key in state.savingKeys,
                            savedWorkId = state.savedWorkIds[result.key],
                            saveFailed = result.key in state.saveFailedKeys,
                            onSave = { onSave(result) },
                            onOpen = onOpenPaper,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSearchDiscoverAction(
    query: String,
    searches: LoadState<List<SavedSearchFeed>>,
    providerIds: Set<String>,
    actions: SavedSearchActionUiState,
    onSaveSearch: (String) -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val normalized = normalizeSavedSearchQuery(query).orEmpty()
    val actionKey = SavedSearchActionKey(
        queryText = normalized,
        providerIds = providerIds.map(String::lowercase).distinct().sorted(),
    )
    val existingId = (searches as? LoadState.Ready)
        ?.value
        ?.firstOrNull { feed ->
            feed.search.queryText == normalized &&
                feed.search.sources.map { it.providerId }.toSet() == providerIds
        }
        ?.search
        ?.id
        ?.value
        ?: actions.createdSearchIds[actionKey]
    val creating = actionKey in actions.creatingSearches
    PaperSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(PaperIconKey.BOOKMARK_ADD, contentDescription = null, tint = PaperTheme.tokens.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.save_search_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(
                        R.plurals.save_search_provider_count,
                        providerIds.size,
                        providerIds.size,
                    ),
                    color = PaperTheme.tokens.inkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (existingId == null) {
            PaperPrimaryButton(
                onClick = { onSaveSearch(query) },
                enabled = !creating && normalized.isNotEmpty() && providerIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(if (creating) R.string.saving_search else R.string.save_search_action))
            }
        } else {
            PaperSecondaryButton(onClick = onOpenUpdates, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.view_saved_search))
            }
        }
        actions.createFailures[actionKey]?.let { failure ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when (failure) {
                        SavedSearchCreateFailure.INVALID_QUERY -> R.string.saved_search_invalid_query
                        SavedSearchCreateFailure.NO_PROVIDERS -> R.string.saved_search_no_providers
                        SavedSearchCreateFailure.STORAGE -> R.string.saved_search_action_failed
                    },
                ),
                color = PaperTheme.tokens.danger,
            )
        }
    }
}

@Composable
private fun ProviderFailureRow(failure: ProviderSearchFailure) {
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.warning)
            Text(
                text = when (failure.kind) {
                    ProviderFailureKind.RATE_LIMITED -> failure.retryAfterMillis?.let { retryAfterMillis ->
                        stringResource(
                            R.string.provider_rate_limited_retry,
                            failure.providerName,
                            ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L),
                        )
                    } ?: stringResource(R.string.provider_rate_limited, failure.providerName)
                    ProviderFailureKind.UNAVAILABLE -> stringResource(R.string.provider_unavailable, failure.providerName)
                    ProviderFailureKind.INVALID_RESPONSE -> stringResource(R.string.provider_invalid_response, failure.providerName)
                },
                color = PaperTheme.tokens.ink,
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchPaperUi,
    saving: Boolean,
    savedWorkId: String?,
    saveFailed: Boolean,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
) {
    PaperSurface {
        PaperMetaRow(
            source = result.sources.joinToString { it.displayProviderName() },
            year = result.publishedDate?.year?.toString(),
            identifier = result.primaryIdentifier?.displayValue(),
        )
        Spacer(Modifier.height(8.dp))
        Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            result.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
            color = PaperTheme.tokens.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        result.abstractText?.let { abstractText ->
            Spacer(Modifier.height(10.dp))
            Text(abstractText, maxLines = 4, overflow = TextOverflow.Ellipsis, color = PaperTheme.tokens.inkMuted)
        }
        Spacer(Modifier.height(12.dp))
        if (savedWorkId == null) {
            PaperPrimaryButton(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(if (saving) R.string.saving_paper else R.string.save_paper))
            }
        } else {
            PaperSecondaryButton(
                onClick = { onOpen(savedWorkId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_saved_paper))
            }
        }
        if (saveFailed) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.save_failed), color = PaperTheme.tokens.danger)
        }
    }
}

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
                        body = stringResource(R.string.saved_searches_loading_body),
                        loading = true,
                    )
                }

                LoadState.Failed -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.saved_searches_load_failed),
                        body = stringResource(R.string.data_error_body),
                        icon = PaperIconKey.ERROR,
                    )
                }

                is LoadState.Ready -> if (savedSearches.value.isEmpty()) {
                    item {
                        PaperStatePanel(
                            title = stringResource(R.string.saved_searches_empty_title),
                            body = stringResource(R.string.saved_searches_empty_body),
                            icon = PaperIconKey.BOOKMARK_ADD,
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
                        body = stringResource(R.string.library_loading_body),
                        loading = true,
                    )
                }

                LoadState.Failed -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.data_error_title),
                        body = stringResource(R.string.data_error_body),
                        icon = PaperIconKey.ERROR,
                    )
                }

                is LoadState.Ready -> if (tasks.value.isEmpty()) {
                    item {
                        PaperStatePanel(
                            title = stringResource(R.string.download_queue_empty_title),
                            body = stringResource(R.string.download_queue_empty_body),
                            icon = PaperIconKey.SYNC,
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
                TextButton(
                    onClick = {
                        pendingDeleteSearch = null
                        onDeleteSearch(feed.search.id.value)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSearch = null }) {
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
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
    PaperSurface(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(taskStateLabel(task.state), color = taskStateColor(task.state))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = paperTitle ?: stringResource(
                        if (task.kind == TaskKind.DOWNLOAD) R.string.task_download else R.string.task_extraction,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (paperTitle != null) {
                    PaperLabel(
                        stringResource(
                            if (task.kind == TaskKind.DOWNLOAD) R.string.task_download else R.string.task_extraction,
                        ),
                    )
                }
                if (task.state == TaskState.RUNNING) PaperProgress(task.progress.toFloat())
                if (task.attempt > 0) {
                    PaperLabel(stringResource(R.string.task_attempt, task.attempt))
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
            if (acting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (actionFailed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.task_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = PaperTheme.tokens.danger,
            )
        }
        if (availableActions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (DownloadTaskAction.CANCEL in availableActions) {
                    PaperSecondaryButton(
                        onClick = onCancel,
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.task_cancel_action)) }
                }
                if (DownloadTaskAction.RETRY in availableActions) {
                    PaperPrimaryButton(
                        onClick = onRetry,
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.task_retry_action)) }
                }
                if (DownloadTaskAction.REMOVE in availableActions) {
                    PaperSecondaryButton(
                        onClick = onRemove,
                        enabled = !acting,
                        modifier = Modifier.weight(1f),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    selectedPreset: PaperThemePreset,
    automaticRefreshEnabled: Boolean = false,
    notificationsAvailable: Boolean = true,
    providers: ProviderManagerState = ProviderManagerState(),
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    localPdfImportState: LocalPdfImportUiState = LocalPdfImportUiState.Idle,
    backupState: MetadataBackupUiState = MetadataBackupUiState.Idle,
    onOpenAppearance: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenReadingImports: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenDataBackup: () -> Unit = {},
    onOpenSources: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.more_title)) },
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
                Text(
                    text = stringResource(R.string.more_overview_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.appearance_title),
                    supportingText = themeName(selectedPreset),
                    icon = PaperIconKey.PALETTE,
                    onClick = onOpenAppearance,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.collections_title),
                    supportingText = collectionsHubSummary(collections),
                    icon = PaperIconKey.FOLDER,
                    onClick = onOpenCollections,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.reading_and_imports_title),
                    supportingText = localPdfHubSummary(localPdfImportState),
                    icon = PaperIconKey.PDF,
                    onClick = onOpenReadingImports,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.updates_and_notifications_title),
                    supportingText = updatesHubSummary(automaticRefreshEnabled, notificationsAvailable),
                    icon = PaperIconKey.NOTIFICATIONS_ON,
                    onClick = onOpenUpdates,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.data_and_backup),
                    supportingText = backupHubSummary(backupState),
                    icon = PaperIconKey.DOWNLOAD,
                    onClick = onOpenDataBackup,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.sources_title),
                    supportingText = sourcesHubSummary(providers),
                    icon = PaperIconKey.PUBLIC,
                    onClick = onOpenSources,
                )
            }
        }
    }
}

@Composable
private fun MoreHubRow(
    title: String,
    supportingText: String,
    icon: PaperIconKey,
    onClick: () -> Unit,
) {
    PaperSurface(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
                color = PaperTheme.tokens.primaryContainer,
                contentColor = PaperTheme.tokens.onPrimaryContainer,
                border = BorderStroke(1.dp, PaperTheme.tokens.border),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PaperIcon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PaperIcon(
                PaperIconKey.FORWARD,
                contentDescription = null,
                tint = PaperTheme.tokens.inkMuted,
            )
        }
    }
}

@Composable
private fun collectionsHubSummary(collections: LoadState<List<PaperCollectionUi>>): String = when (collections) {
    LoadState.Loading -> stringResource(R.string.collections_loading)
    LoadState.Failed -> stringResource(R.string.collections_load_failed_short)
    is LoadState.Ready -> pluralStringResource(
        R.plurals.collection_count,
        collections.value.size,
        collections.value.size,
    )
}

@Composable
private fun localPdfHubSummary(state: LocalPdfImportUiState): String = stringResource(
    when (state) {
        LocalPdfImportUiState.Idle -> R.string.reading_and_imports_summary
        LocalPdfImportUiState.Preparing -> R.string.local_pdf_hub_preparing
        is LocalPdfImportUiState.Confirming -> R.string.local_pdf_hub_review
        is LocalPdfImportUiState.Importing -> R.string.local_pdf_hub_importing
        is LocalPdfImportUiState.Complete -> R.string.local_pdf_hub_complete
        is LocalPdfImportUiState.Failed -> R.string.local_pdf_hub_failed
    },
)

@Composable
private fun updatesHubSummary(enabled: Boolean, notificationsAvailable: Boolean): String = stringResource(
    when {
        enabled && !notificationsAvailable -> R.string.updates_hub_on_blocked
        enabled -> R.string.updates_hub_on
        else -> R.string.updates_hub_off
    },
)

@Composable
private fun backupHubSummary(state: MetadataBackupUiState): String = stringResource(
    when (state) {
        MetadataBackupUiState.Idle -> R.string.data_backup_summary
        MetadataBackupUiState.Exporting -> R.string.creating_metadata_backup
        MetadataBackupUiState.Inspecting -> R.string.inspecting_metadata_backup
        is MetadataBackupUiState.Preview -> R.string.backup_hub_review
        is MetadataBackupUiState.Restoring -> R.string.restoring_backup
        is MetadataBackupUiState.Exported -> R.string.backup_hub_exported
        is MetadataBackupUiState.Restored -> R.string.backup_hub_restored
        is MetadataBackupUiState.Failed -> R.string.backup_hub_failed
    },
)

@Composable
private fun sourcesHubSummary(state: ProviderManagerState): String = when {
    state.untrusted.isNotEmpty() -> pluralStringResource(
        R.plurals.untrusted_provider_count,
        state.untrusted.size,
        state.untrusted.size,
    )
    state.available.isNotEmpty() -> pluralStringResource(
        R.plurals.available_provider_count,
        state.available.size,
        state.available.size,
    )
    else -> pluralStringResource(
        R.plurals.installed_provider_count,
        state.installed.size,
        state.installed.size,
    )
}

@Composable
fun AppearanceScreen(
    selectedPreset: PaperThemePreset,
    onPresetChange: (PaperThemePreset) -> Unit,
    onBack: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    MoreBranchScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.appearance_body),
                style = MaterialTheme.typography.bodyLarge,
                color = PaperTheme.tokens.inkMuted,
            )
        }
        items(PaperThemePreset.entries) { preset ->
            ThemeChoiceCard(
                preset = preset,
                selected = preset == selectedPreset,
                dark = dark,
                onClick = { onPresetChange(preset) },
            )
        }
    }
}

@Composable
fun ReadingImportsScreen(
    state: LocalPdfImportUiState,
    onRequestImport: () -> Unit,
    onConfirmImport: (String) -> Unit,
    onDismissImport: () -> Unit,
    onOpenImportedPaper: (String) -> Unit,
    onBack: () -> Unit,
) {
    val actionsEnabled = state is LocalPdfImportUiState.Idle ||
        state is LocalPdfImportUiState.Complete || state is LocalPdfImportUiState.Failed
    MoreBranchScaffold(title = stringResource(R.string.reading_and_imports_title), onBack = onBack) {
        item {
            PaperSurface {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperIcon(
                        PaperIconKey.LIBRARY,
                        contentDescription = null,
                        tint = PaperTheme.tokens.primary,
                    )
                    Text(
                        stringResource(R.string.mobile_reading_formats_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatusBadge(
                    text = stringResource(R.string.original_pdf_available),
                    icon = PaperIconKey.DONE,
                    color = PaperTheme.tokens.success,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mobile_reading_formats_body),
                    color = PaperTheme.tokens.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mobile_reflow_planned),
                    style = MaterialTheme.typography.bodySmall,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
        }
        item {
            PaperSurface {
                Text(stringResource(R.string.local_pdf_import_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.local_pdf_import_body), color = PaperTheme.tokens.inkMuted)
                LocalPdfImportStatus(state, onOpenImportedPaper, onDismissImport)
                Spacer(Modifier.height(12.dp))
                PaperPrimaryButton(
                    onClick = onRequestImport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.UPLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.choose_local_pdf))
                }
            }
        }
    }
    LocalPdfImportDialog(state, onConfirmImport, onDismissImport)
}

@Composable
private fun LocalPdfImportDialog(
    state: LocalPdfImportUiState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val importDialog = when (state) {
        is LocalPdfImportUiState.Confirming -> state.candidate to false
        is LocalPdfImportUiState.Importing -> state.candidate to true
        else -> null
    }
    importDialog?.let { (candidate, importing) ->
        var editableTitle by rememberSaveable(candidate.importToken) { mutableStateOf(candidate.suggestedTitle) }
        val title = (state as? LocalPdfImportUiState.Importing)?.title ?: editableTitle
        val normalizedTitle = title.replace(Regex("\\s+"), " ").trim()
        val titleValid = normalizedTitle.isNotEmpty() &&
            normalizedTitle.length <= MAX_LOCAL_PDF_TITLE_LENGTH &&
            normalizedTitle.none { it.isISOControl() && !it.isWhitespace() }
        AlertDialog(
            onDismissRequest = { if (!importing) onDismiss() },
            title = { Text(stringResource(R.string.confirm_local_pdf_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(candidate.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.local_pdf_selected_size, formatImportFileSize(candidate.byteLength)),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(stringResource(R.string.local_pdf_title_explanation), color = PaperTheme.tokens.inkMuted)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { if (!importing) editableTitle = it },
                        enabled = !importing,
                        label = { Text(stringResource(R.string.local_pdf_paper_title)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.local_pdf_title_count,
                                    title.length,
                                    MAX_LOCAL_PDF_TITLE_LENGTH,
                                ),
                            )
                        },
                        isError = !titleValid,
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importing) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.importing_local_pdf))
                        }
                    }
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = { onConfirm(normalizedTitle) }, enabled = titleValid && !importing) {
                    Text(stringResource(R.string.import_local_pdf_action))
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = !importing) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun UpdatesNotificationsScreen(
    automaticRefreshEnabled: Boolean,
    notificationsAvailable: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var changing by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MoreBranchScaffold(title = stringResource(R.string.updates_branch_title), onBack = onBack) {
        item {
            PaperSurface {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.automatic_saved_search_refresh),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                if (automaticRefreshEnabled) {
                                    R.string.automatic_saved_search_refresh_on
                                } else {
                                    R.string.automatic_saved_search_refresh_off
                                },
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    if (changing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        val label = stringResource(R.string.automatic_saved_search_refresh)
                        Switch(
                            checked = automaticRefreshEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    changing = true
                                    error = false
                                    try {
                                        error = !onAutomaticRefreshChange(enabled)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = true
                                    } finally {
                                        changing = false
                                    }
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.automatic_saved_search_refresh_failed),
                        color = PaperTheme.tokens.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (automaticRefreshEnabled && !notificationsAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon(PaperIconKey.NOTIFICATIONS_OFF, contentDescription = null, tint = PaperTheme.tokens.danger)
                        Text(
                            stringResource(R.string.saved_search_notifications_blocked),
                            color = PaperTheme.tokens.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PaperSecondaryButton(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_notification_settings))
                    }
                }
            }
        }
    }
}

@Composable
fun CollectionsScreen(
    collections: LoadState<List<PaperCollectionUi>>,
    onCreateCollection: suspend (String) -> CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> DeleteCollectionResult,
    onBack: () -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var editorErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<PaperCollectionUi?>(null) }
    var deleteErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun openEditor(collection: PaperCollectionUi?) {
        editingId = collection?.id
        name = collection?.name.orEmpty()
        editorErrorRes = null
        showEditor = true
    }

    MoreBranchScaffold(title = stringResource(R.string.collections_title), onBack = onBack) {
        when (collections) {
            LoadState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.collections_loading), color = PaperTheme.tokens.inkMuted)
                }
            }
            LoadState.Failed -> item {
                Text(stringResource(R.string.collections_load_failed), color = PaperTheme.tokens.danger)
            }
            is LoadState.Ready -> if (collections.value.isEmpty()) {
                item {
                    PaperSurface { Text(stringResource(R.string.collections_empty), color = PaperTheme.tokens.inkMuted) }
                }
            } else {
                items(collections.value, key = PaperCollectionUi::id) { collection ->
                    CollectionRow(
                        collection = collection,
                        onRename = { openEditor(collection) },
                        onDelete = { deleteErrorRes = null; toDelete = collection },
                    )
                }
            }
        }
        item {
            PaperSecondaryButton(onClick = { openEditor(null) }, modifier = Modifier.fillMaxWidth()) {
                PaperIcon(PaperIconKey.ADD, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.new_collection))
            }
        }
    }

    if (showEditor) {
        val isRename = editingId != null
        AlertDialog(
            onDismissRequest = { if (!saving) showEditor = false },
            title = { Text(stringResource(if (isRename) R.string.rename_collection else R.string.new_collection)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; editorErrorRes = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        singleLine = true,
                        label = { Text(stringResource(R.string.collection_name)) },
                        supportingText = { Text(stringResource(R.string.collection_name_limit, name.length)) },
                    )
                    editorErrorRes?.let { Text(stringResource(it), color = PaperTheme.tokens.danger) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }, enabled = !saving) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            saving = true
                            editorErrorRes = null
                            try {
                                val id = editingId
                                editorErrorRes = if (id == null) {
                                    when (onCreateCollection(name)) {
                                        is CreateCollectionResult.Created -> null.also { showEditor = false }
                                        CreateCollectionResult.InvalidName -> R.string.collection_name_invalid
                                        CreateCollectionResult.NameTaken -> R.string.collection_name_taken
                                    }
                                } else {
                                    when (onRenameCollection(id, name)) {
                                        is RenameCollectionResult.Renamed -> null.also { showEditor = false }
                                        RenameCollectionResult.InvalidName -> R.string.collection_name_invalid
                                        RenameCollectionResult.NameTaken -> R.string.collection_name_taken
                                        RenameCollectionResult.NotFound -> R.string.collection_not_found
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                editorErrorRes = R.string.collection_save_failed
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
                    Text(stringResource(if (isRename) R.string.save else R.string.create_collection))
                }
            },
        )
    }

    toDelete?.let { collection ->
        AlertDialog(
            onDismissRequest = { if (!deleting) toDelete = null },
            title = { Text(stringResource(R.string.delete_collection_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_collection_body, collection.name))
                    deleteErrorRes?.let { Text(stringResource(it), color = PaperTheme.tokens.danger) }
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }, enabled = !deleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            deleteErrorRes = null
                            try {
                                when (onDeleteCollection(collection.id)) {
                                    DeleteCollectionResult.Deleted,
                                    DeleteCollectionResult.NotFound,
                                    -> toDelete = null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                deleteErrorRes = R.string.collection_delete_failed
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
                    Text(stringResource(R.string.delete), color = PaperTheme.tokens.danger)
                }
            },
        )
    }
}

@Composable
fun DataBackupScreen(
    state: MetadataBackupUiState,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissState: () -> Unit,
    onBack: () -> Unit,
) {
    val actionsEnabled = state is MetadataBackupUiState.Idle ||
        state is MetadataBackupUiState.Exported || state is MetadataBackupUiState.Restored ||
        state is MetadataBackupUiState.Failed
    MoreBranchScaffold(title = stringResource(R.string.data_and_backup), onBack = onBack) {
        item {
            PaperSurface {
                Text(stringResource(R.string.metadata_backup_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.metadata_backup_body), color = PaperTheme.tokens.inkMuted)
                MetadataBackupStatus(state, onDismissState)
                Spacer(Modifier.height(12.dp))
                PaperPrimaryButton(
                    onClick = onRequestExport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.create_metadata_backup))
                }
                Spacer(Modifier.height(8.dp))
                PaperSecondaryButton(
                    onClick = onRequestImport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.UPLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.restore_metadata_backup))
                }
            }
        }
    }
    MetadataRestoreDialog(state, onConfirmRestore, onDismissState)
}

@Composable
private fun MetadataRestoreDialog(
    state: MetadataBackupUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val preview = when (state) {
        is MetadataBackupUiState.Preview -> state.value
        is MetadataBackupUiState.Restoring -> state.preview
        else -> null
    }
    preview?.let {
        val restoring = state is MetadataBackupUiState.Restoring
        AlertDialog(
            onDismissRequest = { if (!restoring) onDismiss() },
            title = { Text(stringResource(R.string.restore_backup_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.restore_backup_created, it.createdAt.toEnglishDisplayDateTime()))
                    val counts = listOf(
                        pluralStringResource(R.plurals.restore_count_papers, it.summary.works, it.summary.works),
                        pluralStringResource(
                            R.plurals.restore_count_manifestations,
                            it.summary.manifestations,
                            it.summary.manifestations,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_collections,
                            it.summary.collections,
                            it.summary.collections,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_reading_states,
                            it.summary.readingStates,
                            it.summary.readingStates,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_history_entries,
                            it.summary.historyEntries,
                            it.summary.historyEntries,
                        ),
                        pluralStringResource(R.plurals.restore_count_bookmarks, it.summary.bookmarks, it.summary.bookmarks),
                        pluralStringResource(
                            R.plurals.restore_count_annotations,
                            it.summary.annotations,
                            it.summary.annotations,
                        ),
                    )
                    Text(counts.take(3).joinToString(" · ") + "\n" + counts.drop(3).joinToString(" · "))
                    Text(
                        stringResource(
                            R.string.restore_backup_plan,
                            it.newWorks,
                            it.mergedWorks,
                            it.skippedWorks,
                            it.skippedRecords,
                        ),
                        color = if (it.skippedWorks + it.skippedRecords == 0) {
                            PaperTheme.tokens.inkMuted
                        } else {
                            PaperTheme.tokens.danger
                        },
                    )
                    if (it.missingProviders.isNotEmpty()) {
                        val visible = it.missingProviders.take(
                            if (detailsExpanded) RESTORE_DETAIL_LIMIT else RESTORE_DETAIL_COLLAPSED_LIMIT,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.restore_backup_missing_providers,
                                it.missingProviders.size,
                                it.missingProviders.size,
                                visible.joinToString(),
                                hiddenItemSuffix(it.missingProviders.size - visible.size),
                            ),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    if (it.conflicts.isNotEmpty()) {
                        val visible = it.conflicts.take(
                            if (detailsExpanded) RESTORE_DETAIL_LIMIT else RESTORE_DETAIL_COLLAPSED_LIMIT,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.restore_backup_conflicts,
                                it.conflicts.size,
                                it.conflicts.size,
                            ),
                            color = PaperTheme.tokens.danger,
                        )
                        visible.forEach { issue ->
                            Text(
                                text = "• ${issue.toEnglishRestoreIssue()}",
                                color = PaperTheme.tokens.inkMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val hidden = it.conflicts.size - visible.size
                        if (hidden > 0) {
                            Text(
                                pluralStringResource(R.plurals.restore_backup_more_items, hidden, hidden),
                                color = PaperTheme.tokens.inkMuted,
                            )
                        }
                    }
                    if (!detailsExpanded &&
                        (it.missingProviders.size > RESTORE_DETAIL_COLLAPSED_LIMIT ||
                            it.conflicts.size > RESTORE_DETAIL_COLLAPSED_LIMIT)
                    ) {
                        TextButton(onClick = { detailsExpanded = true }) {
                            Text(stringResource(R.string.restore_backup_show_details))
                        }
                    }
                    if (it.dormantReadingStates + it.dormantBookmarks + it.dormantAnnotations > 0) {
                        Text(
                            stringResource(
                                R.string.restore_backup_dormant_anchors,
                                it.dormantReadingStates,
                                it.dormantBookmarks,
                                it.dormantAnnotations,
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    Text(stringResource(R.string.restore_backup_merge_notice), color = PaperTheme.tokens.inkMuted)
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = !restoring) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = onConfirm, enabled = !restoring) {
                    if (restoring) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (restoring) R.string.restoring_backup else R.string.restore_backup))
                }
            },
        )
    }
}

@Composable
fun SourcesScreen(
    providers: ProviderManagerState,
    onBack: () -> Unit,
) {
    MoreBranchScaffold(title = stringResource(R.string.sources_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.sources_body),
                style = MaterialTheme.typography.bodyLarge,
                color = PaperTheme.tokens.inkMuted,
            )
        }
        if (providers.installed.isEmpty() && providers.available.isEmpty() && providers.untrusted.isEmpty()) {
            item { PaperStatePanel(stringResource(R.string.no_providers), stringResource(R.string.sources_empty_body)) }
        }
        if (providers.untrusted.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_security_attention)) }
            items(providers.untrusted, key = { "untrusted:${it.packageName}" }) { provider ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.danger)
                        Text(
                            provider.packageName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusBadge(
                            text = stringResource(R.string.provider_blocked),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.provider_blocked_body), color = PaperTheme.tokens.inkMuted)
                    Text(
                        stringResource(R.string.provider_block_reason, provider.reason.take(240)),
                        color = PaperTheme.tokens.inkMuted,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.provider_signer,
                            provider.signerSha256.take(64).chunked(8).joinToString(" "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
        if (providers.installed.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_installed_section)) }
            items(providers.installed, key = { it.descriptor.id }) { provider ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(provider.descriptor.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (provider.origin == ProviderOrigin.BUILT_IN) {
                            stringResource(R.string.provider_builtin)
                        } else {
                            provider.packageName.orEmpty()
                        },
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(
                        stringResource(
                            R.string.provider_interval,
                            "${provider.descriptor.minimumRequestIntervalMillis} ms",
                        ),
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
        if (providers.available.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_available_section)) }
            item {
                Text(
                    stringResource(R.string.provider_available_notice),
                    color = PaperTheme.tokens.inkMuted,
                )
            }
            items(providers.available, key = { "available:${it.packageName}" }) { provider ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.provider_package, provider.packageName),
                        color = PaperTheme.tokens.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.provider_version_code, provider.versionCode),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.provider_id_count,
                            provider.providerIds.size,
                            provider.providerIds.size,
                        ),
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreBranchScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(title) },
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
            content = content,
        )
    }
}

@Composable
private fun LocalPdfImportStatus(
    state: LocalPdfImportUiState,
    onOpenPaper: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        LocalPdfImportUiState.Idle,
        is LocalPdfImportUiState.Confirming,
        -> Unit

        LocalPdfImportUiState.Preparing -> {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.inspecting_local_pdf))
            }
        }

        is LocalPdfImportUiState.Importing -> Unit

        is LocalPdfImportUiState.Complete -> {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(
                    text = stringResource(R.string.local_pdf_import_complete_badge),
                    icon = PaperIconKey.DONE,
                    color = PaperTheme.tokens.success,
                )
                Text(
                    stringResource(
                        if (state.alreadyImported) {
                            R.string.local_pdf_already_imported
                        } else {
                            R.string.local_pdf_imported
                        },
                    ),
                    color = PaperTheme.tokens.inkMuted,
                )
                PaperSecondaryButton(
                    onClick = { onOpenPaper(state.workId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_imported_paper))
                }
                PaperSecondaryButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        is LocalPdfImportUiState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperIcon(
                        PaperIconKey.ERROR,
                        contentDescription = null,
                        tint = PaperTheme.tokens.danger,
                    )
                    Text(
                        stringResource(localPdfImportFailureMessage(state.reason)),
                        color = PaperTheme.tokens.danger,
                    )
                }
                PaperSecondaryButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

private fun localPdfImportFailureMessage(reason: LocalPdfImportFailure): Int = when (reason) {
    LocalPdfImportFailure.INVALID_URI -> R.string.local_pdf_invalid_uri
    LocalPdfImportFailure.SOURCE_UNAVAILABLE -> R.string.local_pdf_source_unavailable
    LocalPdfImportFailure.TOO_LARGE -> R.string.local_pdf_too_large
    LocalPdfImportFailure.INVALID_PDF -> R.string.local_pdf_invalid_file
    LocalPdfImportFailure.INVALID_TITLE -> R.string.local_pdf_invalid_title
    LocalPdfImportFailure.IO_FAILURE -> R.string.local_pdf_io_failure
    LocalPdfImportFailure.COMMIT_FAILED -> R.string.local_pdf_commit_failed
}

private fun formatImportFileSize(byteLength: Long): String = when {
    byteLength >= 1024L * 1024L -> String.format(java.util.Locale.ENGLISH, "%.1f MB", byteLength / (1024.0 * 1024.0))
    byteLength >= 1024L -> String.format(java.util.Locale.ENGLISH, "%.1f KB", byteLength / 1024.0)
    else -> "$byteLength B"
}

@Composable
private fun MetadataBackupStatus(
    state: MetadataBackupUiState,
    onDismiss: () -> Unit,
) {
    when (state) {
        MetadataBackupUiState.Idle,
        is MetadataBackupUiState.Preview,
        is MetadataBackupUiState.Restoring,
        -> Unit

        MetadataBackupUiState.Exporting,
        MetadataBackupUiState.Inspecting,
        -> {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(
                        if (state == MetadataBackupUiState.Exporting) {
                            R.string.creating_metadata_backup
                        } else {
                            R.string.inspecting_metadata_backup
                        },
                    ),
                    color = PaperTheme.tokens.inkMuted,
                )
            }
        }

        is MetadataBackupUiState.Exported -> {
            Spacer(Modifier.height(12.dp))
            Text(
                pluralStringResource(
                    R.plurals.metadata_backup_saved,
                    state.summary.works,
                    state.summary.works,
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.success,
            )
        }

        is MetadataBackupUiState.Restored -> {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.metadata_backup_restored,
                    state.report.appliedWorks,
                    state.report.skippedWorks,
                    state.report.skippedRecords,
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.success,
            )
        }

        is MetadataBackupUiState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    when (state.reason) {
                        MetadataBackupFailure.FILE_TOO_LARGE -> R.string.metadata_backup_too_large
                        MetadataBackupFailure.FILE_UNAVAILABLE -> R.string.metadata_backup_file_unavailable
                        MetadataBackupFailure.INCOMPLETE_FILE_MAY_REMAIN -> {
                            R.string.metadata_backup_incomplete_file_may_remain
                        }
                        MetadataBackupFailure.INVALID_OR_UNSUPPORTED -> R.string.metadata_backup_invalid
                        MetadataBackupFailure.LIBRARY_TOO_LARGE -> R.string.metadata_backup_library_too_large
                        MetadataBackupFailure.EXPORT_FAILED -> R.string.metadata_backup_export_failed
                        MetadataBackupFailure.RESTORE_FAILED -> R.string.metadata_backup_restore_failed
                    },
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.danger,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

private fun MetadataRestoreIssueUi.toEnglishRestoreIssue(): String {
    val label = when (code) {
        "alias_conflict" -> "Exact identifier matches multiple local papers"
        "work_id_conflict" -> "Paper identity collides with different local data"
        "manifestation_id_conflict" -> "Document identity collides with different local data"
        "annotation_conflict" -> "Annotation ID belongs to a different local anchor"
        else -> code.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    return "$label ($detail)"
}

private fun hiddenItemSuffix(hiddenCount: Int): String =
    if (hiddenCount > 0) " (+$hiddenCount more)" else ""

private const val RESTORE_DETAIL_COLLAPSED_LIMIT = 3
private const val RESTORE_DETAIL_LIMIT = 20

@Composable
private fun CollectionRow(
    collection: PaperCollectionUi,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaperIcon(PaperIconKey.FOLDER, contentDescription = null, tint = PaperTheme.tokens.primary)
            Text(
                collection.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                PaperIcon(
                    PaperIconKey.EDIT,
                    contentDescription = stringResource(R.string.rename_collection_accessibility, collection.name),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                PaperIcon(
                    PaperIconKey.DELETE,
                    contentDescription = stringResource(R.string.delete_collection_accessibility, collection.name),
                    tint = PaperTheme.tokens.danger,
                )
            }
        }
    }
}

@Composable
private fun ThemeChoiceCard(
    preset: PaperThemePreset,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    PaperSurface(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(themeName(preset), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = themeDescription(preset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ThemeSwatch(preset, dark)
        }
    }
}

@Composable
private fun themeName(preset: PaperThemePreset): String = stringResource(
    when (preset) {
        PaperThemePreset.DOODLE -> R.string.theme_doodle
        PaperThemePreset.RETRO -> R.string.theme_retro
        PaperThemePreset.NEOBRUTALISM -> R.string.theme_neobrutalism
    },
)

@Composable
private fun themeDescription(preset: PaperThemePreset): String = stringResource(
    when (preset) {
        PaperThemePreset.DOODLE -> R.string.theme_doodle_description
        PaperThemePreset.RETRO -> R.string.theme_retro_description
        PaperThemePreset.NEOBRUTALISM -> R.string.theme_neobrutalism_description
    },
)

@Composable
private fun ThemeSwatch(preset: PaperThemePreset, dark: Boolean) {
    val preview = paperThemeTokens(preset, dark)
    val colors = listOf(preview.primary, preview.secondary, preview.surface)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { color ->
            Surface(
                modifier = Modifier.size(20.dp),
                shape = RoundedCornerShape(4.dp),
                color = color,
                border = BorderStroke(1.dp, PaperTheme.tokens.border),
            ) {}
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

@Composable
private fun topBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = PaperTheme.tokens.canvas,
    titleContentColor = PaperTheme.tokens.ink,
    actionIconContentColor = PaperTheme.tokens.ink,
)
