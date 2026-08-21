package dev.paperreader.app.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
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
import dev.paperreader.app.ui.ProviderFailureKind
import dev.paperreader.app.ui.ProviderSearchFailure
import dev.paperreader.app.ui.ProviderSearchStatus
import dev.paperreader.app.ui.ProviderSearchUiState
import dev.paperreader.app.ui.SearchUiState
import dev.paperreader.app.ui.SavedSearchActionUiState
import dev.paperreader.app.ui.SavedSearchActionKey
import dev.paperreader.app.ui.SavedSearchCreateFailure
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.normalizeSavedSearchQuery

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
    onSearchGoogle: (String) -> Unit = {},
) {
    var query by rememberSaveable(state.submittedQuery) {
        mutableStateOf(state.submittedQuery.orEmpty())
    }
    var previewKey by rememberSaveable(state.submittedQuery) { mutableStateOf<String?>(null) }
    var previewLinkFailed by rememberSaveable(state.submittedQuery, previewKey) { mutableStateOf(false) }
    var selectedProviderId by rememberSaveable(state.submittedQuery) { mutableStateOf<String?>(null) }
    var onlySourcesWithResults by rememberSaveable(state.submittedQuery) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    var searchFieldHeightPx by remember { mutableIntStateOf(0) }
    // Material's single-line search field starts at 56 dp. Matching that on the
    // first frame prevents the action from visibly resizing after measurement.
    val searchFieldHeight = if (searchFieldHeightPx == 0) 56.dp else with(density) { searchFieldHeightPx.toDp() }
    val previewResult = state.results.firstOrNull { it.key == previewKey }
    val eligibleProviderIds = state.providerStates
        .filter { !onlySourcesWithResults || it.resultCount > 0 }
        .map(ProviderSearchUiState::providerId)
        .toSet()
    val visibleResults = state.results.filter { result ->
        val matchesProvider = selectedProviderId == null || selectedProviderId in result.sources
        val matchesAvailability = !onlySourcesWithResults || result.sources.any(eligibleProviderIds::contains)
        matchesProvider && matchesAvailability
    }
    val submitSearch = {
        if (query.isNotBlank() && !state.running) {
            focusManager.clearFocus(force = true)
            onSearch(query)
        }
    }
    previewResult?.let { result ->
        SearchPreviewSheet(
            result = result,
            saving = result.key in state.savingKeys,
            savedWorkId = state.savedWorkIds[result.key],
            saveFailed = result.key in state.saveFailedKeys,
            linkOpenFailed = previewLinkFailed,
            onDismiss = { previewKey = null },
            onSave = { onSave(result) },
            onOpenSaved = { workId ->
                previewKey = null
                onOpenPaper(workId)
            },
            onOpenUrl = { url ->
                previewLinkFailed = runCatching { uriHandler.openUri(url) }.isFailure
            },
        )
    }
    val searchActionDescription = stringResource(
        if (state.running) R.string.searching_title else R.string.search_submit,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.discover_title)) },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { searchFieldHeightPx = it.height },
                    singleLine = true,
                    // This is a search control, not a form field. A floating label
                    // reserves visual space above the outline and makes the adjacent
                    // action look misaligned even when their layout bounds match.
                    placeholder = { Text(stringResource(R.string.search_label)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    onClear()
                                },
                            ) {
                                PaperIcon(
                                    PaperIconKey.CLOSE,
                                    contentDescription = stringResource(R.string.search_clear),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                )
                PaperPrimaryButton(
                    onClick = submitSearch,
                    enabled = query.isNotBlank() && !state.running,
                    contentDescription = searchActionDescription,
                    modifier = Modifier.size(
                        width = 64.dp,
                        height = searchFieldHeight + PaperTheme.tokens.shadowOffset,
                    ),
                ) {
                    if (state.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        PaperIcon(
                            PaperIconKey.SEARCH,
                            contentDescription = null,
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            if (state.running && state.results.isNotEmpty()) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PaperTheme.tokens.ink,
                        trackColor = PaperTheme.tokens.surfaceMuted,
                    )
                }
            }
            if (state.providerStates.size > 1) {
                item {
                    SearchProviderFilters(
                        providers = state.providerStates,
                        selectedProviderId = selectedProviderId,
                        onlySourcesWithResults = onlySourcesWithResults,
                        onProviderSelected = { providerId ->
                            selectedProviderId = providerId
                            onlySourcesWithResults = false
                        },
                        onAvailabilityFilterSelected = {
                            onlySourcesWithResults = it
                            selectedProviderId = null
                        },
                    )
                }
            }
            items(
                state.failures,
                key = ProviderSearchFailure::providerId,
                contentType = { "provider-failure" },
            ) { failure ->
                ProviderFailureRow(
                    failure = failure,
                    onRetry = state.submittedQuery?.let { submittedQuery -> { onSearch(submittedQuery) } },
                )
            }
            if (state.submittedQuery == null && state.recentQueries.isNotEmpty()) {
                item {
                    RecentSearches(
                        queries = state.recentQueries.filter { recentQuery ->
                            query.isBlank() || recentQuery.contains(query.trim(), ignoreCase = true)
                        }.take(MAX_VISIBLE_RECENT_SEARCHES),
                        onSelect = { recentQuery ->
                            query = recentQuery
                            submitSearch()
                        },
                    )
                }
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
                state.submittedQuery == null -> if (state.recentQueries.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_start_body),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                }

                state.running && state.results.isEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.searching_title),
                        body = pluralStringResource(
                            R.plurals.searching_provider_count,
                            state.providerCount,
                            state.providerCount,
                        ),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        loading = true,
                    )
                }

                !state.running && state.results.isEmpty() && state.failures.isNotEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_failed_title),
                        body = stringResource(R.string.search_failed_body),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        icon = PaperIconKey.ERROR,
                    )
                    state.submittedQuery?.let { submittedQuery ->
                        PaperSecondaryButton(
                            onClick = { onSearchGoogle(submittedQuery) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            PaperIcon(PaperIconKey.SEARCH, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.search_google_in_app))
                        }
                    }
                }

                !state.running && state.results.isEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_no_results_title),
                        body = stringResource(R.string.search_no_results_body),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        icon = PaperIconKey.SEARCH,
                    )
                    state.submittedQuery?.let { submittedQuery ->
                        PaperSecondaryButton(
                            onClick = { onSearchGoogle(submittedQuery) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            PaperIcon(PaperIconKey.SEARCH, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.search_google_in_app))
                        }
                    }
                }

                visibleResults.isEmpty() && state.results.isNotEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_filter_no_results_title),
                        body = stringResource(R.string.search_filter_no_results_body),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                                        visibleResults.size,
                                        visibleResults.size,
                                    ),
                                )
                            },
                        )
                    }
                    items(
                        visibleResults,
                        key = SearchPaperUi::key,
                        contentType = { "search-result" },
                    ) { result ->
                        SearchResultCard(
                            result = result,
                            saving = result.key in state.savingKeys,
                            savedWorkId = state.savedWorkIds[result.key],
                            saveFailed = result.key in state.saveFailedKeys,
                            onPreview = { previewKey = result.key },
                            onSave = { onSave(result) },
                            onOpen = onOpenPaper,
                        )
                    }
                }
            }
        }
    }
}
}

