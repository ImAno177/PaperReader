package dev.paperreader.logic.data.repository

import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.ReadingHistoryRow
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.domain.history.ReadingHistoryRepository
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomReadingHistoryRepository(database: LibraryDatabase) : ReadingHistoryRepository {
    private val dao = database.readingHistoryDao()

    override val history: Flow<List<ReadingHistoryEntry>> = dao.observeHistory().map { rows ->
        rows.map(ReadingHistoryRow::toDomain)
    }

    override suspend fun record(workId: WorkId, readAt: Instant, duration: Duration) {
        require(!duration.isNegative) { "Reading duration cannot be negative" }
        dao.record(workId.value, readAt.toEpochMilli(), duration.toMillis())
    }

    override suspend fun remove(workId: WorkId) = dao.remove(workId.value)
}

private fun ReadingHistoryRow.toDomain() = ReadingHistoryEntry(
    workId = WorkId(workId),
    title = title,
    lastReadAt = Instant.ofEpochMilli(lastReadAtEpochMillis),
    totalReadDuration = Duration.ofMillis(totalReadDurationMillis),
    sessionCount = sessionCount,
    progression = progression,
)
