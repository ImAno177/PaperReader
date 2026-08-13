package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

    private fun provider(
        id: String,
        capabilities: Set<ProviderCapability> = setOf(ProviderCapability.DISCOVERY),
        identifierTypes: Set<IdentifierType> = IdentifierType.entries.toSet(),
        search: suspend () -> ProviderPage,
    ): PaperProvider =
        object : PaperProvider {
            override val descriptor = ProviderDescriptor(
                id = id,
                displayName = id,
                minimumRequestIntervalMillis = 0,
                capabilities = capabilities,
                identifierLookupTypes = identifierTypes,
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
