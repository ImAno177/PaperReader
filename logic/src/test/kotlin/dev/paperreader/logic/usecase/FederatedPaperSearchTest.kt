package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.SearchSort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FederatedPaperSearchTest {
    @Test
    fun `one failing provider does not cancel successful providers`() = runTest {
        val success = provider("arxiv") { ProviderPage(listOf(remote("arxiv", "2401.1"))) }
        val failure = provider("semanticscholar") { throw ProviderException.RateLimited(1_000) }

        val events = FederatedPaperSearch(listOf(success, failure))
            .search(PaperSearchQuery("graph neural networks"))
            .toList()

        assertEquals(FederatedSearchEvent.Started(setOf("arxiv", "semanticscholar")), events.first())
        assertTrue(events.any { it is FederatedSearchEvent.PageReceived && it.providerId == "arxiv" })
        assertTrue(events.any { it is FederatedSearchEvent.ProviderFailed && it.providerId == "semanticscholar" })
        assertEquals(FederatedSearchEvent.Finished(1, 1), events.last())
    }

    @Test
    fun `metadata resolvers are excluded from unstructured discovery`() = runTest {
        val discovery = provider("arxiv") { ProviderPage(listOf(remote("arxiv", "2401.1"))) }
        var metadataCalls = 0
        val metadata = provider(
            id = "metadata-only",
            capabilities = setOf(ProviderCapability.METADATA_RESOLUTION),
            identifierTypes = setOf(IdentifierType.DOI),
        ) {
            metadataCalls += 1
            ProviderPage(emptyList())
        }

        val events = FederatedPaperSearch(listOf(discovery, metadata))
            .search(PaperSearchQuery("attention mechanisms"))
            .toList()

        assertEquals(FederatedSearchEvent.Started(setOf("arxiv")), events.first())
        assertEquals(0, metadataCalls)
    }

    @Test
    fun `exact identifiers route only to providers that declare that identifier type`() = runTest {
        val arxiv = provider("arxiv", identifierTypes = setOf(IdentifierType.ARXIV)) { ProviderPage(emptyList()) }
        val semanticScholar = provider(
            "semanticscholar",
            capabilities = setOf(ProviderCapability.METADATA_RESOLUTION),
            identifierTypes = setOf(IdentifierType.DOI),
        ) { ProviderPage(emptyList()) }
        val europePmc = provider(
            "europepmc",
            identifierTypes = setOf(IdentifierType.DOI, IdentifierType.PMID, IdentifierType.PMCID),
        ) { ProviderPage(emptyList()) }

        val pmidEvents = FederatedPaperSearch(listOf(arxiv, semanticScholar, europePmc))
            .search(PaperSearchQuery("PMID:41694193"))
            .toList()
        val doiEvents = FederatedPaperSearch(listOf(arxiv, semanticScholar, europePmc))
            .search(PaperSearchQuery("doi:10.1234/example"))
            .toList()

        assertEquals(FederatedSearchEvent.Started(setOf("europepmc")), pmidEvents.first())
        assertEquals(FederatedSearchEvent.Started(setOf("semanticscholar", "europepmc")), doiEvents.first())
    }

    @Test
    fun `clusters only exact identifiers and preserves provider alternatives`() {
        val doi = PaperIdentifier(IdentifierType.DOI, "10.1000/example")
        val arxiv = remote("arxiv", "2401.12345", setOf(doi))
        val semanticScholar = remote("semanticscholar", "s2-123", setOf(doi.copy(value = "DOI:10.1000/EXAMPLE")))
        val similarTitleWithoutAlias = remote("other", "record-3")

        val clusters = SearchResultClusterer.cluster(listOf(arxiv, semanticScholar, similarTitleWithoutAlias))

        assertEquals(listOf(2, 1), clusters.map { it.records.size })
    }

    @Test
    fun `a bridging record merges multiple exact-alias clusters transitively`() {
        val doi = PaperIdentifier(IdentifierType.DOI, "10.1000/bridge")
        val arxiv = PaperIdentifier(IdentifierType.ARXIV, "2401.12345")
        val first = remote("arxiv", "2401.12345", setOf(doi))
        val second = remote("crossref", "10.1000/bridge", setOf(arxiv))
        val bridge = remote("semanticscholar", "s2-bridge", setOf(doi, arxiv))

        val clusters = SearchResultClusterer.cluster(listOf(first, second, bridge))

        assertEquals(1, clusters.size)
        assertEquals(setOf("arxiv", "crossref", "semanticscholar"), clusters.single().records.map { it.providerId }.toSet())
    }

    @Test
    fun `generic provider failures become unavailable events and unsupported sorts are skipped`() = runTest {
        val genericFailure = provider("generic") { error("offline") }
        val newestOnly = provider("newest", supportedSorts = setOf(SearchSort.NEWEST)) {
            ProviderPage(emptyList())
        }

        val events = FederatedPaperSearch(listOf(genericFailure, newestOnly))
            .search(PaperSearchQuery("query", sort = SearchSort.RELEVANCE))
            .toList()

        assertEquals(FederatedSearchEvent.Started(setOf("generic")), events.first())
        val failure = events.filterIsInstance<FederatedSearchEvent.ProviderFailed>().single()
        assertTrue(failure.error is ProviderException.Unavailable)
        assertEquals(FederatedSearchEvent.Finished(0, 1), events.last())
    }

    @Test
    fun `empty federated search still emits a complete lifecycle`() = runTest {
        val events = FederatedPaperSearch(emptyList()).search(PaperSearchQuery("query")).toList()

        assertEquals(FederatedSearchEvent.Started(emptySet()), events[0])
        assertEquals(FederatedSearchEvent.Finished(0, 0), events[1])
    }

    private fun provider(
        id: String,
        capabilities: Set<ProviderCapability> = setOf(ProviderCapability.DISCOVERY),
        identifierTypes: Set<IdentifierType> = IdentifierType.entries.toSet(),
        supportedSorts: Set<SearchSort> = SearchSort.entries.toSet(),
        search: suspend () -> ProviderPage,
    ): PaperProvider =
        object : PaperProvider {
            override val descriptor = ProviderDescriptor(
                id = id,
                displayName = id,
                minimumRequestIntervalMillis = 0,
                roles = setOf(ProviderRole.SEARCH_ENGINE),
                capabilities = capabilities,
                identifierLookupTypes = identifierTypes,
                supportedSorts = supportedSorts,
            )

            override suspend fun search(query: PaperSearchQuery): ProviderPage = search()

            override suspend fun get(recordId: String): RemotePaper? = null
        }

    private fun remote(
        provider: String,
        id: String,
        identifiers: Set<PaperIdentifier> = emptySet(),
    ) = RemotePaper(
        providerId = provider,
        providerRecordId = id,
        title = "A shared or similar title",
        identifiers = identifiers,
    )
}
