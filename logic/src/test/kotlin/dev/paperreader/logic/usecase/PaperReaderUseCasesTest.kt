package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.domain.repository.ReadingBookmarkRepository
import dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperResult
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PaperReaderUseCasesTest {
    @Test
    fun `default optional repositories fail closed with explicit empty outcomes`() = runTest {
        val useCases = paperReaderUseCases(
            repository = FakeLibraryRepository(),
            historyRepository = FakeReadingHistoryRepository(),
            bookmarkRepository = FakeReadingBookmarkRepository(),
            search = FederatedPaperSearch(dev.paperreader.logic.provider.MutableProviderManager(emptyList())),
        )
        val workId = WorkId("missing")
        val selection = AnnotationSelection(
            documentSha256 = "a".repeat(64),
            blockId = "prx-b00001",
            startOffset = 0,
            endOffset = 4,
            quotePrefix = "",
            quoteExact = "Text",
            quoteSuffix = "",
        )

        assertTrue(useCases.observeAnnotations.subscribe(workId, "a".repeat(64)).first().isEmpty())
        assertEquals(dev.paperreader.logic.domain.repository.SaveAnnotationResult.DocumentNotCurrent,
            useCases.saveAnnotation.await(workId, selection, null))
        assertEquals(dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult.NotFound,
            useCases.updateAnnotationNote.await("missing", "note"))
        assertEquals(dev.paperreader.logic.domain.repository.RemoveAnnotationResult.NotFound,
            useCases.removeAnnotation.await("missing"))

        assertEquals(dev.paperreader.logic.domain.LocalPdfImportFailure.SOURCE_UNAVAILABLE,
            (useCases.prepareLocalPdf.await("content://missing") as dev.paperreader.logic.domain.PrepareLocalPdfResult.Rejected).reason)
        assertEquals(null, useCases.recoverPendingLocalPdf.await())
        assertEquals(dev.paperreader.logic.domain.LocalPdfImportFailure.SOURCE_UNAVAILABLE,
            (useCases.importLocalPdf.await("token", "Paper") as dev.paperreader.logic.domain.LocalPdfImportResult.Rejected).reason)
        useCases.discardPendingLocalPdf.await("token")

        assertTrue(useCases.createMetadataBackup.await() is dev.paperreader.logic.backup.MetadataBackupExportResult.Rejected)
        assertTrue(useCases.previewMetadataRestore.await(ByteArray(0)) is dev.paperreader.logic.backup.MetadataRestorePreviewResult.Rejected)
        assertTrue(useCases.restoreMetadataBackup.await(ByteArray(0)) is dev.paperreader.logic.backup.MetadataRestoreResult.Rejected)

        assertTrue(useCases.observeSavedSearches.subscribe().first().isEmpty())
        assertEquals(dev.paperreader.logic.domain.repository.CreateSavedSearchResult.NoProviders,
            useCases.createSavedSearch.await("query", emptySet()))
        assertEquals(dev.paperreader.logic.domain.repository.DeleteSavedSearchResult.NotFound,
            useCases.deleteSavedSearch.await(dev.paperreader.logic.domain.SavedSearchId("missing")))
        assertEquals(RefreshSavedSearchResult.NotFound,
            useCases.refreshSavedSearch.await(dev.paperreader.logic.domain.SavedSearchId("missing")))
        assertEquals(RefreshAllSavedSearchesResult(0, 0, 0, 0), useCases.refreshAllSavedSearches.await())
        assertFalse(useCases.markSavedSearchHitRead.await(SavedSearchHitId("missing")))
        assertEquals(SaveSavedSearchHitResult.NotFound,
            useCases.saveSavedSearchHit.await(SavedSearchHitId("missing")))
    }

    @Test
    fun `use cases are a thin UI boundary over repositories and search`() = runTest {
        val repository = FakeLibraryRepository()
        val provider = object : PaperProvider {
            override val descriptor = ProviderDescriptor(
                "fixture",
                "Fixture",
                0,
                roles = setOf(ProviderRole.SEARCH_ENGINE),
            )
            override suspend fun search(query: PaperSearchQuery) =
                ProviderPage(listOf(RemotePaper("fixture", "r-1", "Result")))
            override suspend fun get(recordId: String): RemotePaper? = null
        }
        val history = FakeReadingHistoryRepository()
        val readableManifestation = PaperManifestation(
            id = ManifestationId("manifestation-remove-readable"),
            workId = WorkId("w-saved"),
            type = ManifestationType.PREPRINT,
            sourceProvider = "arxiv",
            sourceRecordId = "2501.04510v2",
            version = "v2",
            updatedAt = Instant.EPOCH,
        )
        val secondReadableManifestation = readableManifestation.copy(
            id = ManifestationId("manifestation-remove-readable-2"),
            sourceRecordId = "1706.03762v7",
            version = "v7",
        )
        val released = mutableListOf<PaperManifestation>()
        val removalEvents = mutableListOf<String>()
        repository.onRemove = { removalEvents += "repository" }
        val useCases = paperReaderUseCases(
            repository,
            history,
            FakeReadingBookmarkRepository(),
            FederatedPaperSearch(listOf(provider)),
            removeReadableArtifacts = {
                released += it
                removalEvents += "artifact:${it.id.value}"
            },
        )
        val remote = RemotePaper("fixture", "r-1", "Result")

        assertEquals(emptyList<LibraryPaper>(), useCases.observeLibrary.subscribe().first())
        assertEquals(WorkId("w-saved"), useCases.savePaper.await(remote))
        assertEquals(
            WorkId("w-saved"),
            useCases.saveSearchResult.await(SearchResultCluster(listOf(remote, remote.copy(providerRecordId = "r-2")))),
        )
        useCases.updateReadingState.await(
            ReadingState(workId = WorkId("w-saved"), updatedAt = Instant.EPOCH),
        )
        val events = useCases.searchPapers.subscribe(PaperSearchQuery("result"))
        assertEquals(FederatedSearchEvent.Started(setOf("fixture")), events.first())
        assertEquals(
            listOf(remote, remote, remote.copy(providerRecordId = "r-2")),
            repository.saved,
        )
        assertEquals(WorkId("w-saved"), repository.readingState?.workId)
        repository.paper = LibraryPaper(
            work = PaperWork(WorkId("w-saved"), "Result", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            manifestations = listOf(readableManifestation, secondReadableManifestation),
        )
        assertEquals(RemovePaperResult.Removed, useCases.removePaper.await(WorkId("w-saved")))
        assertEquals(listOf(readableManifestation, secondReadableManifestation), released)
        assertEquals(
            listOf(
                "repository",
                "artifact:${readableManifestation.id.value}",
                "artifact:${secondReadableManifestation.id.value}",
            ),
            removalEvents,
        )

        val blockedRepository = FakeLibraryRepository().apply {
            paper = repository.paper
            removeResult = RemovePaperResult.HasActiveTasks
        }
        val blockedCleanup = mutableListOf<PaperManifestation>()
        val blockedUseCases = paperReaderUseCases(
            blockedRepository,
            history,
            FakeReadingBookmarkRepository(),
            FederatedPaperSearch(emptyList()),
            removeReadableArtifacts = { blockedCleanup += it },
        )
        assertEquals(
            RemovePaperResult.HasActiveTasks,
            blockedUseCases.removePaper.await(WorkId("w-saved")),
        )
        assertTrue(blockedCleanup.isEmpty())

        val cleanupFailureRepository = FakeLibraryRepository().apply { paper = repository.paper }
        val attemptedCleanup = mutableListOf<PaperManifestation>()
        val cleanupFailureUseCases = paperReaderUseCases(
            cleanupFailureRepository,
            history,
            FakeReadingBookmarkRepository(),
            FederatedPaperSearch(emptyList()),
            removeReadableArtifacts = {
                attemptedCleanup += it
                error("storage unavailable")
            },
        )
        assertEquals(
            RemovePaperResult.Removed,
            cleanupFailureUseCases.removePaper.await(WorkId("w-saved")),
        )
        assertEquals(listOf(readableManifestation, secondReadableManifestation), attemptedCleanup)
        assertEquals(1, cleanupFailureRepository.removeCalls)
        useCases.recordReadingSession.await(WorkId("w-saved"), Instant.EPOCH, Duration.ofMinutes(4))
        assertEquals(Duration.ofMinutes(4), history.duration)
    }

    @Test
    fun `saving identifier-only result persists an exact readable content manifestation`() = runTest {
        val repository = FakeLibraryRepository()
        val resolved = RemotePaper(
            providerId = "arxiv",
            providerRecordId = "1706.03762v2",
            title = "Attention Is All You Need",
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
            manifestations = listOf(
                RemoteManifestation(
                    type = ManifestationType.PREPRINT,
                    version = "v2",
                    landingPageUrl = "https://arxiv.org/abs/1706.03762",
                    pdfUrl = "https://arxiv.org/pdf/1706.03762",
                ),
            ),
        )
        val contentSource = object : PaperProvider {
            override val descriptor = ProviderDescriptor(
                id = "arxiv",
                displayName = "arXiv",
                minimumRequestIntervalMillis = 0,
                roles = setOf(ProviderRole.CONTENT_SOURCE),
                capabilities = setOf(ProviderCapability.DISCOVERY),
                identifierLookupTypes = setOf(IdentifierType.ARXIV),
            )

            override suspend fun search(query: PaperSearchQuery): ProviderPage = ProviderPage(listOf(resolved))
            override suspend fun get(recordId: String): RemotePaper? = null
        }
        val original = RemotePaper(
            providerId = "semanticscholar",
            providerRecordId = "s2-1",
            title = "Attention Is All You Need",
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
        )
        val useCases = paperReaderUseCases(
            repository = repository,
            historyRepository = FakeReadingHistoryRepository(),
            bookmarkRepository = FakeReadingBookmarkRepository(),
            search = FederatedPaperSearch(emptyList()),
            providerManager = dev.paperreader.logic.provider.MutableProviderManager(listOf(contentSource)),
        )

        assertEquals(WorkId("w-saved"), useCases.saveSearchResult.await(SearchResultCluster(listOf(original))))
        assertEquals(listOf(original, resolved), repository.saved)
    }

    @Test
    fun `set reading status preserves the concrete document locator`() = runTest {
        val repository = FakeLibraryRepository()
        val workId = WorkId("w-saved")
        val original = ReadingState(
            workId = workId,
            locator = ReadingLocator(
                documentSha256 = "a".repeat(64),
                blockId = "block-7",
                characterOffset = 31,
                pageIndex = 4,
                progression = 0.42,
            ),
            status = ReadingStatus.READING,
            updatedAt = Instant.EPOCH,
        )
        repository.paper = LibraryPaper(
            work = PaperWork(
                id = workId,
                title = "Saved paper",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
            manifestations = emptyList(),
            readingState = original,
        )
        val updatedAt = Instant.parse("2026-08-11T12:00:00Z")

        val changed = SetReadingStatus(repository, now = { updatedAt })
            .await(workId, ReadingStatus.FINISHED)

        assertTrue(changed)
        assertEquals(ReadingStatus.FINISHED, repository.readingState?.status)
        assertEquals(original.locator, repository.readingState?.locator)
        assertEquals(updatedAt, repository.readingState?.updatedAt)
    }

    @Test
    fun `readable paper use case resolves only a manifestation owned by the requested work`() = runTest {
        val repository = FakeLibraryRepository()
        val workId = WorkId("work-readable")
        val manifestationId = ManifestationId("manifestation-readable")
        val manifestation = PaperManifestation(
            id = manifestationId,
            workId = workId,
            type = ManifestationType.PREPRINT,
            sourceProvider = "arxiv",
            sourceRecordId = "2501.04510v2",
            version = "v2",
            updatedAt = Instant.EPOCH,
        )
        repository.paper = LibraryPaper(
            work = PaperWork(workId, "CGP-Tuning", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            manifestations = listOf(manifestation),
        )
        var receivedTitle: String? = null
        var receivedRetainedSha: String? = null
        val loader = ReadablePaperLoader { title, received, retainDocumentSha256 ->
            receivedTitle = title
            receivedRetainedSha = retainDocumentSha256
            assertEquals(manifestation, received)
            ReadablePaperResult.Unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND)
        }
        val useCase = LoadReadablePaper(repository, loader)

        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND),
            useCase.await(workId, manifestationId, retainDocumentSha256 = "a".repeat(64)),
        )
        assertEquals("CGP-Tuning", receivedTitle)
        assertEquals("a".repeat(64), receivedRetainedSha)
        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.MANIFESTATION_NOT_FOUND),
            useCase.await(workId, ManifestationId("other")),
        )
    }

    @Test
    fun `every public repository facade delegates values and explicit edge outcomes`() = runTest {
        val repository = FakeLibraryRepository().apply {
            createResult = CreateCollectionResult.InvalidName
            renameResult = RenameCollectionResult.NotFound
            deleteResult = DeleteCollectionResult.NotFound
            setResult = SetPaperCollectionsResult.PaperNotFound
            removeResult = RemovePaperResult.NotFound
        }
        val bookmarkRepository = FakeReadingBookmarkRepository().apply {
            toggleResult = ToggleReadingBookmarkResult.DocumentNotCurrent
            removeResult = RemoveReadingBookmarkResult.NotFound
        }
        val historyRepository = FakeReadingHistoryRepository()
        val useCases = paperReaderUseCases(
            repository,
            historyRepository,
            bookmarkRepository,
            FederatedPaperSearch(emptyList()),
        )
        val collectionId = CollectionId(1)
        val bookmarkId = ReadingBookmarkId("b".repeat(64))

        assertEquals(repository.collections, useCases.observeCollections.subscribe())
        assertEquals(RemovePaperResult.NotFound, useCases.removePaper.await(WorkId("missing")))
        assertEquals(null, useCases.getPaper.await(WorkId("missing")))
        assertEquals(CreateCollectionResult.InvalidName, useCases.createCollection.await("bad"))
        assertEquals(RenameCollectionResult.NotFound, useCases.renameCollection.await(collectionId, "bad"))
        assertEquals(DeleteCollectionResult.NotFound, useCases.deleteCollection.await(collectionId))
        assertEquals(SetPaperCollectionsResult.PaperNotFound, useCases.setPaperCollections.await(WorkId("missing"), setOf(collectionId)))
        assertEquals(bookmarkRepository.observeFlow, useCases.observeReadingBookmarks.subscribe(WorkId("w"), ManifestationId("m"), "a".repeat(64)))
        assertEquals(ToggleReadingBookmarkResult.DocumentNotCurrent, useCases.toggleReadingBookmark.await(WorkId("w"), ManifestationId("m"), "a".repeat(64), 0))
        assertEquals(RemoveReadingBookmarkResult.NotFound, useCases.removeReadingBookmark.await(bookmarkId))
        assertEquals(false, useCases.setReadingStatus.await(WorkId("missing"), ReadingStatus.FINISHED))
        assertEquals(historyRepository.history, useCases.observeReadingHistory.subscribe())
        useCases.removeReadingHistory.await(WorkId("missing"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCases.recordReadingSession.await(WorkId("w"), Instant.EPOCH, Duration.ofSeconds(-1)) }
        }
        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.PAPER_NOT_FOUND),
            useCases.loadReadablePaper.await(WorkId("missing"), ManifestationId("missing")),
        )
    }

    private class FakeLibraryRepository : LibraryRepository {
        override val library: Flow<List<LibraryPaper>> = MutableStateFlow(emptyList())
        override val collections: Flow<List<PaperCollection>> = MutableStateFlow(emptyList())
        val saved = mutableListOf<RemotePaper>()
        var readingState: ReadingState? = null
        var paper: LibraryPaper? = null
        var removeCalls = 0
        var onRemove: () -> Unit = {}
        var removeResult: RemovePaperResult = RemovePaperResult.Removed
        var createResult: CreateCollectionResult = CreateCollectionResult.NameTaken
        var renameResult: RenameCollectionResult = RenameCollectionResult.NotFound
        var deleteResult: DeleteCollectionResult = DeleteCollectionResult.NotFound
        var setResult: SetPaperCollectionsResult = SetPaperCollectionsResult.PaperNotFound

        override suspend fun save(remote: RemotePaper): WorkId {
            saved += remote
            return WorkId("w-saved")
        }

        override suspend fun get(workId: WorkId): LibraryPaper? = paper

        override suspend fun updateReadingState(state: ReadingState) {
            readingState = state
        }

        override suspend fun remove(workId: WorkId): RemovePaperResult {
            removeCalls += 1
            onRemove()
            return removeResult
        }
        override suspend fun createCollection(name: String): CreateCollectionResult = createResult
        override suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult = renameResult
        override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult = deleteResult
        override suspend fun setPaperCollections(
            workId: WorkId,
            collectionIds: Set<CollectionId>,
        ): SetPaperCollectionsResult = setResult
    }

    private class FakeReadingHistoryRepository : ReadingHistoryRepository {
        override val history: Flow<List<ReadingHistoryEntry>> = MutableStateFlow(emptyList())
        var duration: Duration? = null

        override suspend fun record(workId: WorkId, readAt: Instant, duration: Duration) {
            this.duration = duration
        }

        override suspend fun remove(workId: WorkId) = Unit
    }

    private class FakeReadingBookmarkRepository : ReadingBookmarkRepository {
        val observeFlow = MutableStateFlow<List<ReadingBookmark>>(emptyList())
        var toggleResult: ToggleReadingBookmarkResult = ToggleReadingBookmarkResult.DocumentNotCurrent
        var removeResult: RemoveReadingBookmarkResult = RemoveReadingBookmarkResult.NotFound
        override fun observe(
            workId: WorkId,
            manifestationId: ManifestationId,
            documentSha256: String,
        ): Flow<List<ReadingBookmark>> = observeFlow

        override suspend fun toggle(
            workId: WorkId,
            manifestationId: ManifestationId,
            documentSha256: String,
            pageIndex: Int,
        ): ToggleReadingBookmarkResult = toggleResult

        override suspend fun remove(id: ReadingBookmarkId): RemoveReadingBookmarkResult =
            removeResult
    }
}
