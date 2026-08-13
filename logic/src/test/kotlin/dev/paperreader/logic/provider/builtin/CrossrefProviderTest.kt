package dev.paperreader.logic.provider.builtin

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.SearchSort
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossrefProviderTest {
    @Test fun parsesSearchMessageAndMapsMetadata() {
        val body = File("src/test/resources/provider/crossref-search.json").readText()
        val observedAt = Instant.parse("2026-08-13T00:00:00Z")
        val provider = CrossrefProvider(clock = Clock.fixed(observedAt, ZoneOffset.UTC))
        val page = provider.parseBody(body)
        val paper = page.items.single()
        assertEquals("10.1234/abc.1", paper.providerRecordId)
        assertEquals("A Crossref Paper", paper.title)
        assertEquals("Abstract text.", paper.abstractText)
        assertEquals("2023-07-02", paper.publishedDate.toString())
        assertTrue(paper.identifiers.contains(dev.paperreader.logic.domain.PaperIdentifier(IdentifierType.DOI, "10.1234/abc.1")))
        assertEquals("https://example.org/paper.pdf", paper.manifestations.single().pdfUrl)
        assertEquals(42, paper.citationMetrics?.count)
        assertEquals("crossref", paper.citationMetrics?.sourceId)
        assertEquals(observedAt, paper.citationMetrics?.observedAt)
        assertEquals("next-token", page.nextCursor)
    }

    @Test fun buildsSearchUrlWithEncodedCursor() {
        val url = CrossrefProvider(endpoint = "https://example.test").buildSearchUrl(
            PaperSearchQuery("graph neural networks", limit = 5, cursor = "a/b", sort = SearchSort.NEWEST),
        )
        assertTrue(url.contains("query.bibliographic=graph%20neural%20networks"))
        assertTrue(url.contains("rows=5"))
        assertTrue(url.contains("cursor=a%2Fb"))
        assertTrue(url.contains("sort=published"))
    }

    @Test fun exactDoiUsesTheCrossrefExactFilterInsteadOfNoisyBibliographicSearch() {
        val url = CrossrefProvider(endpoint = "https://example.test").buildSearchUrl(
            PaperSearchQuery(
                "https://doi.org/10.1371/JOURNAL.PMED.0020124",
                limit = 20,
                cursor = "ignored",
                sort = SearchSort.OLDEST,
            ),
        )

        assertEquals(
            "https://example.test/works?filter=doi:10.1371%2Fjournal.pmed.0020124&rows=1",
            url,
        )
    }

    @Test fun exactDoiSearchRequestsOneExactFilteredRecord() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(File("src/test/resources/provider/crossref-search.json").readText()),
        )
        server.start()
        try {
            val provider = CrossrefProvider(endpoint = server.url("/").toString().removeSuffix("/"))

            val page = provider.search(PaperSearchQuery("doi:10.1234/ABC.1", limit = 20))

            assertEquals("10.1234/abc.1", page.items.single().providerRecordId)
            assertEquals(
                "/works?filter=doi:10.1234%2Fabc.1&rows=1",
                server.takeRequest().path,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = dev.paperreader.logic.provider.ProviderException.InvalidResponse::class)
    fun rejectsMalformedJson() {
        CrossrefProvider().parseBody("not json")
    }

    @Test fun malformedDatePartDoesNotPoisonThePage() {
        val body = File("src/test/resources/provider/crossref-mixed-dates.json").readText()

        val papers = CrossrefProvider().parseBody(body).items

        assertEquals(2, papers.size)
        assertEquals("2024-06-03", papers[0].publishedDate.toString())
        assertNull(papers[1].publishedDate)
        assertEquals("10.56065/icebm", papers[1].providerRecordId)
    }

    @Test fun exactArxivIdentifierDoesNotProduceCrossrefNoise() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val provider = CrossrefProvider(endpoint = server.url("/").toString().removeSuffix("/"))

            val page = provider.search(PaperSearchQuery("1706.03762"))

            assertTrue(page.items.isEmpty())
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
