package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
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
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperResult
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperReaderUseCasesTest {
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
        val useCases = paperReaderUseCases(
            repository,
            history,
            FakeReadingBookmarkRepository(),
            FederatedPaperSearch(listOf(provider)),
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
        assertEquals(RemovePaperResult.Removed, useCases.removePaper.await(WorkId("w-saved")))
        useCases.recordReadingSession.await(WorkId("w-saved"), Instant.EPOCH, Duration.ofMinutes(4))
        assertEquals(Duration.ofMinutes(4), history.duration)
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
        val loader = ReadablePaperLoader { title, received ->
            receivedTitle = title
            assertEquals(manifestation, received)
            ReadablePaperResult.Unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND)
        }
        val useCase = LoadReadablePaper(repository, loader)

        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND),
            useCase.await(workId, manifestationId),
        )
        assertEquals("CGP-Tuning", receivedTitle)
        assertEquals(
            ReadablePaperResult.Unavailable(ReadablePaperFailure.MANIFESTATION_NOT_FOUND),
            useCase.await(workId, ManifestationId("other")),
        )
    }

    private class FakeLibraryRepository : LibraryRepository {
        override val library: Flow<List<LibraryPaper>> = MutableStateFlow(emptyList())
        override val collections: Flow<List<PaperCollection>> = MutableStateFlow(emptyList())
        val saved = mutableListOf<RemotePaper>()
        var readingState: ReadingState? = null
        var paper: LibraryPaper? = null

        override suspend fun save(remote: RemotePaper): WorkId {
            saved += remote
            return WorkId("w-saved")
        }

        override suspend fun get(workId: WorkId): LibraryPaper? = paper

        override suspend fun updateReadingState(state: ReadingState) {
            readingState = state
        }

        override suspend fun remove(workId: WorkId): RemovePaperResult = RemovePaperResult.Removed
        override suspend fun createCollection(name: String): CreateCollectionResult = error("Not used")
        override suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult = error("Not used")
        override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult = error("Not used")
        override suspend fun setPaperCollections(
            workId: WorkId,
            collectionIds: Set<CollectionId>,
        ): SetPaperCollectionsResult = error("Not used")
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
        override fun observe(
            workId: WorkId,
            manifestationId: ManifestationId,
            documentSha256: String,
        ): Flow<List<ReadingBookmark>> = MutableStateFlow(emptyList())

        override suspend fun toggle(
            workId: WorkId,
            manifestationId: ManifestationId,
            documentSha256: String,
            pageIndex: Int,
        ): ToggleReadingBookmarkResult = ToggleReadingBookmarkResult.DocumentNotCurrent

        override suspend fun remove(id: ReadingBookmarkId): RemoveReadingBookmarkResult =
            RemoveReadingBookmarkResult.NotFound
    }
}
