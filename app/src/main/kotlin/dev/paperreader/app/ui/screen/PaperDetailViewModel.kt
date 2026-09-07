package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.toPaperCollectionUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.state.DownloadActionUiState
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.reader.ReadablePaperDocument
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.task.DeleteDownloadResult
import dev.paperreader.logic.task.DownloadRequestResult
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PaperDetailUiState(
    val paper: LoadState<PaperUi?> = LoadState.Loading,
    val collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    val tasks: LoadState<List<PaperTask>> = LoadState.Loading,
    val downloadActions: DownloadActionUiState = DownloadActionUiState(),
)

sealed interface PaperDetailAction {
    data class SetStatus(val status: ReadingStatus) : PaperDetailAction
    data object RepairSavedPaper : PaperDetailAction
    data class RequestDownload(val manifestationId: String) : PaperDetailAction
}

class PaperDetailViewModel(
    private val logic: PaperReaderLogic,
    private val downloadWorkScheduler: DownloadWorkScheduler,
    private val workId: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PaperDetailUiState())
    val uiState: StateFlow<PaperDetailUiState> = mutableUiState.asStateFlow()
    private val repairing = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            logic.useCases.observeLibrary
                .subscribe()
                .asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() }.firstOrNull { it.id == workId } }
                .collectLatest { paper -> update { copy(paper = paper) } }
        }
        viewModelScope.launch {
            logic.useCases.observeCollections
                .subscribe()
                .asLoadState(viewModelScope) { collections -> collections.map { it.toPaperCollectionUi() } }
                .collectLatest { collections -> update { copy(collections = collections) } }
        }
        viewModelScope.launch {
            logic.tasks.tasks.asLoadState(viewModelScope) { it }.collectLatest { tasks -> update { copy(tasks = tasks) } }
        }
    }

    fun onAction(action: PaperDetailAction) {
        when (action) {
            is PaperDetailAction.SetStatus -> setReadingStatus(action.status)
            PaperDetailAction.RepairSavedPaper -> repairSavedPaper()
            is PaperDetailAction.RequestDownload -> requestDownload(action.manifestationId)
        }
    }

    private fun setReadingStatus(status: ReadingStatus) {
        viewModelScope.launch { logic.useCases.setReadingStatus.await(WorkId(workId), status) }
    }

    private fun repairSavedPaper() {
        if (!repairing.add(workId)) return
        viewModelScope.launch {
            try {
                logic.useCases.repairSavedPaper.await(WorkId(workId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An optional source failure must not block the detail screen.
            } finally {
                repairing.remove(workId)
            }
        }
    }

    private fun requestDownload(manifestationId: String) {
        val current = mutableUiState.value.downloadActions
        if (manifestationId in current.requestingManifestations) return
        update {
            copy(
                downloadActions = current.copy(
                    requestingManifestations = current.requestingManifestations + manifestationId,
                    failedManifestations = current.failedManifestations - manifestationId,
                ),
            )
        }
        viewModelScope.launch {
            try {
                when (val result = logic.downloads.requestDownload(WorkId(workId), ManifestationId(manifestationId))) {
                    is DownloadRequestResult.Enqueued -> downloadWorkScheduler.enqueue(result.task.id)
                    is DownloadRequestResult.AlreadyDownloaded -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update { copy(downloadActions = downloadActions.copy(failedManifestations = downloadActions.failedManifestations + manifestationId)) }
            } finally {
                update { copy(downloadActions = downloadActions.copy(requestingManifestations = downloadActions.requestingManifestations - manifestationId)) }
            }
        }
    }

    suspend fun remove(): RemovePaperResult = logic.useCases.removePaper.await(WorkId(workId))

    suspend fun setCollections(collectionIds: Set<Long>): SetPaperCollectionsResult =
        logic.useCases.setPaperCollections.await(WorkId(workId), collectionIds.map(::CollectionId).toSet())

    suspend fun downloadedPaper(manifestationId: String): DownloadedPaper? =
        logic.downloads.downloadedPaper(ManifestationId(manifestationId))

    suspend fun loadReadablePaper(
        manifestationId: String,
        retainDocumentSha256: String?,
    ): ReadablePaperResult = logic.useCases.loadReadablePaper.await(
        WorkId(workId),
        ManifestationId(manifestationId),
        retainDocumentSha256,
    )

    suspend fun readReadablePaperAsset(document: ReadablePaperDocument, assetId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            logic.openReadablePaperAsset(document, assetId)?.inputStream?.use { it.readBytes() }
        }

    suspend fun deleteDownload(manifestationId: String): DeleteDownloadResult =
        logic.downloads.deleteDownload(WorkId(workId), ManifestationId(manifestationId))

    private fun update(block: PaperDetailUiState.() -> PaperDetailUiState) {
        mutableUiState.value = mutableUiState.value.block()
    }
}
