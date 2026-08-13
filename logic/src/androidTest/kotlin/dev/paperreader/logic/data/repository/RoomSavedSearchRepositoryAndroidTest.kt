package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import dev.paperreader.logic.provider.RemotePaper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSavedSearchRepositoryAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: RoomSavedSearchRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomSavedSearchRepository(database, FIXED_CLOCK)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun baselineIsReadAndLaterChangesAreUnreadWithoutDuplicateRows() = runBlocking {
        val searchId = (repository.create(" graph   learning ", setOf("arxiv")) as CreateSavedSearchResult.Created)
            .searchId
        assertEquals(
            CreateSavedSearchResult.AlreadyExists(searchId),
            repository.create("graph learning", setOf("arxiv")),
        )
        val initial = record("2401.00001v1", "Initial title", Instant.parse("2026-08-10T00:00:00Z"))

        assertEquals(0, repository.recordSuccess(searchId, "arxiv", listOf(initial), CHECK_ONE))
        assertFalse(repository.feeds.first().single().hits.single().unread)
        assertEquals(0, repository.recordSuccess(searchId, "arxiv", listOf(initial), CHECK_TWO))
        assertEquals(1, repository.feeds.first().single().hits.size)

        val changed = initial.copy(title = "Provider-corrected title", updatedAt = Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals(1, repository.recordSuccess(searchId, "arxiv", listOf(changed), CHECK_THREE))
        val changedHit = repository.feeds.first().single().hits.single()
        assertTrue(changedHit.unread)
        assertEquals("Provider-corrected title", changedHit.paper.title)
        assertTrue(repository.markHitRead(changedHit.id))
        assertFalse(repository.feeds.first().single().hits.single().unread)
    }

    @Test
    fun newArxivVersionIsUnreadAndExactAliasLinksOnlyAfterLibrarySave() = runBlocking {
        val searchId = (repository.create("transformer", setOf("arxiv")) as CreateSavedSearchResult.Created).searchId
        val v1 = record("2401.00001v1", "Version one", Instant.parse("2026-08-10T00:00:00Z"))
        repository.recordSuccess(searchId, "arxiv", listOf(v1), CHECK_ONE)
        assertNull(repository.feeds.first().single().hits.single().linkedWorkId)

        val library = RoomLibraryRepository(database, FIXED_CLOCK)
        val workId = library.save(v1)
        repository.recordSuccess(searchId, "arxiv", listOf(v1), CHECK_TWO)
        assertEquals(workId, repository.feeds.first().single().hits.single().linkedWorkId)

        val v2 = record("2401.00001v2", "Version two", Instant.parse("2026-08-12T00:00:00Z"))
        assertEquals(1, repository.recordSuccess(searchId, "arxiv", listOf(v2), CHECK_THREE))
        val feed = repository.feeds.first().single()
        assertEquals(2, feed.hits.size)
        val versionTwo = feed.hits.single { it.paper.providerRecordId.endsWith("v2") }
        assertTrue(versionTwo.unread)
        assertEquals(workId, versionTwo.linkedWorkId)
    }

    @Test
    fun deletionCascadesSourcesAndHits() = runBlocking {
        val searchId = (repository.create("deletion", setOf("crossref")) as CreateSavedSearchResult.Created).searchId
        repository.recordSuccess(
            searchId,
            "crossref",
            listOf(RemotePaper("crossref", "10.1000/delete", "Delete result")),
            CHECK_ONE,
        )
        val hitId: SavedSearchHitId = repository.feeds.first().single().hits.single().id
        assertNotNull(repository.getHit(hitId))

        assertEquals(DeleteSavedSearchResult.Deleted, repository.delete(searchId))
        assertEquals(emptyList<Any>(), repository.feeds.first())
        assertNull(repository.getHit(hitId))
    }

    @Test
    fun providerFailurePreservesStaleHitsAndSuccessfulCheckpoint() = runBlocking {
        val searchId = (repository.create("durable errors", setOf("arxiv")) as CreateSavedSearchResult.Created)
            .searchId
        val initial = record("2401.00001v1", "Still visible", CHECK_ONE)
        repository.recordSuccess(searchId, "arxiv", listOf(initial), CHECK_ONE)

        repository.recordFailure(
            searchId,
            "arxiv",
            SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE),
            CHECK_TWO,
        )

        val stale = repository.feeds.first().single()
        assertEquals(listOf("Still visible"), stale.hits.map { it.paper.title })
        assertEquals(CHECK_ONE, stale.search.sources.single().lastSuccessAt)
        assertEquals(CHECK_TWO, stale.search.sources.single().lastCheckedAt)
        assertEquals(SavedSearchFailureKind.UNAVAILABLE, stale.search.sources.single().failure?.kind)

        repository.recordSuccess(searchId, "arxiv", listOf(initial), CHECK_THREE)
        assertNull(repository.feeds.first().single().search.sources.single().failure)
    }

    @Test
    fun olderOrEqualRefreshOutcomeCannotOverwriteNewerProviderState() = runBlocking {
        val searchId = (repository.create("ordered refresh", setOf("arxiv")) as CreateSavedSearchResult.Created)
            .searchId
        val baseline = record("2401.00001v1", "Baseline", CHECK_ONE)
        repository.recordSuccess(searchId, "arxiv", listOf(baseline), CHECK_ONE)

        val newest = baseline.copy(title = "Newest metadata", updatedAt = CHECK_THREE)
        assertEquals(1, repository.recordSuccess(searchId, "arxiv", listOf(newest), CHECK_THREE))

        val delayed = baseline.copy(title = "Delayed stale metadata", updatedAt = CHECK_TWO)
        assertEquals(0, repository.recordSuccess(searchId, "arxiv", listOf(delayed), CHECK_TWO))
        repository.recordFailure(
            searchId,
            "arxiv",
            SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE),
            CHECK_TWO,
        )
        repository.recordFailure(
            searchId,
            "arxiv",
            SavedSearchFailure(SavedSearchFailureKind.INVALID_RESPONSE),
            CHECK_THREE,
        )

        val feed = repository.feeds.first().single()
        assertEquals("Newest metadata", feed.hits.single().paper.title)
        assertEquals(CHECK_THREE, feed.search.sources.single().lastCheckedAt)
        assertEquals(CHECK_THREE, feed.search.sources.single().lastSuccessAt)
        assertNull(feed.search.sources.single().failure)
    }

    @Test
    fun providerInboxIsBoundedAndNeverFuzzyMergesEqualTitles() = runBlocking {
        val searchId = (repository.create("bounded", setOf("crossref")) as CreateSavedSearchResult.Created)
            .searchId
        val records = (0 until 205).map { index ->
            RemotePaper(
                providerId = "crossref",
                providerRecordId = "10.1000/bounded-$index",
                title = "An identical title is not identity",
                updatedAt = CHECK_ONE.plusSeconds(index.toLong()),
            )
        }

        repository.recordSuccess(searchId, "crossref", records, CHECK_ONE)

        val hits = repository.feeds.first().single().hits
        assertEquals(200, hits.size)
        assertFalse(hits.any { it.paper.providerRecordId == "10.1000/bounded-0" })
        assertTrue(hits.any { it.paper.providerRecordId == "10.1000/bounded-204" })
    }

    private fun record(recordId: String, title: String, updatedAt: Instant) = RemotePaper(
        providerId = "arxiv",
        providerRecordId = recordId,
        title = title,
        identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "2401.00001")),
        updatedAt = updatedAt,
    )

    private companion object {
        val CHECK_ONE: Instant = Instant.parse("2026-08-12T01:00:00Z")
        val CHECK_TWO: Instant = Instant.parse("2026-08-12T02:00:00Z")
        val CHECK_THREE: Instant = Instant.parse("2026-08-12T03:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(CHECK_ONE, ZoneOffset.UTC)
    }
}
