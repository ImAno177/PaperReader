package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.SavedSearch
import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.SavedSearchSource
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import dev.paperreader.logic.domain.repository.SavedSearchRepository
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchUseCasesTest {
    @Test
    fun `manual refresh uses newest real provider pages and isolates typed failures`() = runTest {
        val repository = FakeSavedSearchRepository(feed())
        val successful = RecordingProvider("arxiv") {
            ProviderPage(listOf(RemotePaper("arxiv", "2401.1v1", "Fresh preprint")))
        }
        val limited = RecordingProvider("crossref") {
            throw ProviderException.RateLimited(30_000)
        }
        val now = Instant.parse("2026-08-12T10:00:00Z")
        val useCase = RefreshSavedSearch(
            repository,
            MutableProviderManager(listOf(successful, limited)),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = useCase.await(SEARCH_ID)

        assertEquals(RefreshSavedSearchResult.Completed(1, 1, 2), result)
        assertEquals(SearchSort.NEWEST, successful.query?.sort)
        assertEquals(20, successful.query?.limit)
        assertEquals("graph learning", successful.query?.text)
        assertEquals(
            SavedSearchFailure(
                SavedSearchFailureKind.RATE_LIMITED,
                Instant.parse("2026-08-12T10:00:30Z"),
            ),
            repository.failures.single().failure,
        )
    }

    @Test
    fun `refresh cancellation is never persisted as a provider failure`() = runTest {
        val repository = FakeSavedSearchRepository(
            feed().copy(search = feed().search.copy(sources = listOf(SavedSearchSource("arxiv")))),
        )
        val provider = RecordingProvider("arxiv") { throw CancellationException("stop") }
        val useCase = RefreshSavedSearch(repository, MutableProviderManager(listOf(provider)))

        var cancelled = false
        try {
            useCase.await(SEARCH_ID)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(emptyList<FailureRecord>(), repository.failures)
    }

    @Test
    fun `refresh attempts receive monotonic checkpoints when the wall clock is unchanged`() = runTest {
        val repository = FakeSavedSearchRepository(
            feed().copy(search = feed().search.copy(sources = listOf(SavedSearchSource("arxiv")))),
        )
        val provider = RecordingProvider("arxiv") { ProviderPage(emptyList()) }
        val now = Instant.parse("2026-08-12T10:00:00Z")
        val useCase = RefreshSavedSearch(
            repository,
            MutableProviderManager(listOf(provider)),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        useCase.await(SEARCH_ID)
        useCase.await(SEARCH_ID)

        assertEquals(listOf(now, now.plusMillis(1)), repository.successes.map(SuccessRecord::checkedAt))
    }

    @Test
    fun `refresh checkpoint remains monotonic after restart with a backward wall clock`() = runTest {
        val persisted = Instant.parse("2026-08-12T11:00:00Z")
        val repository = FakeSavedSearchRepository(
            feed().copy(
                search = feed().search.copy(
                    sources = listOf(SavedSearchSource("arxiv", lastCheckedAt = persisted)),
                ),
            ),
        )
        val provider = RecordingProvider("arxiv") { ProviderPage(emptyList()) }
        val useCase = RefreshSavedSearch(
            repository,
            MutableProviderManager(listOf(provider)),
            Clock.fixed(persisted.minusSeconds(3_600), ZoneOffset.UTC),
        )

        useCase.await(SEARCH_ID)

        assertEquals(persisted.plusMillis(1), repository.successes.single().checkedAt)
    }

    @Test
    fun `background refresh checks saved searches sequentially and aggregates only completed work`() = runTest {
        val first = feed(
            id = SEARCH_ID,
            queryText = "graph learning",
            sources = listOf(SavedSearchSource("arxiv")),
        )
        val second = feed(
            id = SavedSearchId("saved-search-2"),
            queryText = "open peer review",
            sources = listOf(SavedSearchSource("crossref")),
        )
        val deletedBeforeRefresh = feed(SavedSearchId("saved-search-3"), "deleted query")
        val repository = FakeSavedSearchRepository(
            initialFeeds = listOf(first, second, deletedBeforeRefresh),
            availableFeeds = mapOf(first.search.id to first, second.search.id to second),
        )
        val providerOrder = mutableListOf<String>()
        val providers = MutableProviderManager(
            listOf(
                RecordingProvider("arxiv") {
                    providerOrder += "arxiv"
                    ProviderPage(emptyList())
                },
                RecordingProvider("crossref") {
                    providerOrder += "crossref"
                    throw ProviderException.Unavailable()
                },
            ),
        )
        val singleRefresh = RefreshSavedSearch(repository, providers)

        val result = RefreshAllSavedSearches(repository, singleRefresh).await()

        assertEquals(RefreshAllSavedSearchesResult(2, 1, 1, 2), result)
        assertEquals(listOf("arxiv", "crossref"), providerOrder)
    }

    private fun feed(
        id: SavedSearchId = SEARCH_ID,
        queryText: String = "graph learning",
        sources: List<SavedSearchSource> = listOf(SavedSearchSource("arxiv"), SavedSearchSource("crossref")),
    ) = SavedSearchFeed(
        search = SavedSearch(
            id = id,
            queryText = queryText,
            sources = sources,
            createdAt = Instant.EPOCH,
        ),
        hits = emptyList(),
    )

    private class RecordingProvider(
        id: String,
        private val result: suspend () -> ProviderPage,
    ) : PaperProvider {
        override val descriptor = ProviderDescriptor(id, id, 0)
        var query: PaperSearchQuery? = null

        override suspend fun search(query: PaperSearchQuery): ProviderPage {
            this.query = query
            return result()
        }

        override suspend fun get(recordId: String): RemotePaper? = null
    }

    private class FakeSavedSearchRepository(
        feed: SavedSearchFeed? = null,
        initialFeeds: List<SavedSearchFeed> = listOfNotNull(feed),
        private val availableFeeds: Map<SavedSearchId, SavedSearchFeed> = initialFeeds.associateBy { it.search.id },
    ) : SavedSearchRepository {
        override val feeds: Flow<List<SavedSearchFeed>> = MutableStateFlow(initialFeeds)
        val failures = mutableListOf<FailureRecord>()
        val successes = mutableListOf<SuccessRecord>()

        override suspend fun get(id: SavedSearchId): SavedSearchFeed? = availableFeeds[id]
        override suspend fun getHit(id: SavedSearchHitId): SavedSearchHit? = null
        override suspend fun create(queryText: String, providerIds: Set<String>) =
            CreateSavedSearchResult.Created(SEARCH_ID)

        override suspend fun delete(id: SavedSearchId) = DeleteSavedSearchResult.Deleted
        override suspend fun recordSuccess(
            id: SavedSearchId,
            providerId: String,
            records: List<RemotePaper>,
            checkedAt: Instant,
        ): Int {
            successes += SuccessRecord(providerId, checkedAt)
            return 2
        }

        override suspend fun recordFailure(
            id: SavedSearchId,
            providerId: String,
            failure: SavedSearchFailure,
            checkedAt: Instant,
        ) {
            failures += FailureRecord(providerId, failure, checkedAt)
        }

        override suspend fun markHitRead(id: SavedSearchHitId) = true
        override suspend fun linkHit(id: SavedSearchHitId, workId: WorkId) = true
    }

    private data class FailureRecord(
        val providerId: String,
        val failure: SavedSearchFailure,
        val checkedAt: Instant,
    )

    private data class SuccessRecord(
        val providerId: String,
        val checkedAt: Instant,
    )

    private companion object {
        val SEARCH_ID = SavedSearchId("saved-search-1")
    }
}
