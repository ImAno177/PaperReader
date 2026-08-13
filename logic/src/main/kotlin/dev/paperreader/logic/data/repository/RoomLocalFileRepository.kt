package dev.paperreader.logic.data.repository

import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.toDomain
import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.repository.LocalFileRepository

internal class RoomLocalFileRepository(database: LibraryDatabase) : LocalFileRepository {
    private val dao = database.libraryDao()

    override suspend fun get(manifestationId: ManifestationId): LocalPaperArtifact? =
        dao.getFile(manifestationId.value)?.toDomain()

    override suspend fun upsert(artifact: LocalPaperArtifact) {
        dao.upsertFile(artifact.toEntity())
    }

    override suspend fun remove(manifestationId: ManifestationId) {
        dao.deleteFile(manifestationId.value)
    }
}

private fun LocalPaperArtifact.toEntity() = FileEntity(
    id = id,
    manifestationId = manifestationId.value,
    localPath = storagePath,
    sha256 = sha256.lowercase(),
    byteLength = byteLength,
    mimeType = mimeType,
    extractionStatus = "NOT_STARTED",
    extractionManifestPath = null,
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)