private const val MAX_VISIBLE_RECENT_SEARCHES = 5

@Composable
private fun SearchProviderFilters(
    providers: List<ProviderSearchUiState>,
    selectedProviderId: String?,
    onlySourcesWithResults: Boolean,
    onProviderSelected: (String?) -> Unit,
    onAvailabilityFilterSelected: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedProviderId == null && !onlySourcesWithResults,
            onClick = { onProviderSelected(null) },
            label = { Text(stringResource(R.string.search_filter_all)) },
        )
        FilterChip(
            selected = onlySourcesWithResults,
            onClick = { onAvailabilityFilterSelected(!onlySourcesWithResults) },
            label = { Text(stringResource(R.string.search_filter_has_results)) },
        )
        providers.forEach { provider ->
            FilterChip(
                selected = selectedProviderId == provider.providerId,
                onClick = { onProviderSelected(provider.providerId) },
                label = {
                    Text(
                        text = when (provider.status) {
                            ProviderSearchStatus.SEARCHING ->
                                stringResource(R.string.search_provider_searching, provider.providerName)
                            ProviderSearchStatus.READY ->
                                stringResource(
                                    R.string.search_provider_ready,
                                    provider.providerName,
                                    provider.resultCount,
                                )
                            ProviderSearchStatus.FAILED ->
                                stringResource(R.string.search_provider_failed, provider.providerName)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun RecentSearches(
    queries: List<String>,
    onSelect: (String) -> Unit,
) {
    if (queries.isEmpty()) return
    PaperSurface {
        Text(stringResource(R.string.search_recent_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        queries.forEach { recentQuery ->
            val recentQueryDescription = stringResource(R.string.search_recent_query, recentQuery)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelect(recentQuery) }
                    .semantics {
                        contentDescription = recentQueryDescription
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperIcon(PaperIconKey.HISTORY, contentDescription = null, tint = PaperTheme.tokens.inkMuted)
                Text(
                    text = recentQuery,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PaperIcon(PaperIconKey.FORWARD, contentDescription = null, tint = PaperTheme.tokens.inkMuted)
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PaperTheme.tokens.ink,
                        strokeWidth = 2.dp,
                    )
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
