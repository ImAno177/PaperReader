package dev.paperreader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.backup.MetadataRestoreSessionStore
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.model.toPaperCollectionUi
import dev.paperreader.app.ui.model.toReadingHistoryUi
import dev.paperreader.app.ui.model.toSearchPaperUi
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.normalizeSavedSearchQuery
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.plugin.ExtensionStorePreview
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.CancelTaskResult
import dev.paperreader.logic.task.DeleteDownloadResult
import dev.paperreader.logic.task.DownloadRequestResult
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.task.RemoveTaskResult
import dev.paperreader.logic.task.RetryDownloadResult
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskState
import dev.paperreader.logic.usecase.FederatedSearchEvent
import dev.paperreader.logic.usecase.SearchResultClusterer
import dev.paperreader.logic.usecase.SearchResultCluster
import dev.paperreader.logic.usecase.SearchResultRanker
import dev.paperreader.logic.usecase.RefreshSavedSearchResult
import dev.paperreader.logic.usecase.SaveSavedSearchHitResult
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data object Failed : LoadState<Nothing>
}

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

data class SearchUiState(
    val submittedQuery: String? = null,
    val running: Boolean = false,
    val providerCount: Int = 0,
    val providerIds: Set<String> = emptySet(),
    val results: List<SearchPaperUi> = emptyList(),
    val failures: List<ProviderSearchFailure> = emptyList(),
    val savingKeys: Set<String> = emptySet(),
    val savedWorkIds: Map<String, String> = emptyMap(),
    val saveFailedKeys: Set<String> = emptySet(),
)

