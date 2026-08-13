package dev.paperreader.logic.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkEntity::class,
        CollectionEntity::class,
        WorkCollectionEntity::class,
        AuthorEntity::class,
        IdentifierEntity::class,
        ManifestationEntity::class,
        FileEntity::class,
        ReadingStateEntity::class,
        ReadingBookmarkEntity::class,
        AnnotationEntity::class,
        TaskEntity::class,
        ReadingHistoryEntity::class,
        SavedSearchEntity::class,
        SavedSearchSourceEntity::class,
        SavedSearchHitEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    internal abstract fun libraryDao(): LibraryDao
    internal abstract fun taskDao(): TaskDao
    internal abstract fun readingHistoryDao(): ReadingHistoryDao
    internal abstract fun readingBookmarkDao(): ReadingBookmarkDao
    internal abstract fun savedSearchDao(): SavedSearchDao
}
