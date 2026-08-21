package dev.paperreader.app.ui

import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.persistedSavedWorkIds
import dev.paperreader.app.ui.model.toSearchPaperUi
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.normalizeSavedSearchQuery
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.usecase.FederatedSearchEvent
import dev.paperreader.logic.usecase.RefreshSavedSearchResult
import dev.paperreader.logic.usecase.SaveSavedSearchHitResult
import dev.paperreader.logic.usecase.SearchResultCluster
import dev.paperreader.logic.usecase.SearchResultClusterer
import dev.paperreader.logic.usecase.SearchResultRanker
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProviderFailureKind {
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

data class ProviderSearchFailure(
    val providerId: String,
    val providerName: String,
    val kind: ProviderFailureKind,
    val retryAfterMillis: Long? = null,
)

enum class ProviderSearchStatus {
    SEARCHING,
    READY,
    FAILED,
}

data class ProviderSearchUiState(
    val providerId: String,
    val providerName: String,
    val status: ProviderSearchStatus = ProviderSearchStatus.SEARCHING,
    val resultCount: Int = 0,
    val failure: ProviderSearchFailure? = null,
)

data class SearchUiState(
    val submittedQuery: String? = null,
    val running: Boolean = false,
    val providerCount: Int = 0,
    val providerIds: Set<String> = emptySet(),
    val providerStates: List<ProviderSearchUiState> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val results: List<SearchPaperUi> = emptyList(),
    val failures: List<ProviderSearchFailure> = emptyList(),
    val savingKeys: Set<String> = emptySet(),
    val savedWorkIds: Map<String, String> = emptyMap(),
    val saveFailedKeys: Set<String> = emptySet(),
)

enum class SavedSearchCreateFailure {
    INVALID_QUERY,
    NO_PROVIDERS,
    STORAGE,
}

data class SavedSearchActionUiState(
    val creatingSearches: Set<SavedSearchActionKey> = emptySet(),
    val createdSearchIds: Map<SavedSearchActionKey, String> = emptyMap(),
    val createFailures: Map<SavedSearchActionKey, SavedSearchCreateFailure> = emptyMap(),
    val refreshingSearchIds: Set<String> = emptySet(),
    val deletingSearchIds: Set<String> = emptySet(),
    val failedSearchActionIds: Set<String> = emptySet(),
    val savingHitIds: Set<String> = emptySet(),
    val failedHitActionIds: Set<String> = emptySet(),
    val savedHitWorkIds: Map<String, String> = emptyMap(),
)

data class SavedSearchActionKey(
    val queryText: String,
    val providerIds: List<String>,
)

/** Owns remote discovery, result ranking, and the persisted saved-search action state. */
internal class PaperReaderSearchController(
    private val logic: PaperReaderLogic,
    private val scope: CoroutineScope,
    private val providers: StateFlow<ProviderManagerState>,
    private val preferences: PaperReaderPreferences,
    private val library: StateFlow<LoadState<List<PaperUi>>>,
) {
    val savedSearches: StateFlow<LoadState<List<SavedSearchFeed>>> = logic.useCases.observeSavedSearches
        .subscribe()
        .asLoadState(scope) { it }

    private val mutableSearch = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = mutableSearch
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var savedLibrary = emptyList<PaperUi>()
    private val newlySavedWorkIds = linkedMapOf<String, String>()

    private val mutableSavedSearchActions = MutableStateFlow(SavedSearchActionUiState())
    val savedSearchActions: StateFlow<SavedSearchActionUiState> = mutableSavedSearchActions

    init {
        scope.launch {
            library.collect { state ->
                if (state !is LoadState.Ready) return@collect
                savedLibrary = state.value
                val liveWorkIds = savedLibrary.mapTo(hashSetOf(), PaperUi::id)
                newlySavedWorkIds.entries.removeAll { (_, workId) -> workId !in liveWorkIds }
                val current = mutableSearch.value
                mutableSearch.value = current.copy(savedWorkIds = savedWorkIdsFor(current.results))
            }
        }
        scope.launch {
            preferences.recentSearchQueries.collect { queries ->
                mutableSearch.value = mutableSearch.value.copy(recentQueries = queries)
            }
        }
    }

    fun search(query: String) {
        val submitted = query.trim()
        if (submitted.isEmpty()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        val recentQueries = listOf(submitted) + mutableSearch.value.recentQueries.filterNot {
            it.equals(submitted, ignoreCase = true)
        }
        mutableSearch.value = SearchUiState(
            submittedQuery = submitted,
            running = true,
            recentQueries = recentQueries.take(8),
        )
        scope.launch { preferences.setRecentSearchQueries(recentQueries) }
        val providerNames = providers.value.installed.associate {
            it.descriptor.id to it.descriptor.displayName
        }
        searchJob = scope.launch {
            val records = mutableListOf<RemotePaper>()
            val failures = linkedMapOf<String, ProviderSearchFailure>()
            try {
                logic.useCases.searchPapers.subscribe(PaperSearchQuery(submitted)).collect { event ->
                    if (generation != searchGeneration) return@collect
                    when (event) {
                        is FederatedSearchEvent.Started -> mutableSearch.value = mutableSearch.value.copy(
                            providerCount = event.providerCount,
                            providerIds = event.providerIds,
                            providerStates = event.providerIds
                                .sorted()
                                .map { providerId ->
                                    ProviderSearchUiState(
                                        providerId = providerId,
                                        providerName = providerNames[providerId] ?: providerId,
                                    )
                                },
                        )

                        is FederatedSearchEvent.PageReceived -> {
                            records += event.page.items
                            // Ranking and exact-alias clustering are CPU-heavy when a provider
                            // returns a large page. Keep that work off the main dispatcher so
                            // the LazyColumn remains responsive while results stream in.
                            val rankedResults = withContext(Dispatchers.Default) {
                                SearchResultRanker.rank(
                                    submitted,
                                    SearchResultClusterer.cluster(records.toList()),
                                ).map { it.toSearchPaperUi(providerNames) }
                            }
                            if (generation == searchGeneration) {
                                val current = mutableSearch.value
                                mutableSearch.value = current.copy(
                                    providerStates = current.providerStates.map { provider ->
                                        if (provider.providerId == event.providerId) {
                                            provider.copy(
                                                status = ProviderSearchStatus.READY,
                                                resultCount = records.count { it.providerId == event.providerId },
                                            )
                                        } else {
                                            provider
                                        }
                                    },
                                    results = rankedResults,
                                    savedWorkIds = savedWorkIdsFor(rankedResults),
                                )
                            }
                        }

                        is FederatedSearchEvent.ProviderFailed -> {
                            val providerName = providers.value.installed
                                .firstOrNull { it.descriptor.id == event.providerId }
                                ?.descriptor
                                ?.displayName
                                ?: event.providerId
                            failures[event.providerId] = event.error.toUiFailure(event.providerId, providerName)
                            mutableSearch.value = mutableSearch.value.copy(
                                providerStates = mutableSearch.value.providerStates.map { provider ->
                                    if (provider.providerId == event.providerId) {
                                        provider.copy(
                                            status = ProviderSearchStatus.FAILED,
                                            failure = failures[event.providerId],
                                        )
                                    } else {
                                        provider
                                    }
                                },
                                failures = failures.values.toList(),
                            )
                        }

                        is FederatedSearchEvent.Finished -> Unit
                    }
                }
            } finally {
                if (generation == searchGeneration) {
                    mutableSearch.value = mutableSearch.value.copy(running = false)
                }
            }
        }
    }

    fun clearSearch() {
        searchGeneration += 1
        searchJob?.cancel()
        mutableSearch.value = SearchUiState(recentQueries = mutableSearch.value.recentQueries)
    }

    fun save(result: SearchPaperUi) {
        val current = mutableSearch.value
        if (result.key in current.savingKeys || result.key in current.savedWorkIds) return
        mutableSearch.value = current.copy(
            savingKeys = current.savingKeys + result.key,
            saveFailedKeys = current.saveFailedKeys - result.key,
        )
        scope.launch {
            try {
                val workId = logic.useCases.saveSearchResult.await(SearchResultCluster(result.records))
                newlySavedWorkIds[result.key] = workId.value
                val latest = mutableSearch.value
                mutableSearch.value = latest.copy(
                    savingKeys = latest.savingKeys - result.key,
                    savedWorkIds = savedWorkIdsFor(latest.results),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSearch.value = mutableSearch.value.copy(
                    savingKeys = mutableSearch.value.savingKeys - result.key,
                    saveFailedKeys = mutableSearch.value.saveFailedKeys + result.key,
                )
            }
        }
    }

    private fun savedWorkIdsFor(results: List<SearchPaperUi>): Map<String, String> {
        val resultKeys = results.mapTo(hashSetOf(), SearchPaperUi::key)
        return persistedSavedWorkIds(results, savedLibrary) +
            newlySavedWorkIds.filterKeys(resultKeys::contains)
    }

    fun createSavedSearch(query: String) {
        val searchState = mutableSearch.value
        val normalized = normalizeSavedSearchQuery(query)
        val providerIds = if (
            normalized != null && normalizeSavedSearchQuery(searchState.submittedQuery.orEmpty()) == normalized
        ) {
            searchState.providerIds
        } else {
            providers.value.installed.map { it.descriptor.id }.toSet()
        }
        val key = SavedSearchActionKey(
            queryText = normalized ?: query.trim(),
            providerIds = providerIds.map(String::lowercase).distinct().sorted(),
        )
        if (normalized == null) {
            mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                createFailures = mutableSavedSearchActions.value.createFailures +
                    (key to SavedSearchCreateFailure.INVALID_QUERY),
            )
            return
        }
        val current = mutableSavedSearchActions.value
        if (key in current.creatingSearches) return
        mutableSavedSearchActions.value = current.copy(
            creatingSearches = current.creatingSearches + key,
            createFailures = current.createFailures - key,
        )
        scope.launch {
            try {
                when (val result = logic.useCases.createSavedSearch.await(normalized, providerIds)) {
                    is CreateSavedSearchResult.Created -> {
                        savedSearchCreated(key, result.searchId)
                        refreshSavedSearchNow(result.searchId)
                    }

                    is CreateSavedSearchResult.AlreadyExists -> savedSearchCreated(key, result.searchId)
                    CreateSavedSearchResult.InvalidQuery -> savedSearchCreateFailed(
                        key,
                        SavedSearchCreateFailure.INVALID_QUERY,
                    )

                    CreateSavedSearchResult.NoProviders -> savedSearchCreateFailed(
                        key,
                        SavedSearchCreateFailure.NO_PROVIDERS,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                savedSearchCreateFailed(key, SavedSearchCreateFailure.STORAGE)
            } finally {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    creatingSearches = mutableSavedSearchActions.value.creatingSearches - key,
                )
            }
        }
    }

    fun refreshSavedSearch(searchId: String) {
        val id = runCatching { SavedSearchId(searchId) }.getOrNull() ?: return
        if (searchId in mutableSavedSearchActions.value.refreshingSearchIds) return
        scope.launch { refreshSavedSearchNow(id) }
    }

    fun deleteSavedSearch(searchId: String) {
        val id = runCatching { SavedSearchId(searchId) }.getOrNull() ?: return
        val current = mutableSavedSearchActions.value
        if (searchId in current.deletingSearchIds) return
        mutableSavedSearchActions.value = current.copy(
            deletingSearchIds = current.deletingSearchIds + searchId,
            failedSearchActionIds = current.failedSearchActionIds - searchId,
        )
        scope.launch {
            try {
                when (logic.useCases.deleteSavedSearch.await(id)) {
                    DeleteSavedSearchResult.Deleted,
                    DeleteSavedSearchResult.NotFound,
                    -> mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                        createdSearchIds = mutableSavedSearchActions.value.createdSearchIds
                            .filterValues { it != searchId },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    failedSearchActionIds = mutableSavedSearchActions.value.failedSearchActionIds + searchId,
                )
            } finally {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    deletingSearchIds = mutableSavedSearchActions.value.deletingSearchIds - searchId,
                )
            }
        }
    }

    fun markSavedSearchHitRead(hitId: String) {
        val id = runCatching { SavedSearchHitId(hitId) }.getOrNull() ?: return
        scope.launch {
            try {
                if (!logic.useCases.markSavedSearchHitRead.await(id)) {
                    mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                        failedHitActionIds = mutableSavedSearchActions.value.failedHitActionIds + hitId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    failedHitActionIds = mutableSavedSearchActions.value.failedHitActionIds + hitId,
                )
            }
        }
    }

    fun saveSavedSearchHit(hitId: String) {
        val id = runCatching { SavedSearchHitId(hitId) }.getOrNull() ?: return
        val current = mutableSavedSearchActions.value
        if (hitId in current.savingHitIds || hitId in current.savedHitWorkIds) return
        mutableSavedSearchActions.value = current.copy(
            savingHitIds = current.savingHitIds + hitId,
            failedHitActionIds = current.failedHitActionIds - hitId,
        )
        scope.launch {
            try {
                when (val result = logic.useCases.saveSavedSearchHit.await(id)) {
                    is SaveSavedSearchHitResult.Saved -> mutableSavedSearchActions.value =
                        mutableSavedSearchActions.value.copy(
                            savedHitWorkIds = mutableSavedSearchActions.value.savedHitWorkIds +
                                (hitId to result.workId.value),
                        )

                    SaveSavedSearchHitResult.NotFound -> mutableSavedSearchActions.value =
                        mutableSavedSearchActions.value.copy(
                            failedHitActionIds = mutableSavedSearchActions.value.failedHitActionIds + hitId,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    failedHitActionIds = mutableSavedSearchActions.value.failedHitActionIds + hitId,
                )
            } finally {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    savingHitIds = mutableSavedSearchActions.value.savingHitIds - hitId,
                )
            }
        }
    }

    private suspend fun refreshSavedSearchNow(id: SavedSearchId) {
        val searchId = id.value
        if (searchId in mutableSavedSearchActions.value.refreshingSearchIds) return
        mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
            refreshingSearchIds = mutableSavedSearchActions.value.refreshingSearchIds + searchId,
            failedSearchActionIds = mutableSavedSearchActions.value.failedSearchActionIds - searchId,
        )
        try {
            if (logic.useCases.refreshSavedSearch.await(id) == RefreshSavedSearchResult.NotFound) {
                mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                    failedSearchActionIds = mutableSavedSearchActions.value.failedSearchActionIds + searchId,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                failedSearchActionIds = mutableSavedSearchActions.value.failedSearchActionIds + searchId,
            )
        } finally {
            mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
                refreshingSearchIds = mutableSavedSearchActions.value.refreshingSearchIds - searchId,
            )
        }
    }

    private fun savedSearchCreated(key: SavedSearchActionKey, id: SavedSearchId) {
        mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
            createdSearchIds = mutableSavedSearchActions.value.createdSearchIds + (key to id.value),
            createFailures = mutableSavedSearchActions.value.createFailures - key,
        )
    }

    private fun savedSearchCreateFailed(key: SavedSearchActionKey, failure: SavedSearchCreateFailure) {
        mutableSavedSearchActions.value = mutableSavedSearchActions.value.copy(
            createFailures = mutableSavedSearchActions.value.createFailures + (key to failure),
        )
    }
}

private fun ProviderException.toUiFailure(
    providerId: String,
    providerName: String,
): ProviderSearchFailure = when (this) {
    is ProviderException.RateLimited -> ProviderSearchFailure(
        providerId = providerId,
        providerName = providerName,
        kind = ProviderFailureKind.RATE_LIMITED,
        retryAfterMillis = retryAfterMillis,
    )

    is ProviderException.InvalidResponse -> ProviderSearchFailure(
        providerId = providerId,
        providerName = providerName,
        kind = ProviderFailureKind.INVALID_RESPONSE,
    )
    is ProviderException.Unavailable -> ProviderSearchFailure(
        providerId = providerId,
        providerName = providerName,
        kind = ProviderFailureKind.UNAVAILABLE,
    )
}

private fun <T, R> Flow<T>.asLoadState(
    scope: CoroutineScope,
    transform: (T) -> R,
): StateFlow<LoadState<R>> = map<T, LoadState<R>> { LoadState.Ready(transform(it)) }
    .catch { error ->
        if (error is CancellationException) throw error
        emit(LoadState.Failed)
    }
    .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = LoadState.Loading,
    )
