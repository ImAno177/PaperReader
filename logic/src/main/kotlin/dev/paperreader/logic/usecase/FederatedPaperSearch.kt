package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderManager
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
        val providers = providerManager.getAll()
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

data class SearchResultCluster(val records: List<RemotePaper>) {
    init {
        require(records.isNotEmpty())
    }
}

/** Clusters exact aliases only. Similar titles remain separate for user review. */
object SearchResultClusterer {
    fun cluster(records: Iterable<RemotePaper>): List<SearchResultCluster> {
        val clusters = mutableListOf<MutableList<RemotePaper>>()
        records.forEach { record ->
            val recordKeys = identityKeys(record)
            val matching = clusters.filter { cluster ->
                cluster.any { existing -> identityKeys(existing).intersect(recordKeys).isNotEmpty() }
            }
            if (matching.isEmpty()) {
                clusters += mutableListOf(record)
            } else {
                val target = matching.first()
                target += record
                matching.drop(1).forEach { extra ->
                    target += extra
                    clusters.remove(extra)
                }
            }
        }
        return clusters.map(::SearchResultCluster)
    }

    private fun identityKeys(record: RemotePaper): Set<String> =
        IdentityResolver.exactKeys(record.identifiers) +
            "provider:${record.providerId.lowercase()}:${record.providerRecordId}"
}
