package dev.paperreader.app.ui.screen

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
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
) {
    var query by rememberSaveable(state.submittedQuery) {
        mutableStateOf(state.submittedQuery.orEmpty())
    }
    var previewKey by rememberSaveable(state.submittedQuery) { mutableStateOf<String?>(null) }
    var previewLinkFailed by rememberSaveable(state.submittedQuery, previewKey) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val previewResult = state.results.firstOrNull { it.key == previewKey }
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
                }
            }
            if (state.running && state.results.isNotEmpty()) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            items(
                state.failures,
                key = ProviderSearchFailure::providerId,
                contentType = { "provider-failure" },
            ) { failure ->
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
                    state.submittedQuery?.let { submittedQuery ->
                        GoogleArxivSearchAction(
                            onOpen = { uriHandler.openUri(googleArxivSearchUrl(submittedQuery)) },
                        )
                    }
                }

                !state.running && state.results.isEmpty() -> item {
                    PaperStatePanel(
                        title = stringResource(R.string.search_no_results_title),
                        body = stringResource(R.string.search_no_results_body),
                        icon = PaperIconKey.SEARCH,
                    )
                    state.submittedQuery?.let { submittedQuery ->
                        GoogleArxivSearchAction(
                            onOpen = { uriHandler.openUri(googleArxivSearchUrl(submittedQuery)) },
                        )
                    }
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
                    items(
                        state.results,
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

@Composable
private fun GoogleArxivSearchAction(
    onOpen: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    PaperSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(PaperIconKey.SEARCH, contentDescription = null, tint = PaperTheme.tokens.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.search_google_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.search_google_body),
                    color = PaperTheme.tokens.inkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PaperSecondaryButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            PaperIcon(PaperIconKey.OPEN_EXTERNAL, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.search_google_action))
        }
    }
}

internal fun googleArxivSearchUrl(query: String): String =
    "https://www.google.com/search?q=${Uri.encode("site:arxiv.org $query")}"

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
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val previewDescription = stringResource(R.string.search_preview_open, result.title)
    PaperSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .semantics {
                    contentDescription = previewDescription
                },
        ) {
            PaperMetaRow(
                source = result.sourceDisplayNames.joinToString(),
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
