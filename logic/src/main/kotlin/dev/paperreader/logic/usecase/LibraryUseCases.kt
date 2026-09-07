package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ObserveLibrary(private val repository: LibraryRepository) {
    fun subscribe(): Flow<List<LibraryPaper>> = repository.library

    fun subscribe(workId: WorkId): Flow<LibraryPaper?> = repository.observePaper(workId)

    fun subscribeCount(): Flow<Int> = repository.observePaperCount()
}

class ObserveCollections(private val repository: LibraryRepository) {
    fun subscribe(): Flow<List<PaperCollection>> = repository.collections

    fun subscribeCount(): Flow<Int> = repository.observeCollectionCount()
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
