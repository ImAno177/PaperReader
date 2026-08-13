package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.SavedSearchAggregate
import dev.paperreader.logic.data.SavedSearchEntity
import dev.paperreader.logic.data.SavedSearchHitEntity
import dev.paperreader.logic.data.SavedSearchRecordCodec
import dev.paperreader.logic.data.SavedSearchSourceEntity
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.SavedSearch
import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.SavedSearchSource
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.normalizeSavedSearchQuery
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.domain.repository.DeleteSavedSearchResult
import dev.paperreader.logic.domain.repository.SavedSearchRepository
import dev.paperreader.logic.provider.RemotePaper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomSavedSearchRepository(
    private val database: LibraryDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : SavedSearchRepository {
    private val dao = database.savedSearchDao()
    private val libraryDao = database.libraryDao()

    override val feeds: Flow<List<SavedSearchFeed>> = dao.observeFeeds().map { rows ->
        rows.map(SavedSearchAggregate::toDomain)
    }

    override suspend fun get(id: SavedSearchId): SavedSearchFeed? = dao.getFeed(id.value)?.toDomain()

    override suspend fun getHit(id: SavedSearchHitId): SavedSearchHit? = dao.getHit(id.value)?.toDomain()

    override suspend fun create(
        queryText: String,
        providerIds: Set<String>,
    ): CreateSavedSearchResult {
        val normalizedQuery = normalizeSavedSearchQuery(queryText) ?: return CreateSavedSearchResult.InvalidQuery
        val normalizedProviders = providerIds
            .map { it.trim().lowercase() }
            .filter { it.matches(PROVIDER_ID_PATTERN) }
            .distinct()
            .sorted()
        if (normalizedProviders.isEmpty()) return CreateSavedSearchResult.NoProviders
        return database.withTransaction {
            val id = stableSavedSearchId(normalizedQuery, normalizedProviders)
            val now = clock.instant().toEpochMilli()
            if (dao.insertSearch(SavedSearchEntity(id.value, normalizedQuery, now)) == -1L) {
                return@withTransaction CreateSavedSearchResult.AlreadyExists(id)
            }
            dao.upsertSources(
                normalizedProviders.map { providerId ->
                    SavedSearchSourceEntity(
                        searchId = id.value,
                        providerId = providerId,
                        lastCheckedAtEpochMillis = null,
                        lastSuccessAtEpochMillis = null,
                        failureKind = null,
                        retryAfterEpochMillis = null,
                    )
                },
            )
            CreateSavedSearchResult.Created(id)
        }
    }

    override suspend fun delete(id: SavedSearchId): DeleteSavedSearchResult = database.withTransaction {
        if (dao.deleteSearch(id.value) == 1) {
            DeleteSavedSearchResult.Deleted
        } else {
            DeleteSavedSearchResult.NotFound
        }
    }

    override suspend fun recordSuccess(
        id: SavedSearchId,
        providerId: String,
        records: List<RemotePaper>,
        checkedAt: Instant,
    ): Int {
        val normalizedProviderId = providerId.trim().lowercase()
        require(records.all { it.providerId.lowercase() == normalizedProviderId }) {
            "Saved-search results must belong to the refreshed provider"
        }
        return database.withTransaction {
            val source = checkNotNull(dao.getSource(id.value, normalizedProviderId)) {
                "Saved-search source no longer exists"
            }
            if (source.lastCheckedAtEpochMillis?.let { it >= checkedAt.toEpochMilli() } == true) {
                return@withTransaction 0
            }
            val prepared = records.distinctBy(RemotePaper::providerRecordId).map { record ->
                val payload = SavedSearchRecordCodec.encode(record)
                PreparedRecord(record, payload, SavedSearchRecordCodec.fingerprint(payload))
            }
            val establishesBaseline = source.lastSuccessAtEpochMillis == null
            var newlyUnread = 0
            val rows = prepared.map { incoming ->
                val record = incoming.record
                val existing = dao.getHitByProviderIdentity(
                    id.value,
                    normalizedProviderId,
                    record.providerRecordId,
                )
                val unread = when {
                    establishesBaseline -> existing?.unread ?: false
                    existing == null -> true
                    existing.fingerprint != incoming.fingerprint -> true
                    else -> existing.unread
                }
                if (unread && existing?.unread != true) newlyUnread += 1
                SavedSearchHitEntity(
                    id = existing?.id ?: stableHitId(id, normalizedProviderId, record.providerRecordId),
                    searchId = id.value,
                    providerId = normalizedProviderId,
                    providerRecordId = record.providerRecordId,
                    fingerprint = incoming.fingerprint,
                    recordPayload = incoming.payload,
                    linkedWorkId = findExactWorkId(record) ?: existing?.linkedWorkId,
                    providerUpdatedAtEpochMillis = record.updatedAt?.toEpochMilli(),
                    firstSeenAtEpochMillis = existing?.firstSeenAtEpochMillis ?: checkedAt.toEpochMilli(),
                    lastSeenAtEpochMillis = checkedAt.toEpochMilli(),
                    unread = unread,
                )
            }
            if (rows.isNotEmpty()) dao.upsertHits(rows)
            dao.pruneHits(id.value, normalizedProviderId, MAX_HITS_PER_SOURCE)
            dao.upsertSources(
                listOf(
                    source.copy(
                        lastCheckedAtEpochMillis = checkedAt.toEpochMilli(),
                        lastSuccessAtEpochMillis = checkedAt.toEpochMilli(),
                        failureKind = null,
                        retryAfterEpochMillis = null,
                    ),
                ),
            )
            newlyUnread
        }
    }

    override suspend fun recordFailure(
        id: SavedSearchId,
        providerId: String,
        failure: SavedSearchFailure,
        checkedAt: Instant,
    ) {
        database.withTransaction {
            val source = dao.getSource(id.value, providerId.lowercase()) ?: return@withTransaction
            if (source.lastCheckedAtEpochMillis?.let { it >= checkedAt.toEpochMilli() } == true) {
                return@withTransaction
            }
            dao.upsertSources(
                listOf(
                    source.copy(
                        lastCheckedAtEpochMillis = checkedAt.toEpochMilli(),
                        failureKind = failure.kind.name,
                        retryAfterEpochMillis = failure.retryAfter?.toEpochMilli(),
                    ),
                ),
            )
        }
    }

    override suspend fun markHitRead(id: SavedSearchHitId): Boolean = dao.markHitRead(id.value) == 1

    override suspend fun linkHit(id: SavedSearchHitId, workId: WorkId): Boolean =
        dao.linkHit(id.value, workId.value) == 1

    private suspend fun findExactWorkId(record: RemotePaper): String? {
        val providerIdentifier = PaperIdentifier(IdentifierType.PROVIDER, record.providerRecordId, record.providerId)
        val matches = (record.identifiers + providerIdentifier).mapNotNull { identifier ->
            runCatching { IdentifierNormalizer.canonical(identifier) }.getOrNull()?.let { canonical ->
                libraryDao.findWorkId(canonical.type.name, canonical.value, canonical.authority.orEmpty())
            }
        }.distinct()
        return matches.singleOrNull()
    }

    private data class PreparedRecord(
        val record: RemotePaper,
        val payload: String,
        val fingerprint: String,
    )

    private companion object {
        val PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
        const val MAX_HITS_PER_SOURCE = 200
    }
}

private fun SavedSearchAggregate.toDomain(): SavedSearchFeed {
    val id = SavedSearchId(search.id)
    val sourceRows = sources.sortedBy(SavedSearchSourceEntity::providerId)
    return SavedSearchFeed(
        search = SavedSearch(
            id = id,
            queryText = search.queryText,
            sources = sourceRows.map(SavedSearchSourceEntity::toDomain),
            createdAt = Instant.ofEpochMilli(search.createdAtEpochMillis),
        ),
        hits = hits.map(SavedSearchHitEntity::toDomain).sortedWith(
            compareByDescending<SavedSearchHit> { it.unread }
                .thenByDescending { it.paper.updatedAt ?: it.firstSeenAt }
                .thenByDescending(SavedSearchHit::firstSeenAt)
                .thenBy { it.id.value },
        ),
    )
}

private fun SavedSearchSourceEntity.toDomain() = SavedSearchSource(
    providerId = providerId,
    lastCheckedAt = lastCheckedAtEpochMillis?.let(Instant::ofEpochMilli),
    lastSuccessAt = lastSuccessAtEpochMillis?.let(Instant::ofEpochMilli),
    failure = failureKind?.let { kind ->
        SavedSearchFailure(
            kind = SavedSearchFailureKind.valueOf(kind),
            retryAfter = retryAfterEpochMillis?.let(Instant::ofEpochMilli),
        )
    },
)

private fun SavedSearchHitEntity.toDomain(): SavedSearchHit {
    val paper = SavedSearchRecordCodec.decode(recordPayload)
    require(paper.providerId.lowercase() == providerId && paper.providerRecordId == providerRecordId) {
        "Saved-search snapshot identity does not match its row"
    }
    require(SavedSearchRecordCodec.fingerprint(recordPayload) == fingerprint) {
        "Saved-search snapshot fingerprint does not match its row"
    }
    return SavedSearchHit(
        id = SavedSearchHitId(id),
        searchId = SavedSearchId(searchId),
        paper = paper,
        fingerprint = fingerprint,
        linkedWorkId = linkedWorkId?.let(::WorkId),
        firstSeenAt = Instant.ofEpochMilli(firstSeenAtEpochMillis),
        lastSeenAt = Instant.ofEpochMilli(lastSeenAtEpochMillis),
        unread = unread,
    )
}

private fun stableHitId(searchId: SavedSearchId, providerId: String, providerRecordId: String): String {
    val key = "${searchId.value}|$providerId|$providerRecordId"
    return "ssh-" + MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun stableSavedSearchId(queryText: String, providerIds: List<String>): SavedSearchId {
    val key = queryText + "|" + providerIds.joinToString(",")
    return SavedSearchId(
        "ss-" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
    )
}
