package dev.paperreader.logic.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** One reactive Room projection; repository mapping keeps these rows out of UI APIs. */
data class LibraryPaperAggregate(
    @Embedded val work: WorkEntity,
    @Relation(parentColumn = "id", entityColumn = "workId")
    val authors: List<AuthorEntity>,
    @Relation(parentColumn = "id", entityColumn = "workId")
    val identifiers: List<IdentifierEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "workId",
        entity = ManifestationEntity::class,
    )
    val manifestations: List<ManifestationWithFiles>,
    @Relation(parentColumn = "id", entityColumn = "workId")
    val readingState: ReadingStateEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = WorkCollectionEntity::class,
            parentColumn = "workId",
            entityColumn = "collectionId",
        ),
    )
    val collections: List<CollectionEntity>,
)

data class ManifestationWithFiles(
    @Embedded val manifestation: ManifestationEntity,
    @Relation(parentColumn = "id", entityColumn = "manifestationId")
    val files: List<FileEntity>,
)

data class SavedSearchAggregate(
    @Embedded val search: SavedSearchEntity,
    @Relation(parentColumn = "id", entityColumn = "searchId")
    val sources: List<SavedSearchSourceEntity>,
    @Relation(parentColumn = "id", entityColumn = "searchId")
    val hits: List<SavedSearchHitEntity>,
)
