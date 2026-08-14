package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderManager
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.RemotePaper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

sealed interface FederatedSearchEvent {
    data class Started(val providerIds: Set<String>) : FederatedSearchEvent {
        val providerCount: Int
            get() = providerIds.size
    }
    data class PageReceived(val providerId: String, val page: ProviderPage) : FederatedSearchEvent
    data class ProviderFailed(val providerId: String, val error: ProviderException) : FederatedSearchEvent
    data class Finished(val succeeded: Int, val failed: Int) : FederatedSearchEvent
}

class FederatedPaperSearch(private val providerManager: ProviderManager) {
    constructor(providers: Iterable<PaperProvider>) : this(MutableProviderManager(providers))

    fun search(query: PaperSearchQuery): Flow<FederatedSearchEvent> = channelFlow {
        val exactIdentifierType = query.text.exactIdentifierTypeOrNull()
        val providers = providerManager.getAll().filter { provider ->
            if (query.sort !in provider.descriptor.supportedSorts) {
                false
            } else if (exactIdentifierType == null) {
                ProviderCapability.DISCOVERY in provider.descriptor.capabilities &&
                    ProviderRole.SEARCH_ENGINE in provider.descriptor.roles
            } else {
                exactIdentifierType in provider.descriptor.identifierLookupTypes
            }
        }
        send(FederatedSearchEvent.Started(providers.map { it.descriptor.id }.toSet()))
        val succeeded = AtomicInteger()
        val failed = AtomicInteger()

        supervisorScope {
            providers.map { provider ->
                launch {
                    try {
                        val page = provider.search(query)
                        succeeded.incrementAndGet()
                        send(FederatedSearchEvent.PageReceived(provider.descriptor.id, page))
                    } catch (error: ProviderException) {
                        failed.incrementAndGet()
                        send(FederatedSearchEvent.ProviderFailed(provider.descriptor.id, error))
                    } catch (error: Exception) {
                        failed.incrementAndGet()
                        send(
                            FederatedSearchEvent.ProviderFailed(
                                provider.descriptor.id,
                                ProviderException.Unavailable(error),
                            ),
                        )
                    }
                }
            }.joinAll()
        }

        send(FederatedSearchEvent.Finished(succeeded.get(), failed.get()))
    }
}

private fun String.exactIdentifierTypeOrNull(): IdentifierType? {
    if (runCatching { IdentifierNormalizer.doi(this) }.isSuccess) return IdentifierType.DOI
    if (runCatching { IdentifierNormalizer.arxiv(this) }.isSuccess) return IdentifierType.ARXIV
    if (Regex("(?i)^pmid:\\s*\\d+$").matches(trim())) return IdentifierType.PMID
    if (Regex("(?i)^(?:pmcid:\\s*)?PMC\\d+$").matches(trim())) return IdentifierType.PMCID
    return null
}

data class SearchResultCluster(val records: List<RemotePaper>) {
    init {
        require(records.isNotEmpty())
    }
}

/** Clusters exact aliases only. Similar titles remain separate for user review. */
object SearchResultClusterer {
    fun cluster(records: Iterable<RemotePaper>): List<SearchResultCluster> {
        data class MutableCluster(
            val order: Int,
            val records: MutableList<RemotePaper> = mutableListOf(),
            val keys: MutableSet<String> = linkedSetOf(),
            var active: Boolean = true,
        )

        val clusters = mutableListOf<MutableCluster>()
        val clustersByKey = mutableMapOf<String, MutableCluster>()
        records.forEach { record ->
            val recordKeys = identityKeys(record)
            val matching = recordKeys.mapNotNull(clustersByKey::get).distinct().sortedBy(MutableCluster::order)
            if (matching.isEmpty()) {
                val cluster = MutableCluster(order = clusters.size).also {
                    it.records += record
                    it.keys += recordKeys
                }
                clusters += cluster
                recordKeys.forEach { key -> clustersByKey[key] = cluster }
            } else {
                val target = matching.first()
                target.records += record
                target.keys += recordKeys
                recordKeys.forEach { key -> clustersByKey[key] = target }
                matching.drop(1).forEach { extra ->
                    target.records += extra.records
                    target.keys += extra.keys
                    extra.active = false
                    extra.keys.forEach { key -> clustersByKey[key] = target }
                }
            }
        }
        return clusters.filter(MutableCluster::active).map { SearchResultCluster(it.records) }
    }

    private fun identityKeys(record: RemotePaper): Set<String> =
        IdentityResolver.exactKeys(record.identifiers) +
            "provider:${record.providerId.lowercase()}:${record.providerRecordId}"
}
