package dev.paperreader.logic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches ORDER BY createdAtEpochMillis, id")
    suspend fun getAllSearches(): List<SavedSearchEntity>

    @Query("SELECT * FROM saved_search_sources ORDER BY searchId, providerId")
    suspend fun getAllSources(): List<SavedSearchSourceEntity>

    @Query("SELECT * FROM saved_search_hits ORDER BY searchId, providerId, providerRecordId")
    suspend fun getAllHits(): List<SavedSearchHitEntity>

    @Transaction
    @Query("SELECT * FROM saved_searches ORDER BY createdAtEpochMillis DESC, id")
    fun observeFeeds(): Flow<List<SavedSearchAggregate>>

    @Transaction
    @Query("SELECT * FROM saved_searches WHERE id = :id LIMIT 1")
    suspend fun getFeed(id: String): SavedSearchAggregate?

    @Query("SELECT * FROM saved_search_sources WHERE searchId = :searchId AND providerId = :providerId LIMIT 1")
    suspend fun getSource(searchId: String, providerId: String): SavedSearchSourceEntity?

    @Query("SELECT * FROM saved_search_hits WHERE id = :id LIMIT 1")
    suspend fun getHit(id: String): SavedSearchHitEntity?

    @Query(
        "SELECT * FROM saved_search_hits WHERE searchId = :searchId " +
            "AND providerId = :providerId AND providerRecordId = :providerRecordId LIMIT 1",
    )
    suspend fun getHitByProviderIdentity(
        searchId: String,
        providerId: String,
        providerRecordId: String,
    ): SavedSearchHitEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearch(search: SavedSearchEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<SavedSearchSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHits(hits: List<SavedSearchHitEntity>)

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun deleteSearch(id: String): Int

    @Query("UPDATE saved_search_hits SET unread = 0 WHERE id = :id")
    suspend fun markHitRead(id: String): Int

    @Query("UPDATE saved_search_hits SET linkedWorkId = :workId WHERE id = :id")
    suspend fun linkHit(id: String, workId: String): Int

    @Query(
        """
        DELETE FROM saved_search_hits
        WHERE id IN (
            SELECT id FROM saved_search_hits
            WHERE searchId = :searchId AND providerId = :providerId
            ORDER BY COALESCE(providerUpdatedAtEpochMillis, firstSeenAtEpochMillis) DESC,
                     firstSeenAtEpochMillis DESC,
                     id
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun pruneHits(searchId: String, providerId: String, keep: Int): Int
}

