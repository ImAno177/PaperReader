package dev.paperreader.logic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingHistoryDao {
    @Query(
        """
        SELECT
            history.workId AS workId,
            works.title AS title,
            history.lastReadAtEpochMillis AS lastReadAtEpochMillis,
            history.totalReadDurationMillis AS totalReadDurationMillis,
            history.sessionCount AS sessionCount,
            COALESCE(reading_state.progression, 0.0) AS progression
        FROM reading_history AS history
        INNER JOIN works ON works.id = history.workId
        LEFT JOIN reading_state ON reading_state.workId = history.workId
        ORDER BY history.lastReadAtEpochMillis DESC
        """,
    )
    fun observeHistory(): Flow<List<ReadingHistoryRow>>

    @Query(
        """
        INSERT INTO reading_history (
            workId, lastReadAtEpochMillis, totalReadDurationMillis, sessionCount
        ) VALUES (
            :workId, :readAtEpochMillis, :durationMillis, 1
        ) ON CONFLICT(workId) DO UPDATE SET
            lastReadAtEpochMillis = MAX(lastReadAtEpochMillis, :readAtEpochMillis),
            totalReadDurationMillis = totalReadDurationMillis + :durationMillis,
            sessionCount = sessionCount + 1
        """,
    )
    suspend fun record(workId: String, readAtEpochMillis: Long, durationMillis: Long)

    @Query("DELETE FROM reading_history WHERE workId = :workId")
    suspend fun remove(workId: String)
}
