package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPaperRepairTest {
    @Test
    fun `identifier-only record is repaired with a readable candidate`() = runTest {
        val repository = FakeLibraryRepository(identifierOnlyPaper())
        var requested: RemotePaper? = null
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
                ),
            ),
        )
        val repair = RepairSavedPaper(repository) { paper ->
            requested = paper
            listOf(paper, resolved)
        }

        repair.await(WorkId("work-1"))

        assertEquals("1706.03762", requested?.identifiers?.single()?.value)
        assertEquals(listOf(resolved), repository.saved)
    }

    @Test
    fun `paper with a manifestation is not queried or rewritten`() = runTest {
        val existing = identifierOnlyPaper().copy(
            manifestations = listOf(
                dev.paperreader.logic.domain.PaperManifestation(
                    id = ManifestationId("manifestation-1"),
                    workId = WorkId("work-1"),
                    type = ManifestationType.PREPRINT,
                    sourceProvider = "arxiv",
                    sourceRecordId = "1706.03762v2",
                    updatedAt = Instant.EPOCH,
                ),
            ),
        )
        val repository = FakeLibraryRepository(existing)
        var calls = 0
        val result = RepairSavedPaper(repository) {
            calls += 1
            error("should not query")
        }.await(WorkId("work-1"))

        assertEquals(existing, result)
        assertEquals(0, calls)
        assertTrue(repository.saved.isEmpty())
    }

    private fun identifierOnlyPaper() = LibraryPaper(
        work = PaperWork(
            id = WorkId("work-1"),
            title = "Attention Is All You Need",
            abstractText = "Abstract",
            authors = listOf(PaperAuthor("A. Author")),
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
        manifestations = emptyList(),
    )

    private class FakeLibraryRepository(
        private val current: LibraryPaper,
    ) : LibraryRepository {
        override val library: Flow<List<LibraryPaper>> = MutableStateFlow(listOf(current))
        override val collections: Flow<List<PaperCollection>> = MutableStateFlow(emptyList())
        val saved = mutableListOf<RemotePaper>()

        override suspend fun save(remote: RemotePaper): WorkId {
            saved += remote
            return current.work.id
        }

        override suspend fun get(workId: WorkId): LibraryPaper? =
            current.takeIf { it.work.id == workId }

        override suspend fun updateReadingState(state: ReadingState) = Unit
        override suspend fun remove(workId: WorkId): RemovePaperResult = RemovePaperResult.Removed
        override suspend fun createCollection(name: String): CreateCollectionResult =
            CreateCollectionResult.NameTaken
        override suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult =
            RenameCollectionResult.NotFound
        override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult =
            DeleteCollectionResult.NotFound
        override suspend fun setPaperCollections(
            workId: WorkId,
            collectionIds: Set<CollectionId>,
        ): SetPaperCollectionsResult = SetPaperCollectionsResult.PaperNotFound
    }
}