data class DownloadActionUiState(
    val requestingManifestations: Set<String> = emptySet(),
    val failedManifestations: Set<String> = emptySet(),
    val actingTaskIds: Set<String> = emptySet(),
    val failedTaskIds: Set<String> = emptySet(),
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

enum class ExtensionStoreOperation {
    PREVIEW,
    ADD,
    REFRESH,
    REMOVE,
}

sealed interface ExtensionStoreActionUiState {
    data object Idle : ExtensionStoreActionUiState
    data class Working(val operation: ExtensionStoreOperation) : ExtensionStoreActionUiState
    data class PreviewReady(val preview: ExtensionStorePreview) : ExtensionStoreActionUiState
    data class Failed(val operation: ExtensionStoreOperation, val message: String) : ExtensionStoreActionUiState
}

class PaperReaderViewModel internal constructor(
    private val logic: PaperReaderLogic,
    private val downloadWorkScheduler: DownloadWorkScheduler,
    private val metadataRestoreSessionStore: MetadataRestoreSessionStore,
) : ViewModel() {
    val library: StateFlow<LoadState<List<PaperUi>>> = logic.useCases.observeLibrary
        .subscribe()
        .asLoadState { papers -> papers.map { it.toPaperUi() } }

    val history: StateFlow<LoadState<List<ReadingHistoryUi>>> = logic.useCases.observeReadingHistory
        .subscribe()
        .asLoadState { entries -> entries.map { it.toReadingHistoryUi() } }

    val collections: StateFlow<LoadState<List<PaperCollectionUi>>> = logic.useCases.observeCollections
        .subscribe()
        .asLoadState { collections -> collections.map { it.toPaperCollectionUi() } }

    val tasks: StateFlow<LoadState<List<PaperTask>>> = logic.tasks.tasks.asLoadState { it }

    val savedSearches = logic.useCases.observeSavedSearches.subscribe().asLoadState { it }

    val providers = logic.providers.state
    val extensionStores = logic.extensionStores.state

    private val mutableDownloadActions = MutableStateFlow(DownloadActionUiState())
    val downloadActions: StateFlow<DownloadActionUiState> = mutableDownloadActions

    private val mutableSearch = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = mutableSearch
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private val mutableSavedSearchActions = MutableStateFlow(SavedSearchActionUiState())
    val savedSearchActions: StateFlow<SavedSearchActionUiState> = mutableSavedSearchActions
    private val metadataBackupController = PaperReaderMetadataBackupController(
        logic = logic,
        sessionStore = metadataRestoreSessionStore,
        scope = viewModelScope,
    )
    val metadataBackup: StateFlow<MetadataBackupUiState> = metadataBackupController.state
    private val localPdfImportController = PaperReaderLocalPdfImportController(logic, viewModelScope)
    val localPdfImport: StateFlow<LocalPdfImportUiState> = localPdfImportController.state
    private val extensionStoreMutex = Mutex()
    private val mutableExtensionStoreAction = MutableStateFlow<ExtensionStoreActionUiState>(ExtensionStoreActionUiState.Idle)
    val extensionStoreAction: StateFlow<ExtensionStoreActionUiState> = mutableExtensionStoreAction

    fun previewExtensionStore(indexUrl: String, publicKeyBase64: String) {
        if (mutableExtensionStoreAction.value is ExtensionStoreActionUiState.Working) return
        mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Working(ExtensionStoreOperation.PREVIEW)
        viewModelScope.launch {
            extensionStoreMutex.withLock {
                mutableExtensionStoreAction.value = try {
                    ExtensionStoreActionUiState.PreviewReady(
                        logic.extensionStores.preview(indexUrl, publicKeyBase64),
                    )
                } catch (cancelled: CancellationException) {
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Idle
                    throw cancelled
                } catch (error: Exception) {
                    ExtensionStoreActionUiState.Failed(
                        ExtensionStoreOperation.PREVIEW,
                        error.extensionStoreMessage("Extension store verification failed"),
                    )
                }
            }
        }
    }

    fun confirmExtensionStore() {
        val preview = (mutableExtensionStoreAction.value as? ExtensionStoreActionUiState.PreviewReady)?.preview ?: return
        mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Working(ExtensionStoreOperation.ADD)
        viewModelScope.launch {
            extensionStoreMutex.withLock {
                try {
                    logic.extensionStores.addPreview(preview.token)
                    logic.reconcileSourceExtensions()
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Idle
                } catch (cancelled: CancellationException) {
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.PreviewReady(preview)
                    throw cancelled
                } catch (error: Exception) {
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Failed(
                        ExtensionStoreOperation.ADD,
                        error.extensionStoreMessage("Extension store could not be added"),
                    )
                }
            }
        }
    }

    fun refreshExtensionStore(storeId: String) {
        runExtensionStoreMutation(ExtensionStoreOperation.REFRESH) {
            logic.extensionStores.refresh(storeId)
            logic.reconcileSourceExtensions()
        }
    }

    fun removeExtensionStore(storeId: String) {
        runExtensionStoreMutation(ExtensionStoreOperation.REMOVE) {
            logic.extensionStores.remove(storeId)
            logic.reconcileSourceExtensions()
        }
    }

    fun reconcileSourceExtensions() {
        viewModelScope.launch {
            try {
                logic.reconcileSourceExtensions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The provider state keeps the last verified runtime; store actions surface their own errors.
            }
        }
    }

    fun dismissExtensionStoreAction() {
        if (mutableExtensionStoreAction.value !is ExtensionStoreActionUiState.Working) {
            mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Idle
        }
    }

    private fun runExtensionStoreMutation(
        operation: ExtensionStoreOperation,
        action: suspend () -> Unit,
    ) {
        if (mutableExtensionStoreAction.value is ExtensionStoreActionUiState.Working) return
        mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Working(operation)
        viewModelScope.launch {
            extensionStoreMutex.withLock {
                try {
                    action()
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Idle
                } catch (cancelled: CancellationException) {
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Idle
                    throw cancelled
                } catch (error: Exception) {
                    mutableExtensionStoreAction.value = ExtensionStoreActionUiState.Failed(
                        operation,
                        error.extensionStoreMessage("Extension store operation failed"),
                    )
                }
            }
        }
    }

    fun search(query: String) {
        val submitted = query.trim()
        if (submitted.isEmpty()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutableSearch.value = SearchUiState(
            submittedQuery = submitted,
            running = true,
        )
        searchJob = viewModelScope.launch {
            val records = mutableListOf<dev.paperreader.logic.provider.RemotePaper>()
            val failures = linkedMapOf<String, ProviderSearchFailure>()
            try {
                logic.useCases.searchPapers.subscribe(PaperSearchQuery(submitted)).collect { event ->
                    if (generation != searchGeneration) return@collect
                    when (event) {
                        is FederatedSearchEvent.Started -> {
                            mutableSearch.value = mutableSearch.value.copy(
                                providerCount = event.providerCount,
                                providerIds = event.providerIds,
                            )
                        }

                        is FederatedSearchEvent.PageReceived -> {
                            records += event.page.items
                            mutableSearch.value = mutableSearch.value.copy(
                                results = SearchResultRanker.rank(
                                    submitted,
                                    SearchResultClusterer.cluster(records),
                                ).map { cluster ->
                                    val names = providers.value.installed.associate {
                                        it.descriptor.id to it.descriptor.displayName
                                    }
                                    cluster.toSearchPaperUi(names)
                                },
                            )
                        }

                        is FederatedSearchEvent.ProviderFailed -> {
                            val providerName = providers.value.installed
                                .firstOrNull { it.descriptor.id == event.providerId }
                                ?.descriptor
                                ?.displayName
                                ?: event.providerId
                            failures[event.providerId] = event.error.toUiFailure(event.providerId, providerName)
                            mutableSearch.value = mutableSearch.value.copy(failures = failures.values.toList())
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
        mutableSearch.value = SearchUiState()
    }

    fun save(result: SearchPaperUi) {
        val current = mutableSearch.value
        if (result.key in current.savingKeys || result.key in current.savedWorkIds) return
        mutableSearch.value = current.copy(
            savingKeys = current.savingKeys + result.key,
            saveFailedKeys = current.saveFailedKeys - result.key,
        )
        viewModelScope.launch {
            try {
                val workId = logic.useCases.saveSearchResult.await(SearchResultCluster(result.records))
                mutableSearch.value = mutableSearch.value.copy(
                    savingKeys = mutableSearch.value.savingKeys - result.key,
                    savedWorkIds = mutableSearch.value.savedWorkIds + (result.key to workId.value),
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
        viewModelScope.launch {
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
        viewModelScope.launch { refreshSavedSearchNow(id) }
    }

    fun deleteSavedSearch(searchId: String) {
        val id = runCatching { SavedSearchId(searchId) }.getOrNull() ?: return
        val current = mutableSavedSearchActions.value
        if (searchId in current.deletingSearchIds) return
        mutableSavedSearchActions.value = current.copy(
            deletingSearchIds = current.deletingSearchIds + searchId,
            failedSearchActionIds = current.failedSearchActionIds - searchId,
        )
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    fun removeHistory(workId: String) {
        viewModelScope.launch {
            logic.useCases.removeReadingHistory.await(WorkId(workId))
        }
    }

    fun setReadingStatus(workId: String, status: ReadingStatus) {
        viewModelScope.launch {
            logic.useCases.setReadingStatus.await(WorkId(workId), status)
        }
    }

    suspend fun removePaper(workId: String): RemovePaperResult =
        logic.useCases.removePaper.await(WorkId(workId))

    suspend fun createCollection(name: String): CreateCollectionResult =
        logic.useCases.createCollection.await(name)

    suspend fun renameCollection(id: Long, name: String): RenameCollectionResult =
        logic.useCases.renameCollection.await(CollectionId(id), name)

    suspend fun deleteCollection(id: Long): DeleteCollectionResult =
        logic.useCases.deleteCollection.await(CollectionId(id))

    suspend fun setPaperCollections(
        workId: String,
        collectionIds: Set<Long>,
    ): SetPaperCollectionsResult = logic.useCases.setPaperCollections.await(
        WorkId(workId),
        collectionIds.map(::CollectionId).toSet(),
    )

    fun requestDownload(workId: String, manifestationId: String) {
        val current = mutableDownloadActions.value
        if (manifestationId in current.requestingManifestations) return
        mutableDownloadActions.value = current.copy(
            requestingManifestations = current.requestingManifestations + manifestationId,
            failedManifestations = current.failedManifestations - manifestationId,
        )
        viewModelScope.launch {
            try {
                when (
                    val result = logic.downloads.requestDownload(
                        WorkId(workId),
                        ManifestationId(manifestationId),
                    )
                ) {
                    is DownloadRequestResult.Enqueued -> downloadWorkScheduler.enqueue(result.task.id)
                    is DownloadRequestResult.AlreadyDownloaded -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableDownloadActions.value = mutableDownloadActions.value.copy(
                    failedManifestations = mutableDownloadActions.value.failedManifestations + manifestationId,
                )
            } finally {
                mutableDownloadActions.value = mutableDownloadActions.value.copy(
                    requestingManifestations = mutableDownloadActions.value.requestingManifestations - manifestationId,
                )
            }
        }
    }

    fun cancelDownloadTask(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        when (logic.downloads.cancelDownload(id)) {
            is CancelTaskResult.Cancelled,
            is CancelTaskResult.AlreadyTerminal,
            -> downloadWorkScheduler.cancel(id)

            CancelTaskResult.NotFound -> Unit
        }
    }

    fun retryDownloadTask(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        downloadWorkScheduler.cancel(id)
        when (val result = logic.downloads.retryDownload(id)) {
            is RetryDownloadResult.Enqueued -> downloadWorkScheduler.enqueue(result.task.id)
            is RetryDownloadResult.NotRetryable -> if (
                result.task.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
            ) {
                downloadWorkScheduler.enqueue(result.task.id)
            }
            is RetryDownloadResult.AlreadyDownloaded,
            RetryDownloadResult.NotFound,
            -> Unit
        }
    }

    fun removeDownloadTask(taskId: String) = performTaskAction(taskId) {
        val id = TaskId(taskId)
        downloadWorkScheduler.cancel(id)
        if (logic.downloads.removeDownloadTask(id) == RemoveTaskResult.Active) {
            downloadWorkScheduler.enqueue(id)
        }
    }

    suspend fun downloadedPaper(manifestationId: String): DownloadedPaper? =
        logic.downloads.downloadedPaper(ManifestationId(manifestationId))

    suspend fun deleteDownload(workId: String, manifestationId: String): DeleteDownloadResult =
        logic.downloads.deleteDownload(WorkId(workId), ManifestationId(manifestationId))

    fun prepareLocalPdf(sourceUri: String): Boolean = localPdfImportController.prepare(sourceUri)

    fun confirmLocalPdfImport(title: String) = localPdfImportController.confirm(title)

    fun dismissLocalPdfImport() = localPdfImportController.dismiss()

    fun createMetadataBackup(
        write: suspend (MetadataBackupExport) -> Unit,
    ) = metadataBackupController.create(write)

    fun previewMetadataRestore(read: suspend () -> ByteArray) = metadataBackupController.previewRestore(read)

    fun confirmMetadataRestore() = metadataBackupController.confirmRestore()

    fun dismissMetadataBackupState() = metadataBackupController.dismiss()

    private fun performTaskAction(taskId: String, action: suspend () -> Unit) {
        val current = mutableDownloadActions.value
        if (taskId in current.actingTaskIds) return
        mutableDownloadActions.value = current.copy(
            actingTaskIds = current.actingTaskIds + taskId,
            failedTaskIds = current.failedTaskIds - taskId,
        )
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableDownloadActions.value = mutableDownloadActions.value.copy(
                    failedTaskIds = mutableDownloadActions.value.failedTaskIds + taskId,
                )
            } finally {
                mutableDownloadActions.value = mutableDownloadActions.value.copy(
                    actingTaskIds = mutableDownloadActions.value.actingTaskIds - taskId,
                )
            }
        }
    }

    private fun <T, R> Flow<T>.asLoadState(transform: (T) -> R): StateFlow<LoadState<R>> =
        map<T, LoadState<R>> { LoadState.Ready(transform(it)) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(LoadState.Failed)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = LoadState.Loading,
            )

    companion object {
        internal fun factory(
            logic: PaperReaderLogic,
            downloadWorkScheduler: DownloadWorkScheduler,
            metadataRestoreSessionStore: MetadataRestoreSessionStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PaperReaderViewModel(logic, downloadWorkScheduler, metadataRestoreSessionStore)
            }
        }
    }
}

private fun Exception.extensionStoreMessage(fallback: String): String =
    message?.trim()?.take(180)?.takeIf(String::isNotBlank) ?: fallback

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
