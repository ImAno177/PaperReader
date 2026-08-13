package dev.paperreader.logic.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.paperreader.logic.data.CollectionEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.WorkCollectionEntity
import dev.paperreader.logic.data.mergeIdentifiers
import dev.paperreader.logic.data.mergeManifestations
import dev.paperreader.logic.data.mergeWork
import dev.paperreader.logic.data.stableWorkId
import dev.paperreader.logic.data.toDomain
import dev.paperreader.logic.data.toEntities
import dev.paperreader.logic.data.toEntity
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.normalizeCollectionName
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

internal class RoomLibraryRepository(
    private val database: LibraryDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : LibraryRepository {
    private val dao = database.libraryDao()

    override val library: Flow<List<dev.paperreader.logic.domain.LibraryPaper>> =
        dao.observeLibrary().map { rows -> rows.map { it.toDomain() } }

    override val collections: Flow<List<PaperCollection>> =
        dao.observeCollections().map { rows -> rows.map { it.toDomain() } }

    override suspend fun save(remote: RemotePaper): WorkId {
        val now = clock.instant()
        val providerIdentifier = PaperIdentifier(IdentifierType.PROVIDER, remote.providerRecordId, remote.providerId)
        val canonicalIdentifiers = (remote.identifiers + providerIdentifier).map(IdentifierNormalizer::canonical)
        val workId = database.withTransaction {
            var existingId: String? = null
            for (identifier in canonicalIdentifiers) {
                existingId = dao.findWorkId(identifier.type.name, identifier.value, identifier.authority.orEmpty())
                if (existingId != null) break
            }
            val id = existingId?.let(::WorkId) ?: stableWorkId(canonicalIdentifiers)
            val rows = remote.toEntities(id, now)
            val existing = dao.getWork(id.value)
            val existingAuthors = dao.getAuthors(id.value)
            val existingIdentifiers = dao.getIdentifiers(id.value)
            val existingManifestations = dao.getManifestations(id.value)
            dao.upsertWork(mergeWork(existing, rows.work))
            if (rows.authors.isNotEmpty() || existingAuthors.isEmpty()) {
                dao.deleteAuthors(id.value)
                dao.upsertAuthors(rows.authors)
            }
            // Identifiers and manifestations are an append/merge set. A provider refresh must
            // not discard an arXiv identifier or a locally discovered open-access file.
            dao.upsertIdentifiers(mergeIdentifiers(existingIdentifiers, rows.identifiers))
            dao.upsertManifestations(mergeManifestations(existingManifestations, rows.manifestations))
            id
        }
        return workId
    }

    override suspend fun get(workId: WorkId) = dao.getWork(workId.value)?.let { work ->
        work.toDomain(
            dao.getAuthors(work.id), dao.getIdentifiers(work.id),
            dao.getManifestations(work.id), dao.getReadingState(work.id),
            dao.getFilesForWork(work.id),
            dao.getCollectionsForWork(work.id),
        )
    }

    override suspend fun updateReadingState(state: ReadingState) {
        database.withTransaction { dao.upsertReadingState(state.toEntity()) }
    }

    override suspend fun remove(workId: WorkId): RemovePaperResult = database.withTransaction {
        if (dao.getWork(workId.value) == null) return@withTransaction RemovePaperResult.NotFound
        if (dao.countLocalArtifacts(workId.value) > 0) {
            return@withTransaction RemovePaperResult.HasLocalArtifacts
        }
        if (dao.countActiveTasks(workId.value) > 0) {
            return@withTransaction RemovePaperResult.HasActiveTasks
        }

        dao.deleteFiles(workId.value)
        dao.deleteAnnotations(workId.value)
        dao.deleteReadingBookmarks(workId.value)
        dao.deleteReadingState(workId.value)
        dao.deleteReadingHistory(workId.value)
        dao.deleteTasks(workId.value)
        dao.deleteManifestations(workId.value)
        dao.deleteIdentifiers(workId.value)
        dao.deleteAuthors(workId.value)
        check(dao.deleteWork(workId.value) == 1) { "Work disappeared during removal transaction" }
        RemovePaperResult.Removed
    }

    override suspend fun createCollection(name: String): CreateCollectionResult {
        val normalized = normalizeCollectionName(name) ?: return CreateCollectionResult.InvalidName
        return try {
            database.withTransaction {
                if (dao.findCollectionByName(normalized) != null) {
                    return@withTransaction CreateCollectionResult.NameTaken
                }
                val now = clock.instant()
                val entity = CollectionEntity(
                    name = normalized,
                    sortOrder = dao.getMaximumCollectionSortOrder() + 1,
                    createdAtEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                )
                val id = dao.insertCollection(entity)
                CreateCollectionResult.Created(entity.copy(id = id).toDomain())
            }
        } catch (_: SQLiteConstraintException) {
            CreateCollectionResult.NameTaken
        }
    }

    override suspend fun renameCollection(
        id: CollectionId,
        name: String,
    ): RenameCollectionResult {
        val normalized = normalizeCollectionName(name) ?: return RenameCollectionResult.InvalidName
        return try {
            database.withTransaction {
                val current = dao.getCollection(id.value) ?: return@withTransaction RenameCollectionResult.NotFound
                val duplicate = dao.findCollectionByName(normalized)
                if (duplicate != null && duplicate.id != id.value) {
                    return@withTransaction RenameCollectionResult.NameTaken
                }
                val updated = current.copy(
                    name = normalized,
                    updatedAtEpochMillis = clock.instant().toEpochMilli(),
                )
                check(dao.renameCollection(updated.id, updated.name, updated.updatedAtEpochMillis) == 1)
                RenameCollectionResult.Renamed(updated.toDomain())
            }
        } catch (_: SQLiteConstraintException) {
            RenameCollectionResult.NameTaken
        }
    }

    override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult =
        database.withTransaction {
            if (dao.deleteCollection(id.value) == 1) {
                DeleteCollectionResult.Deleted
            } else {
                DeleteCollectionResult.NotFound
            }
        }

    override suspend fun setPaperCollections(
        workId: WorkId,
        collectionIds: Set<CollectionId>,
    ): SetPaperCollectionsResult = database.withTransaction {
        if (dao.getWork(workId.value) == null) {
            return@withTransaction SetPaperCollectionsResult.PaperNotFound
        }
        val requestedIds = collectionIds.map(CollectionId::value).sorted()
        if (requestedIds.isNotEmpty()) {
            val existingIds = dao.getExistingCollectionIds(requestedIds).toSet()
            requestedIds.firstOrNull { it !in existingIds }?.let { missing ->
                return@withTransaction SetPaperCollectionsResult.CollectionNotFound(CollectionId(missing))
            }
        }
        dao.deleteWorkCollections(workId.value)
        if (requestedIds.isNotEmpty()) {
            dao.insertWorkCollections(requestedIds.map { WorkCollectionEntity(workId.value, it) })
        }
        SetPaperCollectionsResult.Updated
    }
}
