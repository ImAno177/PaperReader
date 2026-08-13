package dev.paperreader.logic.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LibraryDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT COLLATE NOCASE NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_name` ON `collections` (`name`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `work_collections` (
                    `workId` TEXT NOT NULL,
                    `collectionId` INTEGER NOT NULL,
                    PRIMARY KEY(`workId`, `collectionId`),
                    FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_collections_workId` ON `work_collections` (`workId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_collections_collectionId` " +
                    "ON `work_collections` (`collectionId`)",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reading_bookmarks` (
                    `workId` TEXT NOT NULL,
                    `manifestationId` TEXT NOT NULL,
                    `documentSha256` TEXT NOT NULL,
                    `pageIndex` INTEGER NOT NULL,
                    `id` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`workId`, `manifestationId`, `documentSha256`, `pageIndex`),
                    FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_bookmarks_workId` " +
                    "ON `reading_bookmarks` (`workId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_bookmarks_manifestationId` " +
                    "ON `reading_bookmarks` (`manifestationId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_bookmarks_workId_manifestationId_documentSha256` " +
                    "ON `reading_bookmarks` (`workId`, `manifestationId`, `documentSha256`)",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `saved_searches` (
                    `id` TEXT NOT NULL,
                    `queryText` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `saved_search_sources` (
                    `searchId` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `lastCheckedAtEpochMillis` INTEGER,
                    `lastSuccessAtEpochMillis` INTEGER,
                    `failureKind` TEXT,
                    `retryAfterEpochMillis` INTEGER,
                    PRIMARY KEY(`searchId`, `providerId`),
                    FOREIGN KEY(`searchId`) REFERENCES `saved_searches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_saved_search_sources_searchId` " +
                    "ON `saved_search_sources` (`searchId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `saved_search_hits` (
                    `id` TEXT NOT NULL,
                    `searchId` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `providerRecordId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `recordPayload` TEXT NOT NULL,
                    `linkedWorkId` TEXT,
                    `providerUpdatedAtEpochMillis` INTEGER,
                    `firstSeenAtEpochMillis` INTEGER NOT NULL,
                    `lastSeenAtEpochMillis` INTEGER NOT NULL,
                    `unread` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`searchId`) REFERENCES `saved_searches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`linkedWorkId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_saved_search_hits_searchId` " +
                    "ON `saved_search_hits` (`searchId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_saved_search_hits_linkedWorkId` " +
                    "ON `saved_search_hits` (`linkedWorkId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_search_hits_searchId_providerId_providerRecordId` " +
                    "ON `saved_search_hits` (`searchId`, `providerId`, `providerRecordId`)",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
