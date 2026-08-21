package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.AnnotationRepository
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import dev.paperreader.logic.domain.repository.LocalPdfImportRepository
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.domain.repository.MetadataBackupRepository
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import dev.paperreader.logic.domain.repository.ReadingBookmarkRepository
import dev.paperreader.logic.domain.repository.SavedSearchRepository
import dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class ObserveLibrary(private val repository: LibraryRepository) {
    fun subscribe(): Flow<List<LibraryPaper>> = repository.library
}

class ObserveCollections(private val repository: LibraryRepository) {
    fun subscribe(): Flow<List<PaperCollection>> = repository.collections
}

class SavePaper(
    private val repository: LibraryRepository,
    private val enricher: SavedPaperEnricher = SavedPaperEnricher { listOf(it) },
) {
    suspend fun await(paper: RemotePaper): WorkId {
        val workIds = enricher.enrich(paper).map { repository.save(it) }
        val workId = workIds.first()
        check(workIds.all { it == workId }) { "Exact paper enrichment saved multiple works" }
        return workId
    }
}

class SaveSearchResult(
    private val repository: LibraryRepository,
    private val enricher: SavedPaperEnricher = SavedPaperEnricher { listOf(it) },
) {
    suspend fun await(result: SearchResultCluster): WorkId {
        val workIds = result.records.flatMap { enricher.enrich(it) }.map { repository.save(it) }
        val workId = workIds.first()
        check(workIds.all { it == workId }) { "Exact search cluster saved as multiple works" }
        return workId
    }
}

class GetPaper(private val repository: LibraryRepository) {
    suspend fun await(workId: WorkId): LibraryPaper? = repository.get(workId)
}

class RemovePaper(
    private val repository: LibraryRepository,
    private val removeReadableArtifacts: suspend (PaperManifestation) -> Unit = {},
) {
    suspend fun await(workId: WorkId): RemovePaperResult {
        val manifestations = repository.get(workId)?.manifestations.orEmpty()
        val result = repository.remove(workId)
        if (result == RemovePaperResult.Removed) {
            withContext(NonCancellable) {
                manifestations.forEach { manifestation ->
                    runCatching { removeReadableArtifacts(manifestation) }
                }
            }
        }
        return result
    }
}

class CreateCollection(private val repository: LibraryRepository) {
    suspend fun await(name: String): CreateCollectionResult = repository.createCollection(name)
}

class RenameCollection(private val repository: LibraryRepository) {
    suspend fun await(id: CollectionId, name: String): RenameCollectionResult =
        repository.renameCollection(id, name)
}

class DeleteCollection(private val repository: LibraryRepository) {
    suspend fun await(id: CollectionId): DeleteCollectionResult = repository.deleteCollection(id)
}

class SetPaperCollections(private val repository: LibraryRepository) {
    suspend fun await(
        workId: WorkId,
        collectionIds: Set<CollectionId>,
    ): SetPaperCollectionsResult = repository.setPaperCollections(workId, collectionIds)
}

class UpdateReadingState(private val repository: LibraryRepository) {
    suspend fun await(state: ReadingState) = repository.updateReadingState(state)
}

class ObserveReadingBookmarks(private val repository: ReadingBookmarkRepository) {
    fun subscribe(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
    ): Flow<List<ReadingBookmark>> = repository.observe(workId, manifestationId, documentSha256)
}

class ToggleReadingBookmark(private val repository: ReadingBookmarkRepository) {
    suspend fun await(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
        pageIndex: Int,
    ): ToggleReadingBookmarkResult = repository.toggle(
        workId,
        manifestationId,
        documentSha256,
        pageIndex,
    )
}

class RemoveReadingBookmark(private val repository: ReadingBookmarkRepository) {
    suspend fun await(id: ReadingBookmarkId): RemoveReadingBookmarkResult = repository.remove(id)
}

class ObserveAnnotations(private val repository: AnnotationRepository) {
    fun subscribe(workId: WorkId, documentSha256: String): Flow<List<Annotation>> =
        repository.observe(workId, documentSha256)
}

class SaveAnnotation(private val repository: AnnotationRepository) {
    suspend fun await(
        workId: WorkId,
        selection: AnnotationSelection,
        note: String?,
    ): SaveAnnotationResult = repository.save(workId, selection, note)
}

