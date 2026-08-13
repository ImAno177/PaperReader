package dev.paperreader.logic.domain

import dev.paperreader.logic.provider.RemotePaper
import java.time.Instant

@JvmInline
value class SavedSearchId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SavedSearchHitId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

enum class SavedSearchFailureKind {
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

data class SavedSearchFailure(
    val kind: SavedSearchFailureKind,
    val retryAfter: Instant? = null,
)

data class SavedSearchSource(
    val providerId: String,
    val lastCheckedAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val failure: SavedSearchFailure? = null,
) {
    init {
        require(providerId.isNotBlank())
    }
}

data class SavedSearch(
    val id: SavedSearchId,
    val queryText: String,
    val sources: List<SavedSearchSource>,
    val createdAt: Instant,
) {
    val lastCheckedAt: Instant?
        get() = sources.mapNotNull(SavedSearchSource::lastCheckedAt).maxOrNull()

    init {
        require(normalizeSavedSearchText(queryText, MAX_SAVED_SEARCH_QUERY_LENGTH) == queryText)
        require(sources.isNotEmpty())
        require(sources.map(SavedSearchSource::providerId).distinct().size == sources.size)
    }
}

data class SavedSearchHit(
    val id: SavedSearchHitId,
    val searchId: SavedSearchId,
    val paper: RemotePaper,
    val fingerprint: String,
    val linkedWorkId: WorkId? = null,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val unread: Boolean,
) {
    init {
        require(fingerprint.matches(SHA_256_PATTERN))
        require(!lastSeenAt.isBefore(firstSeenAt))
    }
}

data class SavedSearchFeed(
    val search: SavedSearch,
    val hits: List<SavedSearchHit>,
) {
    val unreadCount: Int
        get() = hits.count(SavedSearchHit::unread)
}

fun normalizeSavedSearchQuery(value: String): String? =
    normalizeSavedSearchText(value, MAX_SAVED_SEARCH_QUERY_LENGTH)

private fun normalizeSavedSearchText(value: String, maximumLength: Int): String? {
    val normalized = value.trim().replace(Regex("\\s+"), " ")
    return normalized.takeIf { it.isNotEmpty() && it.length <= maximumLength }
}

const val MAX_SAVED_SEARCH_QUERY_LENGTH = 300

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
