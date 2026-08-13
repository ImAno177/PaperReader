package dev.paperreader.logic.domain.history

import dev.paperreader.logic.domain.WorkId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class ReadingHistoryEntry(
    val workId: WorkId,
    val title: String,
    val lastReadAt: Instant,
    val totalReadDuration: Duration,
    val sessionCount: Int,
    val progression: Double,
)

interface ReadingHistoryRepository {
    val history: Flow<List<ReadingHistoryEntry>>

    suspend fun record(workId: WorkId, readAt: Instant, duration: Duration)

    suspend fun remove(workId: WorkId)
}
