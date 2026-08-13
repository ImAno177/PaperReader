package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.ReadingBookmarkEntity
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.canonicalDocumentSha256
import dev.paperreader.logic.domain.readingBookmarkId
import dev.paperreader.logic.domain.repository.ReadingBookmarkRepository
import dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomReadingBookmarkRepository(
    private val database: LibraryDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : ReadingBookmarkRepository {
    private val libraryDao = database.libraryDao()
    private val bookmarkDao = database.readingBookmarkDao()

    override fun observe(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
    ): Flow<List<ReadingBookmark>> = bookmarkDao.observe(
        workId.value,
        manifestationId.value,
        canonicalDocumentSha256(documentSha256),
    ).map { rows -> rows.map(ReadingBookmarkEntity::toDomain) }

    override suspend fun toggle(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
        pageIndex: Int,
    ): ToggleReadingBookmarkResult {
        require(pageIndex >= 0) { "Bookmark page index is zero-based" }
        val canonicalSha = canonicalDocumentSha256(documentSha256)
        return database.withTransaction {
            if (libraryDao.getWork(workId.value) == null) {
                return@withTransaction ToggleReadingBookmarkResult.PaperNotFound
            }
            if (libraryDao.countManifestationForWork(workId.value, manifestationId.value) != 1) {
                return@withTransaction ToggleReadingBookmarkResult.ManifestationNotFound
            }
            if (libraryDao.countLocalDocument(manifestationId.value, canonicalSha) != 1) {
                return@withTransaction ToggleReadingBookmarkResult.DocumentNotCurrent
            }
            val existing = bookmarkDao.get(
                workId.value,
                manifestationId.value,
                canonicalSha,
                pageIndex,
            )
            if (existing != null) {
                check(
                    bookmarkDao.delete(
                        workId.value,
                        manifestationId.value,
                        canonicalSha,
                        pageIndex,
                    ) == 1,
                )
                ToggleReadingBookmarkResult.Removed
            } else {
                val entity = ReadingBookmarkEntity(
                    workId = workId.value,
                    manifestationId = manifestationId.value,
                    documentSha256 = canonicalSha,
                    pageIndex = pageIndex,
                    id = readingBookmarkId(workId, manifestationId, canonicalSha, pageIndex).value,
                    createdAtEpochMillis = clock.instant().toEpochMilli(),
                )
                bookmarkDao.insert(entity)
                ToggleReadingBookmarkResult.Added(entity.toDomain())
            }
        }
    }

    override suspend fun remove(id: ReadingBookmarkId): RemoveReadingBookmarkResult =
        if (bookmarkDao.deleteById(id.value) == 1) {
            RemoveReadingBookmarkResult.Removed
        } else {
            RemoveReadingBookmarkResult.NotFound
        }
}

private fun ReadingBookmarkEntity.toDomain() = ReadingBookmark(
    id = ReadingBookmarkId(id),
    workId = WorkId(workId),
    manifestationId = ManifestationId(manifestationId),
    documentSha256 = documentSha256,
    pageIndex = pageIndex,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
)
