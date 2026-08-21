package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.model.LibrarySortOrder
import dev.paperreader.app.ui.model.LibraryStatusFilter
import dev.paperreader.app.ui.model.filterAndSortLibrary
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LoadState<List<PaperUi>>,
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    layout: LibraryLayout = LibraryLayout.LIST,
    onLayoutChange: (LibraryLayout) -> Unit,
    onOpenPaper: (String) -> Unit,
    onDiscover: () -> Unit,
    onReadPaper: (PaperUi) -> Unit = { onOpenPaper(it.id) },
) {
    val grid = layout == LibraryLayout.GRID
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
                                            RadioButton(
                                                selected = option == sortOrder,
                                                onClick = null,
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = PaperTheme.tokens.ink,
                                                    unselectedColor = PaperTheme.tokens.inkMuted,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (papers.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onLayoutChange(if (grid) LibraryLayout.LIST else LibraryLayout.GRID)
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
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
                            onReadPaper = onReadPaper,
                            sectionTitle = librarySortLabel(sortOrder),
                            totalCount = papers.size,
                        )
                    } else {
                        LibraryList(
                            papers = visiblePapers,
                            padding = PaddingValues(0.dp),
                            onOpenPaper = onOpenPaper,
                            onReadPaper = onReadPaper,
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
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = PaperTheme.tokens.ink,
                strokeWidth = 2.dp,
            )
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
    onReadPaper: (PaperUi) -> Unit,
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
            LibraryListPaperCard(
                paper = paper,
                onOpen = { onOpenPaper(paper.id) },
                onRead = { onReadPaper(paper) },
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    papers: List<PaperUi>,
    padding: PaddingValues,
    onOpenPaper: (String) -> Unit,
    onReadPaper: (PaperUi) -> Unit,
    sectionTitle: String,
    totalCount: Int,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
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
            LibraryGridPaperCard(
                paper = paper,
                onOpen = { onOpenPaper(paper.id) },
                onRead = { onReadPaper(paper) },
            )
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
        LibraryStatusFilter.ANNOTATED -> R.string.filter_annotated
    },
)
