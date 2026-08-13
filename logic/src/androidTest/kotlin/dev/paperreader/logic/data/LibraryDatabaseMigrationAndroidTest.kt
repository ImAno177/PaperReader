package dev.paperreader.logic.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseMigrationAndroidTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDatabase::class.java,
    )

    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationOneToTwoPreservesExistingRowsAndAddsCollections() {
        helper.createDatabase(DATABASE_NAME, 1).use { db -> seedVersionOne(db) }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            LibraryDatabaseMigrations.MIGRATION_1_2,
        ).use { db ->
            assertEquals(1, db.count("works"))
            assertEquals(1, db.count("files"))
            assertEquals(1, db.count("paper_tasks"))
            assertEquals(0, db.count("collections"))
            db.execSQL(
                "INSERT INTO collections (name, sortOrder, createdAtEpochMillis, updatedAtEpochMillis) " +
                    "VALUES ('Migration kept data', 0, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO work_collections (workId, collectionId) " +
                    "VALUES ('work-existing', (SELECT id FROM collections LIMIT 1))",
            )
            assertEquals(1, db.count("work_collections"))
        }
    }

    @Test
    fun migrationTwoToThreePreservesCollectionsAndReaderStateAndAddsBookmarks() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            seedVersionOne(db)
            db.execSQL(
                "INSERT INTO collections (name, sortOrder, createdAtEpochMillis, updatedAtEpochMillis) " +
                    "VALUES ('Existing collection', 0, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO work_collections (workId, collectionId) " +
                    "VALUES ('work-existing', (SELECT id FROM collections LIMIT 1))",
            )
            db.execSQL(
                """
                INSERT INTO reading_state (
                    workId, manifestationId, documentSha256, blockId, characterOffset,
                    pageIndex, progression, status, updatedAtEpochMillis
                ) VALUES ('work-existing', 'manifestation-existing', '${"a".repeat(64)}', NULL, 0, 4, 0.5, 'READING', 1)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            LibraryDatabaseMigrations.MIGRATION_2_3,
        ).use { db ->
            assertEquals(1, db.count("works"))
            assertEquals(1, db.count("files"))
            assertEquals(1, db.count("paper_tasks"))
            assertEquals(1, db.count("collections"))
            assertEquals(1, db.count("work_collections"))
            assertEquals(1, db.count("reading_state"))
            assertEquals(0, db.count("reading_bookmarks"))
            db.execSQL(
                """
                INSERT INTO reading_bookmarks (
                    workId, manifestationId, documentSha256, pageIndex, id, createdAtEpochMillis
                ) VALUES ('work-existing', 'manifestation-existing', '${"a".repeat(64)}', 4, '${"b".repeat(64)}', 1)
                """.trimIndent(),
            )
            assertEquals(1, db.count("reading_bookmarks"))
        }
    }

    @Test
    fun migrationThreeToFourPreservesLibraryAndAddsSavedSearchFeedTables() {
        helper.createDatabase(DATABASE_NAME, 3).use { db -> seedVersionOne(db) }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            LibraryDatabaseMigrations.MIGRATION_3_4,
        ).use { db ->
            assertEquals(1, db.count("works"))
            assertEquals(1, db.count("files"))
            assertEquals(1, db.count("paper_tasks"))
            assertEquals(0, db.count("saved_searches"))
            assertEquals(0, db.count("saved_search_sources"))
            assertEquals(0, db.count("saved_search_hits"))
            db.execSQL(
                "INSERT INTO saved_searches (id, queryText, createdAtEpochMillis) " +
                    "VALUES ('saved-1', 'migration', 1)",
            )
            db.execSQL(
                "INSERT INTO saved_search_sources " +
                    "(searchId, providerId, lastCheckedAtEpochMillis, lastSuccessAtEpochMillis, failureKind, retryAfterEpochMillis) " +
                    "VALUES ('saved-1', 'arxiv', NULL, NULL, NULL, NULL)",
            )
            assertEquals(1, db.count("saved_search_sources"))
        }
    }

    private fun seedVersionOne(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO works (
                id, title, abstractText, subjects, publishedDateEpochDay,
                createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES ('work-existing', 'Existing paper', NULL, '', NULL, 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO manifestations (
                id, workId, type, sourceProvider, sourceRecordId, version,
                landingPageUrl, pdfUrl, license, publishedDateEpochDay, updatedAtEpochMillis
            ) VALUES (
                'manifestation-existing', 'work-existing', 'VERSION_OF_RECORD', 'fixture', 'record-1',
                NULL, NULL, 'https://example.org/paper.pdf', NULL, NULL, 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO files (
                id, manifestationId, localPath, sha256, byteLength, mimeType,
                extractionStatus, extractionManifestPath, updatedAtEpochMillis
            ) VALUES (
                'file-existing', 'manifestation-existing', 'papers/existing.pdf',
                '${"a".repeat(64)}', 42, 'application/pdf', 'NOT_STARTED', NULL, 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO paper_tasks (
                id, kind, workId, targetKey, state, progress, attempt,
                failureCode, createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES (
                'task-existing', 'DOWNLOAD', 'work-existing', 'manifestation-existing',
                'SUCCEEDED', 1.0, 1, NULL, 1, 1
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "collections-migration-test"
    }
}
