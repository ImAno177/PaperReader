package dev.paperreader.logic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM paper_tasks ORDER BY createdAtEpochMillis, id")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM paper_tasks WHERE id = :id LIMIT 1")
    suspend fun getTask(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: TaskEntity): Long

    @Query(
        """
        UPDATE paper_tasks SET
            state = :newState,
            progress = :progress,
            attempt = :attempt,
            failureCode = :failureCode,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
            AND state = :expectedState
            AND progress = :expectedProgress
            AND attempt = :expectedAttempt
            AND failureCode IS :expectedFailureCode
            AND updatedAtEpochMillis = :expectedUpdatedAtEpochMillis
        """,
    )
    suspend fun updateIfState(
        id: String,
        expectedState: String,
        expectedProgress: Double,
        expectedAttempt: Int,
        expectedFailureCode: String?,
        expectedUpdatedAtEpochMillis: Long,
        newState: String,
        progress: Double,
        attempt: Int,
        failureCode: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM paper_tasks WHERE id = :id AND state = :expectedState")
    suspend fun deleteIfState(id: String, expectedState: String): Int
}

