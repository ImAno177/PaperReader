package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.SavedSearchRepository
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderManager
import dev.paperreader.logic.provider.SearchSort
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

class ObserveSavedSearches(private val repository: SavedSearchRepository) {
    fun subscribe(): Flow<List<SavedSearchFeed>> = repository.feeds
}

class CreateSavedSearch(private val repository: SavedSearchRepository) {
    suspend fun await(
        queryText: String,
        providerIds: Set<String>,
    ): CreateSavedSearchResult = repository.create(queryText, providerIds)
}

class DeleteSavedSearch(private val repository: SavedSearchRepository) {
    suspend fun await(id: SavedSearchId): DeleteSavedSearchResult = repository.delete(id)
}

sealed interface RefreshSavedSearchResult {
    data class Completed(
        val succeededProviders: Int,
        val failedProviders: Int,
        val newlyUnread: Int,
    ) : RefreshSavedSearchResult

    data object NotFound : RefreshSavedSearchResult
}

class RefreshSavedSearch(
    private val repository: SavedSearchRepository,
    private val providers: ProviderManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lastCheckEpochMillis = AtomicLong(Long.MIN_VALUE)

    suspend fun await(id: SavedSearchId): RefreshSavedSearchResult {
        val feed = repository.get(id) ?: return RefreshSavedSearchResult.NotFound
        val checkedAt = nextCheckedAt(feed.search.lastCheckedAt)
        val outcomes = supervisorScope {
            feed.search.sources.map { source ->
                async {
                    refreshProvider(
                        id = id,
                        queryText = feed.search.queryText,
                        providerId = source.providerId,
                        checkedAt = checkedAt,
                    )
                }
            }.awaitAll()
        }
        return RefreshSavedSearchResult.Completed(
            succeededProviders = outcomes.count { it is ProviderRefreshOutcome.Succeeded },
            failedProviders = outcomes.count { it is ProviderRefreshOutcome.Failed },
            newlyUnread = outcomes.sumOf { (it as? ProviderRefreshOutcome.Succeeded)?.newlyUnread ?: 0 },
        )
    }

    private suspend fun refreshProvider(
        id: SavedSearchId,
        queryText: String,
        providerId: String,
        checkedAt: Instant,
    ): ProviderRefreshOutcome {
        val provider = providers.get(providerId)
        if (provider == null) {
            repository.recordFailure(
                id,
                providerId,
                SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE),
                checkedAt,
            )
            return ProviderRefreshOutcome.Failed
        }
        val page = try {
            provider.search(PaperSearchQuery(queryText, limit = REFRESH_LIMIT, sort = SearchSort.NEWEST))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderException) {
            repository.recordFailure(id, providerId, error.toSavedSearchFailure(checkedAt), checkedAt)
            return ProviderRefreshOutcome.Failed
        } catch (error: Exception) {
            repository.recordFailure(
                id,
                providerId,
                SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE),
                checkedAt,
            )
            return ProviderRefreshOutcome.Failed
        }
        val newlyUnread = try {
            repository.recordSuccess(id, providerId, page.items, checkedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            repository.recordFailure(
                id,
                providerId,
                SavedSearchFailure(SavedSearchFailureKind.INVALID_RESPONSE),
                checkedAt,
            )
            return ProviderRefreshOutcome.Failed
        }
        return ProviderRefreshOutcome.Succeeded(newlyUnread)
    }

    private sealed interface ProviderRefreshOutcome {
        data class Succeeded(val newlyUnread: Int) : ProviderRefreshOutcome
        data object Failed : ProviderRefreshOutcome
    }

    private fun nextCheckedAt(persistedLastCheckedAt: Instant?): Instant {
        val clockMillis = clock.instant().toEpochMilli()
        val persistedFloor = persistedLastCheckedAt?.toEpochMilli()?.plus(1L) ?: Long.MIN_VALUE
        return Instant.ofEpochMilli(
            lastCheckEpochMillis.updateAndGet { previous ->
                maxOf(clockMillis, persistedFloor, previous + 1L)
            },
        )
    }

    private companion object {
        const val REFRESH_LIMIT = 20
    }
}

data class RefreshAllSavedSearchesResult(
    val refreshedSearches: Int,
    val succeededProviders: Int,
    val failedProviders: Int,
    val newlyUnread: Int,
)

/** Runs saved searches sequentially so background checks cannot fan out across every query at once. */
class RefreshAllSavedSearches(
    private val repository: SavedSearchRepository,
    private val refreshSavedSearch: RefreshSavedSearch,
) {
    suspend fun await(): RefreshAllSavedSearchesResult {
        var refreshedSearches = 0
        var succeededProviders = 0
        var failedProviders = 0
        var newlyUnread = 0
        repository.feeds.first().forEach { feed ->
            when (val result = refreshSavedSearch.await(feed.search.id)) {
                is RefreshSavedSearchResult.Completed -> {
                    refreshedSearches += 1
                    succeededProviders += result.succeededProviders
                    failedProviders += result.failedProviders
                    newlyUnread += result.newlyUnread
                }

                RefreshSavedSearchResult.NotFound -> Unit
            }
        }
        return RefreshAllSavedSearchesResult(
            refreshedSearches = refreshedSearches,
            succeededProviders = succeededProviders,
            failedProviders = failedProviders,
            newlyUnread = newlyUnread,
        )
    }
}

class MarkSavedSearchHitRead(private val repository: SavedSearchRepository) {
    suspend fun await(id: SavedSearchHitId): Boolean = repository.markHitRead(id)
}

sealed interface SaveSavedSearchHitResult {
    data class Saved(val workId: WorkId) : SaveSavedSearchHitResult
    data object NotFound : SaveSavedSearchHitResult
}

class SaveSavedSearchHit(
    private val savedSearchRepository: SavedSearchRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend fun await(id: SavedSearchHitId): SaveSavedSearchHitResult {
        val hit = savedSearchRepository.getHit(id) ?: return SaveSavedSearchHitResult.NotFound
        val workId = libraryRepository.save(hit.paper)
        try {
            savedSearchRepository.linkHit(id, workId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The Work is already durable; a future refresh can repair this optional feed link.
        }
        return SaveSavedSearchHitResult.Saved(workId)
    }
}

private fun ProviderException.toSavedSearchFailure(checkedAt: Instant): SavedSearchFailure = when (this) {
    is ProviderException.RateLimited -> SavedSearchFailure(
        kind = SavedSearchFailureKind.RATE_LIMITED,
        retryAfter = retryAfterMillis?.let(checkedAt::plusMillis),
    )

    is ProviderException.InvalidResponse -> SavedSearchFailure(SavedSearchFailureKind.INVALID_RESPONSE)
    is ProviderException.Unavailable -> SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE)
}
