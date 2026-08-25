package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.AnnotationRepository
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import dev.paperreader.logic.domain.repository.LocalPdfImportRepository
import dev.paperreader.logic.domain.repository.MetadataBackupRepository
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import dev.paperreader.logic.domain.repository.ReadingBookmarkRepository
import dev.paperreader.logic.domain.repository.SavedSearchRepository
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperResult
import kotlinx.coroutines.flow.Flow
import java.time.Instant

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
