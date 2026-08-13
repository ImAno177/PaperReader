package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.WorkId
import kotlinx.coroutines.flow.Flow

sealed interface ToggleReadingBookmarkResult {
    data class Added(val bookmark: ReadingBookmark) : ToggleReadingBookmarkResult
    data object Removed : ToggleReadingBookmarkResult
    data object PaperNotFound : ToggleReadingBookmarkResult
    data object ManifestationNotFound : ToggleReadingBookmarkResult
    data object DocumentNotCurrent : ToggleReadingBookmarkResult
}

sealed interface RemoveReadingBookmarkResult {
    data object Removed : RemoveReadingBookmarkResult
    data object NotFound : RemoveReadingBookmarkResult
}

interface ReadingBookmarkRepository {
    fun observe(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
    ): Flow<List<ReadingBookmark>>

    suspend fun toggle(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
        pageIndex: Int,
    ): ToggleReadingBookmarkResult

    suspend fun remove(id: ReadingBookmarkId): RemoveReadingBookmarkResult
}