class UpdateAnnotationNote(private val repository: AnnotationRepository) {
    suspend fun await(id: String, note: String?): UpdateAnnotationNoteResult = repository.updateNote(id, note)
}

class RemoveAnnotation(private val repository: AnnotationRepository) {
    suspend fun await(id: String): RemoveAnnotationResult = repository.remove(id)
}

class SetReadingStatus(
    private val repository: LibraryRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun await(workId: WorkId, status: ReadingStatus): Boolean {
        val paper = repository.get(workId) ?: return false
        val changedAt = now()
        val state = (paper.readingState ?: ReadingState(workId = workId, updatedAt = changedAt)).copy(
            status = status,
            updatedAt = changedAt,
        )
        repository.updateReadingState(state)
        return true
    }
}

class SearchPapers(private val search: FederatedPaperSearch) {
    fun subscribe(query: PaperSearchQuery): Flow<FederatedSearchEvent> = search.search(query)
}

class ObserveReadingHistory(private val repository: ReadingHistoryRepository) {
    fun subscribe(): Flow<List<ReadingHistoryEntry>> = repository.history
}

class RecordReadingSession(private val repository: ReadingHistoryRepository) {
    suspend fun await(workId: WorkId, readAt: Instant, duration: Duration) {
        require(!duration.isNegative) { "Reading duration cannot be negative" }
        repository.record(workId, readAt, duration)
    }
}

class RemoveReadingHistory(private val repository: ReadingHistoryRepository) {
    suspend fun await(workId: WorkId) = repository.remove(workId)
}

class LoadReadablePaper internal constructor(
    private val repository: LibraryRepository,
    private val loader: ReadablePaperLoader,
) {
    suspend fun await(
        workId: WorkId,
        manifestationId: ManifestationId,
        retainDocumentSha256: String? = null,
    ): ReadablePaperResult {
        val paper = repository.get(workId)
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.PAPER_NOT_FOUND)
        val manifestation = paper.manifestations.firstOrNull { it.id == manifestationId }
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.MANIFESTATION_NOT_FOUND)
        return loader.load(paper.work.title, manifestation, retainDocumentSha256)
    }
}

class PrepareLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(sourceUri: String): PrepareLocalPdfResult = repository.prepare(sourceUri)
}

class RecoverPendingLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(): LocalPdfCandidate? = repository.recoverPending()
}

class ImportLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(importToken: String, title: String): LocalPdfImportResult =
        repository.import(importToken, title)
}

class DiscardPendingLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(importToken: String) = repository.discard(importToken)
}

class CreateMetadataBackup(private val repository: MetadataBackupRepository) {
    suspend fun await(): MetadataBackupExportResult = repository.export()
}

class PreviewMetadataRestore(private val repository: MetadataBackupRepository) {
    suspend fun await(bytes: ByteArray): MetadataRestorePreviewResult = repository.preview(bytes)
}

class RestoreMetadataBackup(private val repository: MetadataBackupRepository) {
    suspend fun await(bytes: ByteArray): MetadataRestoreResult = repository.restore(bytes)
}

