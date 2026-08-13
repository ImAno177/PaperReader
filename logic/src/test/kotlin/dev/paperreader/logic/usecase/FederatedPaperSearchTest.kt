package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderDescriptor
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
        val failure = provider("crossref") { throw ProviderException.RateLimited(1_000) }

        val events = FederatedPaperSearch(listOf(success, failure))
            .search(PaperSearchQuery("graph neural networks"))
            .toList()

        assertEquals(FederatedSearchEvent.Started(setOf("arxiv", "crossref")), events.first())
        assertTrue(events.any { it is FederatedSearchEvent.PageReceived && it.providerId == "arxiv" })
        assertTrue(events.any { it is FederatedSearchEvent.ProviderFailed && it.providerId == "crossref" })
        assertEquals(FederatedSearchEvent.Finished(1, 1), events.last())
    }

    @Test
    fun `clusters only exact identifiers and preserves provider alternatives`() {
        val doi = PaperIdentifier(IdentifierType.DOI, "10.1000/example")
        val arxiv = remote("arxiv", "2401.12345", setOf(doi))
        val crossref = remote("crossref", "10.1000/example", setOf(doi.copy(value = "DOI:10.1000/EXAMPLE")))
        val similarTitleWithoutAlias = remote("other", "record-3")

        val clusters = SearchResultClusterer.cluster(listOf(arxiv, crossref, similarTitleWithoutAlias))

        assertEquals(listOf(2, 1), clusters.map { it.records.size })
    }

    private fun provider(id: String, search: suspend () -> ProviderPage): PaperProvider =
        object : PaperProvider {
            override val descriptor = ProviderDescriptor(id, id, 0)

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
