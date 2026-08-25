package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.AnnotationRepository
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import dev.paperreader.logic.domain.repository.ReadingBookmarkRepository
import dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import dev.paperreader.logic.provider.PaperSearchQuery
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

class ObserveReadingBookmarks(private val repository: ReadingBookmarkRepository) {
    fun subscribe(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
    ): Flow<List<ReadingBookmark>> = repository.observe(workId, manifestationId, documentSha256)
}

class ToggleReadingBookmark(private val repository: ReadingBookmarkRepository) {
    suspend fun await(
        workId: WorkId,
        manifestationId: ManifestationId,
        documentSha256: String,
        pageIndex: Int,
    ): ToggleReadingBookmarkResult = repository.toggle(
        workId,
        manifestationId,
        documentSha256,
        pageIndex,
    )
}

class RemoveReadingBookmark(private val repository: ReadingBookmarkRepository) {
    suspend fun await(id: ReadingBookmarkId): RemoveReadingBookmarkResult = repository.remove(id)
}

class ObserveAnnotations(private val repository: AnnotationRepository) {
    fun subscribe(workId: WorkId, documentSha256: String): Flow<List<Annotation>> =
        repository.observe(workId, documentSha256)
}

class SaveAnnotation(private val repository: AnnotationRepository) {
    suspend fun await(
        workId: WorkId,
        selection: AnnotationSelection,
        note: String?,
    ): SaveAnnotationResult = repository.save(workId, selection, note)
}

class UpdateAnnotationNote(private val repository: AnnotationRepository) {
    suspend fun await(id: String, note: String?): UpdateAnnotationNoteResult = repository.updateNote(id, note)
}

class RemoveAnnotation(private val repository: AnnotationRepository) {
    suspend fun await(id: String): RemoveAnnotationResult = repository.remove(id)
}

class SetReadingStatus(
    private val repository: LibraryRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun await(workId: WorkId, status: ReadingStatus): Boolean {
        val paper = repository.get(workId) ?: return false
        val changedAt = now()
        val state = (paper.readingState ?: ReadingState(workId = workId, updatedAt = changedAt)).copy(
            status = status,
            updatedAt = changedAt,
        )
        repository.updateReadingState(state)
        return true
    }
}

class SearchPapers(private val search: FederatedPaperSearch) {
    fun subscribe(query: PaperSearchQuery): Flow<FederatedSearchEvent> = search.search(query)
}

class ObserveReadingHistory(private val repository: ReadingHistoryRepository) {
    fun subscribe(): Flow<List<ReadingHistoryEntry>> = repository.history
}

class RecordReadingSession(private val repository: ReadingHistoryRepository) {
    suspend fun await(workId: WorkId, readAt: Instant, duration: Duration) {
        require(!duration.isNegative) { "Reading duration cannot be negative" }
        repository.record(workId, readAt, duration)
    }
}

class RemoveReadingHistory(private val repository: ReadingHistoryRepository) {
    suspend fun await(workId: WorkId) = repository.remove(workId)
}

