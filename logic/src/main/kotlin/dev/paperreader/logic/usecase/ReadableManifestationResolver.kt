package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderManager
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Resolves a readable manifestation for identifier-only search records before they are saved.
 *
 * Discovery providers are intentionally not queried here. Only providers that explicitly own a
 * content manifestation are allowed to answer an exact identifier lookup, and every response is
 * checked against the identifier that initiated the lookup before it is persisted.
 */
fun interface SavedPaperEnricher {
    suspend fun enrich(paper: RemotePaper): List<RemotePaper>
}

internal class ReadableManifestationResolver(
    private val providers: ProviderManager,
) : SavedPaperEnricher {
    override suspend fun enrich(paper: RemotePaper): List<RemotePaper> {
        if (paper.manifestations.isNotEmpty()) return listOf(paper)

        val identifiers = paper.identifiers
            .asSequence()
            .filter { it.type != IdentifierType.PROVIDER }
            .distinctBy { IdentityResolver.exactKeys(listOf(it)).single() }
            .toList()
        if (identifiers.isEmpty()) return listOf(paper)

        val contentProviders = providers.getAll()
            .filter { ProviderRole.CONTENT_SOURCE in it.descriptor.roles }
            .sortedWith(
                compareBy<PaperProvider> { if (it.descriptor.id.equals(paper.providerId, ignoreCase = true)) 0 else 1 }
                    .thenBy { it.descriptor.id },
            )
        var attempts = 0
        for (provider in contentProviders) {
            val supported = identifiers.filter { identifier ->
                identifier.type in provider.descriptor.identifierLookupTypes
            }
            if (supported.isEmpty()) continue

            if (provider.descriptor.id.equals(paper.providerId, ignoreCase = true)) {
                val refreshed = runProviderCall { provider.get(paper.providerRecordId) }
                if (refreshed != null && refreshed.hasReadableManifestation() && refreshed.isExactRecordFor(paper)) {
                    return listOf(paper, refreshed)
                }
                attempts++
                if (attempts >= MAX_LOOKUP_ATTEMPTS) return listOf(paper)
            }

            if (ProviderCapability.DISCOVERY !in provider.descriptor.capabilities) continue
            val sort = if (SearchSort.RELEVANCE in provider.descriptor.supportedSorts) {
                SearchSort.RELEVANCE
            } else {
                provider.descriptor.supportedSorts.firstOrNull() ?: continue
            }
            for (identifier in supported) {
                if (attempts >= MAX_LOOKUP_ATTEMPTS) return listOf(paper)
                val query = identifier.toExactQuery()
                val page = runProviderCall {
                    provider.search(PaperSearchQuery(query, limit = EXACT_LOOKUP_LIMIT, sort = sort))
                }
                attempts++
                val match = page?.items?.firstOrNull { candidate ->
                    candidate.hasReadableManifestation() && candidate.isExactRecordFor(paper)
                }
                if (match != null) return listOf(paper, match)
            }
        }
        return listOf(paper)
    }

    private suspend fun <T> runProviderCall(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ProviderException) {
        null
    } catch (_: Exception) {
        // A broken optional extension must never make saving an otherwise valid metadata record fail.
        null
    }

    private fun RemotePaper.hasReadableManifestation(): Boolean = manifestations.isNotEmpty()

    private fun RemotePaper.isExactRecordFor(original: RemotePaper): Boolean =
        IdentityResolver.hasExactMatch(identifiers, original.identifiers) ||
            (providerId.equals(original.providerId, ignoreCase = true) && providerRecordId == original.providerRecordId)

    private fun PaperIdentifier.toExactQuery(): String = when (type) {
        IdentifierType.DOI -> "doi:${value.trim().lowercase(Locale.ROOT)}"
        IdentifierType.ARXIV -> "arxiv:${value.trim()}"
        IdentifierType.PMID -> "PMID:${value.trim()}"
        IdentifierType.PMCID -> "PMCID:${value.trim()}"
        IdentifierType.PROVIDER -> error("Provider identifiers are not exact cross-provider aliases")
    }

    private companion object {
        const val EXACT_LOOKUP_LIMIT = 5
        const val MAX_LOOKUP_ATTEMPTS = 12
    }
}