data class PaperReaderUseCases(
    val observeLibrary: ObserveLibrary,
    val observeCollections: ObserveCollections,
    val savePaper: SavePaper,
    val saveSearchResult: SaveSearchResult,
    val getPaper: GetPaper,
    val repairSavedPaper: RepairSavedPaper,
    val removePaper: RemovePaper,
    val createCollection: CreateCollection,
    val renameCollection: RenameCollection,
    val deleteCollection: DeleteCollection,
    val setPaperCollections: SetPaperCollections,
    val updateReadingState: UpdateReadingState,
    val observeReadingBookmarks: ObserveReadingBookmarks,
    val toggleReadingBookmark: ToggleReadingBookmark,
    val removeReadingBookmark: RemoveReadingBookmark,
    val observeAnnotations: ObserveAnnotations,
    val saveAnnotation: SaveAnnotation,
    val updateAnnotationNote: UpdateAnnotationNote,
    val removeAnnotation: RemoveAnnotation,
    val setReadingStatus: SetReadingStatus,
    val searchPapers: SearchPapers,
    val observeReadingHistory: ObserveReadingHistory,
    val recordReadingSession: RecordReadingSession,
    val removeReadingHistory: RemoveReadingHistory,
    val loadReadablePaper: LoadReadablePaper,
    val prepareLocalPdf: PrepareLocalPdf,
    val recoverPendingLocalPdf: RecoverPendingLocalPdf,
    val importLocalPdf: ImportLocalPdf,
    val discardPendingLocalPdf: DiscardPendingLocalPdf,
    val createMetadataBackup: CreateMetadataBackup,
    val previewMetadataRestore: PreviewMetadataRestore,
    val restoreMetadataBackup: RestoreMetadataBackup,
    val observeSavedSearches: ObserveSavedSearches,
    val createSavedSearch: CreateSavedSearch,
    val deleteSavedSearch: DeleteSavedSearch,
    val refreshSavedSearch: RefreshSavedSearch,
    val refreshAllSavedSearches: RefreshAllSavedSearches,
    val markSavedSearchHitRead: MarkSavedSearchHitRead,
    val saveSavedSearchHit: SaveSavedSearchHit,
)

internal fun paperReaderUseCases(
    repository: LibraryRepository,
    historyRepository: ReadingHistoryRepository,
    bookmarkRepository: ReadingBookmarkRepository,
    search: FederatedPaperSearch,
    localPdfImportRepository: LocalPdfImportRepository = UnavailableLocalPdfImportRepository,
    metadataBackupRepository: MetadataBackupRepository = UnavailableMetadataBackupRepository,
    savedSearchRepository: SavedSearchRepository = UnavailableSavedSearchRepository,
    providerManager: dev.paperreader.logic.provider.ProviderManager =
        dev.paperreader.logic.provider.MutableProviderManager(emptyList()),
    readablePaperLoader: ReadablePaperLoader = ReadablePaperLoader { _, _, _ ->
        ReadablePaperResult.Unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
    },
    annotationRepository: AnnotationRepository = UnavailableAnnotationRepository,
    removeReadableArtifacts: suspend (PaperManifestation) -> Unit = {},
) : PaperReaderUseCases {
    val refreshSavedSearch = RefreshSavedSearch(savedSearchRepository, providerManager)
    val savedPaperEnricher = ReadableManifestationResolver(providerManager)
    return PaperReaderUseCases(
    observeLibrary = ObserveLibrary(repository),
    observeCollections = ObserveCollections(repository),
    savePaper = SavePaper(repository, savedPaperEnricher),
    saveSearchResult = SaveSearchResult(repository, savedPaperEnricher),
    getPaper = GetPaper(repository),
    repairSavedPaper = RepairSavedPaper(repository, savedPaperEnricher),
    removePaper = RemovePaper(repository, removeReadableArtifacts),
    createCollection = CreateCollection(repository),
    renameCollection = RenameCollection(repository),
    deleteCollection = DeleteCollection(repository),
    setPaperCollections = SetPaperCollections(repository),
    updateReadingState = UpdateReadingState(repository),
    observeReadingBookmarks = ObserveReadingBookmarks(bookmarkRepository),
    toggleReadingBookmark = ToggleReadingBookmark(bookmarkRepository),
    removeReadingBookmark = RemoveReadingBookmark(bookmarkRepository),
    observeAnnotations = ObserveAnnotations(annotationRepository),
    saveAnnotation = SaveAnnotation(annotationRepository),
    updateAnnotationNote = UpdateAnnotationNote(annotationRepository),
    removeAnnotation = RemoveAnnotation(annotationRepository),
    setReadingStatus = SetReadingStatus(repository),
    searchPapers = SearchPapers(search),
    observeReadingHistory = ObserveReadingHistory(historyRepository),
    recordReadingSession = RecordReadingSession(historyRepository),
    removeReadingHistory = RemoveReadingHistory(historyRepository),
    loadReadablePaper = LoadReadablePaper(repository, readablePaperLoader),
    prepareLocalPdf = PrepareLocalPdf(localPdfImportRepository),
    recoverPendingLocalPdf = RecoverPendingLocalPdf(localPdfImportRepository),
    importLocalPdf = ImportLocalPdf(localPdfImportRepository),
    discardPendingLocalPdf = DiscardPendingLocalPdf(localPdfImportRepository),
    createMetadataBackup = CreateMetadataBackup(metadataBackupRepository),
    previewMetadataRestore = PreviewMetadataRestore(metadataBackupRepository),
    restoreMetadataBackup = RestoreMetadataBackup(metadataBackupRepository),
    observeSavedSearches = ObserveSavedSearches(savedSearchRepository),
    createSavedSearch = CreateSavedSearch(savedSearchRepository),
    deleteSavedSearch = DeleteSavedSearch(savedSearchRepository),
    refreshSavedSearch = refreshSavedSearch,
    refreshAllSavedSearches = RefreshAllSavedSearches(savedSearchRepository, refreshSavedSearch),
    markSavedSearchHitRead = MarkSavedSearchHitRead(savedSearchRepository),
    saveSavedSearchHit = SaveSavedSearchHit(savedSearchRepository, repository, savedPaperEnricher),
)
}

