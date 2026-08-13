package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.provider.RemotePaper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCollectionsAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLibraryRepository(database, FIXED_CLOCK)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun collectionCrudAndMembershipAreTransactionalAndPreservePaper() = runBlocking {
        val workId = repository.save(remotePaper())
        val reading = repository.createCollection("  Reading   list ") as CreateCollectionResult.Created
        val methods = repository.createCollection("Methods") as CreateCollectionResult.Created

        assertEquals("Reading list", reading.collection.name)
        assertEquals(CreateCollectionResult.NameTaken, repository.createCollection("READING LIST"))
        assertEquals(
            SetPaperCollectionsResult.Updated,
            repository.setPaperCollections(workId, setOf(reading.collection.id, methods.collection.id)),
        )
        assertEquals(
            setOf(reading.collection.id, methods.collection.id),
            repository.get(workId)?.collectionIds,
        )

        val renamed = repository.renameCollection(methods.collection.id, "Research methods")
            as RenameCollectionResult.Renamed
        assertEquals("Research methods", renamed.collection.name)
        assertEquals(DeleteCollectionResult.Deleted, repository.deleteCollection(reading.collection.id))

        assertNotNull(repository.get(workId))
        assertEquals(setOf(methods.collection.id), repository.get(workId)?.collectionIds)
        assertEquals(listOf("Research methods"), repository.collections.first().map { it.name })
    }

    @Test
    fun unknownMembershipDoesNotPartiallyReplaceExistingMemberships() = runBlocking {
        val workId = repository.save(remotePaper())
        val existing = (repository.createCollection("Existing") as CreateCollectionResult.Created).collection
        repository.setPaperCollections(workId, setOf(existing.id))
        val missing = CollectionId(existing.id.value + 100)

        assertEquals(
            SetPaperCollectionsResult.CollectionNotFound(missing),
            repository.setPaperCollections(workId, setOf(missing)),
        )
        assertEquals(setOf(existing.id), repository.get(workId)?.collectionIds)
    }

    private fun remotePaper() = RemotePaper(
        providerId = "fixture",
        providerRecordId = "paper-1",
        title = "A real saved paper",
        identifiers = setOf(PaperIdentifier(IdentifierType.DOI, "10.1000/collection-test")),
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-12T00:00:00Z"),
            ZoneOffset.UTC,
        )
    }
}
