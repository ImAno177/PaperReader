package dev.paperreader.logic.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index

/** Room code-generation rows. They are implementation details; callers use domain models. */
@Entity(tableName = "works")
data class WorkEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val abstractText: String?,
    val subjects: String,
    val publishedDateEpochDay: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)],
)
data class CollectionEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val sortOrder: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "work_collections",
    primaryKeys = ["workId", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workId"), Index("collectionId")],
)
data class WorkCollectionEntity(
    val workId: String,
    val collectionId: Long,
)

@Entity(
    tableName = "authors",
    primaryKeys = ["workId", "position"],
    indices = [Index("workId")],
)
data class AuthorEntity(
    val workId: String,
    val position: Int,
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    val orcid: String?,
)

@Entity(
    tableName = "identifiers",
    primaryKeys = ["workId", "type", "value", "authority"],
    indices = [Index("type", "value", "authority")],
)
data class IdentifierEntity(
    val workId: String,
    val type: String,
    val value: String,
    val authority: String,
)

@Entity(
    tableName = "manifestations",
    indices = [Index("workId"), Index("sourceProvider", "sourceRecordId")],
)
data class ManifestationEntity(
    @androidx.room.PrimaryKey val id: String,
    val workId: String,
    val type: String,
    val sourceProvider: String,
    val sourceRecordId: String,
    val version: String?,
    val landingPageUrl: String?,
    val pdfUrl: String?,
    val license: String?,
    val publishedDateEpochDay: Long?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "files",
    indices = [Index("manifestationId")],
)
data class FileEntity(
    @androidx.room.PrimaryKey val id: String,
    val manifestationId: String,
    val localPath: String?,
    val sha256: String?,
    val byteLength: Long?,
    val mimeType: String,
    val extractionStatus: String,
    val extractionManifestPath: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "reading_state")
data class ReadingStateEntity(
    @androidx.room.PrimaryKey val workId: String,
    val manifestationId: String?,
    val documentSha256: String?,
    val blockId: String?,
    val characterOffset: Int,
    val pageIndex: Int?,
    val progression: Double,
    val status: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "reading_bookmarks",
    primaryKeys = ["workId", "manifestationId", "documentSha256", "pageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("workId"),
        Index("manifestationId"),
        Index(value = ["workId", "manifestationId", "documentSha256"]),
    ],
)
data class ReadingBookmarkEntity(
    val workId: String,
    val manifestationId: String,
    val documentSha256: String,
    val pageIndex: Int,
    val id: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "annotations",
    indices = [Index("workId"), Index("documentSha256")],
)
data class AnnotationEntity(
    @androidx.room.PrimaryKey val id: String,
    val workId: String,
    val documentSha256: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quotePrefix: String,
    val quoteExact: String,
    val quoteSuffix: String,
    val pageIndex: Int?,
    val note: String?,
    val color: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "paper_tasks",
    indices = [Index("state"), Index("workId"), Index(value = ["kind", "targetKey"])],
)
data class TaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val kind: String,
    val workId: String?,
    val targetKey: String,
    val state: String,
    val progress: Double,
    val attempt: Int,
    val failureCode: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "reading_history")
data class ReadingHistoryEntity(
    @androidx.room.PrimaryKey val workId: String,
    val lastReadAtEpochMillis: Long,
    val totalReadDurationMillis: Long,
    val sessionCount: Int,
)

@Entity(
    tableName = "saved_searches",
)
data class SavedSearchEntity(
    @androidx.room.PrimaryKey val id: String,
    val queryText: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "saved_search_sources",
    primaryKeys = ["searchId", "providerId"],
    foreignKeys = [
        ForeignKey(
            entity = SavedSearchEntity::class,
            parentColumns = ["id"],
            childColumns = ["searchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("searchId")],
)
data class SavedSearchSourceEntity(
    val searchId: String,
    val providerId: String,
    val lastCheckedAtEpochMillis: Long?,
    val lastSuccessAtEpochMillis: Long?,
    val failureKind: String?,
    val retryAfterEpochMillis: Long?,
)

@Entity(
    tableName = "saved_search_hits",
    foreignKeys = [
        ForeignKey(
            entity = SavedSearchEntity::class,
            parentColumns = ["id"],
            childColumns = ["searchId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedWorkId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("searchId"),
        Index("linkedWorkId"),
        Index(value = ["searchId", "providerId", "providerRecordId"], unique = true),
    ],
)
data class SavedSearchHitEntity(
    @androidx.room.PrimaryKey val id: String,
    val searchId: String,
    val providerId: String,
    val providerRecordId: String,
    val fingerprint: String,
    val recordPayload: String,
    val linkedWorkId: String?,
    val providerUpdatedAtEpochMillis: Long?,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val unread: Boolean,
)

data class ReadingHistoryRow(
    val workId: String,
    val title: String,
    val lastReadAtEpochMillis: Long,
    val totalReadDurationMillis: Long,
    val sessionCount: Int,
    val progression: Double,
)
