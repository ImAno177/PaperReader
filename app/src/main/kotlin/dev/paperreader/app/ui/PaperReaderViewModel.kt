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
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.backup.MetadataBackupExport
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
import java.util.concurrent.CancellationException
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

data class DownloadActionUiState(
    val requestingManifestations: Set<String> = emptySet(),
    val failedManifestations: Set<String> = emptySet(),
    val actingTaskIds: Set<String> = emptySet(),
    val failedTaskIds: Set<String> = emptySet(),
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

    val providers = logic.providers.state
    val extensionStores = logic.extensionStores.state

    private val mutableDownloadActions = MutableStateFlow(DownloadActionUiState())
    val downloadActions: StateFlow<DownloadActionUiState> = mutableDownloadActions

    private val searchController = PaperReaderSearchController(logic, viewModelScope, providers)
    val search: StateFlow<SearchUiState> = searchController.search
    val savedSearches: StateFlow<LoadState<List<dev.paperreader.logic.domain.SavedSearchFeed>>> =
        searchController.savedSearches
    val savedSearchActions: StateFlow<SavedSearchActionUiState> = searchController.savedSearchActions
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

    fun setDisabledProviderIds(providerIds: Set<String>) {
        logic.setDisabledProviderIds(providerIds)
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

    fun search(query: String) = searchController.search(query)

    fun clearSearch() = searchController.clearSearch()

    fun save(result: SearchPaperUi) = searchController.save(result)

    fun createSavedSearch(query: String) = searchController.createSavedSearch(query)

    fun refreshSavedSearch(searchId: String) = searchController.refreshSavedSearch(searchId)

    fun deleteSavedSearch(searchId: String) = searchController.deleteSavedSearch(searchId)

    fun markSavedSearchHitRead(hitId: String) = searchController.markSavedSearchHitRead(hitId)

    fun saveSavedSearchHit(hitId: String) = searchController.saveSavedSearchHit(hitId)

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
