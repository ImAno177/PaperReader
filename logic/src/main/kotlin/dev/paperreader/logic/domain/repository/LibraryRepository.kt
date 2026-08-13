package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.flow.Flow

sealed interface RemovePaperResult {
    data object Removed : RemovePaperResult
    data object NotFound : RemovePaperResult
    data object HasLocalArtifacts : RemovePaperResult
    data object HasActiveTasks : RemovePaperResult
}

sealed interface CreateCollectionResult {
    data class Created(val collection: PaperCollection) : CreateCollectionResult
    data object InvalidName : CreateCollectionResult
    data object NameTaken : CreateCollectionResult
}

sealed interface RenameCollectionResult {
    data class Renamed(val collection: PaperCollection) : RenameCollectionResult
    data object NotFound : RenameCollectionResult
    data object InvalidName : RenameCollectionResult
    data object NameTaken : RenameCollectionResult
}

sealed interface DeleteCollectionResult {
    data object Deleted : DeleteCollectionResult
    data object NotFound : DeleteCollectionResult
}

sealed interface SetPaperCollectionsResult {
    data object Updated : SetPaperCollectionsResult
    data object PaperNotFound : SetPaperCollectionsResult
    data class CollectionNotFound(val id: CollectionId) : SetPaperCollectionsResult
}

interface LibraryRepository {
    val library: Flow<List<LibraryPaper>>
    val collections: Flow<List<PaperCollection>>

    suspend fun save(remote: RemotePaper): WorkId

    suspend fun get(workId: WorkId): LibraryPaper?

    suspend fun updateReadingState(state: ReadingState)

    suspend fun remove(workId: WorkId): RemovePaperResult

    suspend fun createCollection(name: String): CreateCollectionResult

    suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult

    suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult

    suspend fun setPaperCollections(
        workId: WorkId,
        collectionIds: Set<CollectionId>,
    ): SetPaperCollectionsResult
}
