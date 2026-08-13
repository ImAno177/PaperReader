package dev.paperreader.logic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class LocalDocumentAnchorRow(
    val manifestationId: String,
    val documentSha256: String,
)

data class LocalPdfOwnerRow(
    val workId: String,
    val manifestationId: String,
    val localPath: String,
    val documentSha256: String,
    val byteLength: Long,
)

@Dao
interface LibraryDao {
    @Transaction
    @Query("SELECT * FROM works ORDER BY createdAtEpochMillis DESC, id")
    fun observeLibrary(): Flow<List<LibraryPaperAggregate>>

    @Query("SELECT * FROM collections ORDER BY sortOrder, name COLLATE NOCASE, id")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getCollection(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findCollectionByName(name: String): CollectionEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM collections")
    suspend fun getMaximumCollectionSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query(
        "UPDATE collections SET name = :name, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id",
    )
    suspend fun renameCollection(id: Long, name: String, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long): Int

    @Query(
        "SELECT id FROM collections WHERE id IN (:ids)",
    )
    suspend fun getExistingCollectionIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM collections WHERE id IN (SELECT collectionId FROM work_collections WHERE workId = :workId)")
    suspend fun getCollectionsForWork(workId: String): List<CollectionEntity>

    @Query("DELETE FROM work_collections WHERE workId = :workId")
    suspend fun deleteWorkCollections(workId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkCollections(memberships: List<WorkCollectionEntity>)

    @Query("SELECT * FROM works ORDER BY createdAtEpochMillis DESC, id")
    fun observeWorks(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works ORDER BY id")
    suspend fun getAllWorks(): List<WorkEntity>

    @Query("SELECT * FROM authors ORDER BY workId, position")
    suspend fun getAllAuthors(): List<AuthorEntity>

    @Query("SELECT * FROM identifiers ORDER BY workId, type, value, authority")
    suspend fun getAllIdentifiers(): List<IdentifierEntity>

    @Query("SELECT * FROM manifestations ORDER BY workId, id")
    suspend fun getAllManifestations(): List<ManifestationEntity>

    @Query("SELECT * FROM collections ORDER BY sortOrder, name COLLATE NOCASE, id")
    suspend fun getAllCollections(): List<CollectionEntity>

    @Query("SELECT * FROM work_collections ORDER BY workId, collectionId")
    suspend fun getAllWorkCollections(): List<WorkCollectionEntity>

    @Query("SELECT * FROM reading_state ORDER BY workId")
    suspend fun getAllReadingStates(): List<ReadingStateEntity>

    @Query("SELECT * FROM annotations ORDER BY id")
    suspend fun getAllAnnotations(): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getAnnotation(id: String): AnnotationEntity?

    @Query("SELECT * FROM reading_history ORDER BY workId")
    suspend fun getAllReadingHistory(): List<ReadingHistoryEntity>

    @Query("SELECT * FROM works WHERE id = :workId LIMIT 1")
    suspend fun getWork(workId: String): WorkEntity?

    @Query("SELECT * FROM authors WHERE workId = :workId ORDER BY position")
    fun observeAuthors(workId: String): Flow<List<AuthorEntity>>

    @Query("SELECT * FROM identifiers WHERE workId = :workId")
    fun observeIdentifiers(workId: String): Flow<List<IdentifierEntity>>

    @Query("SELECT * FROM manifestations WHERE workId = :workId ORDER BY id")
    fun observeManifestations(workId: String): Flow<List<ManifestationEntity>>

    @Query("SELECT * FROM reading_state WHERE workId = :workId LIMIT 1")
    fun observeReadingState(workId: String): Flow<ReadingStateEntity?>

    @Query("SELECT * FROM authors WHERE workId = :workId ORDER BY position")
    suspend fun getAuthors(workId: String): List<AuthorEntity>

    @Query("SELECT * FROM identifiers WHERE workId = :workId")
    suspend fun getIdentifiers(workId: String): List<IdentifierEntity>

    @Query("SELECT * FROM manifestations WHERE workId = :workId ORDER BY id")
    suspend fun getManifestations(workId: String): List<ManifestationEntity>

    @Query(
        "SELECT * FROM manifestations WHERE sourceProvider = :sourceProvider " +
            "AND LOWER(sourceRecordId) = :sourceRecordId ORDER BY id",
    )
    suspend fun getManifestationsBySourceIdentity(
        sourceProvider: String,
        sourceRecordId: String,
    ): List<ManifestationEntity>

    @Query(
        "SELECT * FROM files WHERE manifestationId IN " +
            "(SELECT id FROM manifestations WHERE workId = :workId)",
    )
    suspend fun getFilesForWork(workId: String): List<FileEntity>

    @Query("SELECT * FROM reading_state WHERE workId = :workId LIMIT 1")
    suspend fun getReadingState(workId: String): ReadingStateEntity?

    @Query(
        "SELECT COUNT(*) FROM manifestations WHERE id = :manifestationId AND workId = :workId",
    )
    suspend fun countManifestationForWork(workId: String, manifestationId: String): Int

    @Query(
        "SELECT COUNT(*) FROM files " +
            "WHERE manifestationId = :manifestationId " +
            "AND localPath IS NOT NULL " +
            "AND LOWER(sha256) = :documentSha256",
    )
    suspend fun countLocalDocument(manifestationId: String, documentSha256: String): Int

    @Query("SELECT COUNT(*) FROM files WHERE localPath IS NOT NULL AND LOWER(sha256) = :documentSha256")
    suspend fun countAnyLocalDocument(documentSha256: String): Int

    @Query(
        "SELECT manifestationId, LOWER(sha256) AS documentSha256 FROM files " +
            "WHERE localPath IS NOT NULL AND sha256 IS NOT NULL",
    )
    suspend fun getLocalDocumentAnchors(): List<LocalDocumentAnchorRow>

    @Query(
        """
        SELECT manifestations.workId AS workId,
               files.manifestationId AS manifestationId,
               files.localPath AS localPath,
               LOWER(files.sha256) AS documentSha256,
               files.byteLength AS byteLength
        FROM files
        INNER JOIN manifestations ON manifestations.id = files.manifestationId
        WHERE files.localPath IS NOT NULL
          AND files.sha256 IS NOT NULL
          AND files.byteLength IS NOT NULL
          AND LOWER(files.sha256) = :documentSha256
          AND manifestations.sourceProvider = :sourceProvider
          AND LOWER(manifestations.sourceRecordId) = :documentSha256
        ORDER BY manifestations.id
        """,
    )
    suspend fun getLocalPdfOwners(
        documentSha256: String,
        sourceProvider: String,
    ): List<LocalPdfOwnerRow>

    @Query("SELECT workId FROM identifiers WHERE type = :type AND value = :value AND authority = :authority LIMIT 1")
    suspend fun findWorkId(type: String, value: String, authority: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: WorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAuthors(authors: List<AuthorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentifiers(identifiers: List<IdentifierEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertManifestations(manifestations: List<ManifestationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingState(state: ReadingStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingStates(states: List<ReadingStateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollections(collections: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertWorkCollections(memberships: List<WorkCollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotations(annotations: List<AnnotationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingHistory(history: List<ReadingHistoryEntity>)

    @Query("SELECT * FROM files WHERE manifestationId = :manifestationId LIMIT 1")
    suspend fun getFile(manifestationId: String): FileEntity?

    @Query("SELECT * FROM files WHERE localPath = :localPath LIMIT 1")
    suspend fun getFileByLocalPath(localPath: String): FileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: FileEntity)

    @Query("DELETE FROM files WHERE manifestationId = :manifestationId")
    suspend fun deleteFile(manifestationId: String)

    @Query("DELETE FROM authors WHERE workId = :workId")
    suspend fun deleteAuthors(workId: String)

    @Query("DELETE FROM identifiers WHERE workId = :workId")
    suspend fun deleteIdentifiers(workId: String)

    @Query("DELETE FROM manifestations WHERE workId = :workId")
    suspend fun deleteManifestations(workId: String)

    @Query(
        """
        SELECT COUNT(*) FROM files
        INNER JOIN manifestations ON manifestations.id = files.manifestationId
        WHERE manifestations.workId = :workId
          AND (files.localPath IS NOT NULL OR files.extractionManifestPath IS NOT NULL)
        """,
    )
    suspend fun countLocalArtifacts(workId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM paper_tasks
        WHERE workId = :workId AND state IN ('QUEUED', 'RUNNING')
        """,
    )
    suspend fun countActiveTasks(workId: String): Int

    @Query("DELETE FROM files WHERE manifestationId IN (SELECT id FROM manifestations WHERE workId = :workId)")
    suspend fun deleteFiles(workId: String)

    @Query("DELETE FROM annotations WHERE workId = :workId")
    suspend fun deleteAnnotations(workId: String)

    @Query("DELETE FROM reading_bookmarks WHERE workId = :workId")
    suspend fun deleteReadingBookmarks(workId: String)

    @Query("DELETE FROM reading_state WHERE workId = :workId")
    suspend fun deleteReadingState(workId: String)

    @Query("DELETE FROM reading_history WHERE workId = :workId")
    suspend fun deleteReadingHistory(workId: String)

    @Query("DELETE FROM paper_tasks WHERE workId = :workId")
    suspend fun deleteTasks(workId: String)

    @Query("DELETE FROM works WHERE id = :workId")
    suspend fun deleteWork(workId: String): Int
}

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

@Dao
interface SavedSearchDao {
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