private object UnavailableAnnotationRepository : AnnotationRepository {
    override fun observe(workId: WorkId, documentSha256: String): Flow<List<Annotation>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun save(
        workId: WorkId,
        selection: AnnotationSelection,
        note: String?,
    ): SaveAnnotationResult = SaveAnnotationResult.DocumentNotCurrent

    override suspend fun updateNote(id: String, note: String?): UpdateAnnotationNoteResult =
        UpdateAnnotationNoteResult.NotFound

    override suspend fun remove(id: String): RemoveAnnotationResult = RemoveAnnotationResult.NotFound
}

private object UnavailableLocalPdfImportRepository : LocalPdfImportRepository {
    override suspend fun prepare(sourceUri: String): PrepareLocalPdfResult =
        PrepareLocalPdfResult.Rejected(dev.paperreader.logic.domain.LocalPdfImportFailure.SOURCE_UNAVAILABLE)

    override suspend fun recoverPending(): LocalPdfCandidate? = null

    override suspend fun import(importToken: String, title: String): LocalPdfImportResult =
        LocalPdfImportResult.Rejected(dev.paperreader.logic.domain.LocalPdfImportFailure.SOURCE_UNAVAILABLE)

    override suspend fun discard(importToken: String) = Unit
}

private object UnavailableMetadataBackupRepository : MetadataBackupRepository {
    private val failure = dev.paperreader.logic.backup.MetadataBackupError.Rejected("unavailable", "Metadata backup is not configured")
    override suspend fun export(): MetadataBackupExportResult = MetadataBackupExportResult.Rejected(failure)
    override suspend fun preview(bytes: ByteArray): MetadataRestorePreviewResult = MetadataRestorePreviewResult.Rejected(failure)
    override suspend fun restore(bytes: ByteArray): MetadataRestoreResult = MetadataRestoreResult.Rejected(failure)
}

private object UnavailableSavedSearchRepository : SavedSearchRepository {
    override val feeds: Flow<List<dev.paperreader.logic.domain.SavedSearchFeed>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun get(id: dev.paperreader.logic.domain.SavedSearchId) = null
    override suspend fun getHit(id: dev.paperreader.logic.domain.SavedSearchHitId) = null
    override suspend fun create(
        queryText: String,
        providerIds: Set<String>,
    ) = dev.paperreader.logic.domain.repository.CreateSavedSearchResult.NoProviders

    override suspend fun delete(id: dev.paperreader.logic.domain.SavedSearchId) =
        dev.paperreader.logic.domain.repository.DeleteSavedSearchResult.NotFound

    override suspend fun recordSuccess(
        id: dev.paperreader.logic.domain.SavedSearchId,
        providerId: String,
        records: List<RemotePaper>,
        checkedAt: Instant,
    ) = 0

    override suspend fun recordFailure(
        id: dev.paperreader.logic.domain.SavedSearchId,
        providerId: String,
        failure: dev.paperreader.logic.domain.SavedSearchFailure,
        checkedAt: Instant,
    ) = Unit

    override suspend fun markHitRead(id: dev.paperreader.logic.domain.SavedSearchHitId) = false
    override suspend fun linkHit(
        id: dev.paperreader.logic.domain.SavedSearchHitId,
        workId: WorkId,
    ) = false
}
