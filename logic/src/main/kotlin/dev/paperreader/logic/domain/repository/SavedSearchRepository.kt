package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.provider.RemotePaper
import java.time.Instant
import kotlinx.coroutines.flow.Flow

sealed interface CreateSavedSearchResult {
    data class Created(val searchId: SavedSearchId) : CreateSavedSearchResult
    data class AlreadyExists(val searchId: SavedSearchId) : CreateSavedSearchResult
    data object InvalidQuery : CreateSavedSearchResult
    data object NoProviders : CreateSavedSearchResult
}

sealed interface DeleteSavedSearchResult {
    data object Deleted : DeleteSavedSearchResult
    data object NotFound : DeleteSavedSearchResult
}

interface SavedSearchRepository {
    val feeds: Flow<List<SavedSearchFeed>>

    suspend fun get(id: SavedSearchId): SavedSearchFeed?

    suspend fun getHit(id: SavedSearchHitId): SavedSearchHit?

    suspend fun create(
        queryText: String,
        providerIds: Set<String>,
    ): CreateSavedSearchResult

    suspend fun delete(id: SavedSearchId): DeleteSavedSearchResult

    /** Commits one provider page and its successful checkpoint atomically. */
    suspend fun recordSuccess(
        id: SavedSearchId,
        providerId: String,
        records: List<RemotePaper>,
        checkedAt: Instant,
    ): Int

    /** Preserves prior hits while recording the typed failure for one provider. */
    suspend fun recordFailure(
        id: SavedSearchId,
        providerId: String,
        failure: SavedSearchFailure,
        checkedAt: Instant,
    )

    suspend fun markHitRead(id: SavedSearchHitId): Boolean

    suspend fun linkHit(id: SavedSearchHitId, workId: WorkId): Boolean
}
