package dev.paperreader.logic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingBookmarkDao {
    @Query("SELECT * FROM reading_bookmarks ORDER BY workId, manifestationId, documentSha256, pageIndex")
    suspend fun getAll(): List<ReadingBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bookmarks: List<ReadingBookmarkEntity>)
    @Query(
        """
        SELECT * FROM reading_bookmarks
        WHERE workId = :workId
          AND manifestationId = :manifestationId
          AND documentSha256 = :documentSha256
        ORDER BY pageIndex, createdAtEpochMillis, id
        """,
    )
    fun observe(
        workId: String,
        manifestationId: String,
        documentSha256: String,
    ): Flow<List<ReadingBookmarkEntity>>

    @Query(
        """
        SELECT * FROM reading_bookmarks
        WHERE workId = :workId
          AND manifestationId = :manifestationId
          AND documentSha256 = :documentSha256
          AND pageIndex = :pageIndex
        LIMIT 1
        """,
    )
    suspend fun get(
        workId: String,
        manifestationId: String,
        documentSha256: String,
        pageIndex: Int,
    ): ReadingBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bookmark: ReadingBookmarkEntity)

    @Query(
        """
        DELETE FROM reading_bookmarks
        WHERE workId = :workId
          AND manifestationId = :manifestationId
          AND documentSha256 = :documentSha256
          AND pageIndex = :pageIndex
        """,
    )
    suspend fun delete(
        workId: String,
        manifestationId: String,
        documentSha256: String,
        pageIndex: Int,
    ): Int

    @Query("DELETE FROM reading_bookmarks WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
