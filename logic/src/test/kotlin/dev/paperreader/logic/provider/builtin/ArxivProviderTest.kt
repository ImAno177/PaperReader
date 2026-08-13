package dev.paperreader.logic.provider.builtin

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.SearchSort
import java.io.File
import dev.paperreader.logic.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArxivProviderTest {
    private val fixture = File("src/test/resources/provider/arxiv-response.xml").readText()

    @Test fun parsesAtomEntryIntoRemotePaper() {
        val paper = ArxivProvider().parse(fixture).single()
        assertEquals("2401.01234v2", paper.providerRecordId)
        assertEquals("A Quantum Paper", paper.title)
        assertEquals("We study <b>quantum</b> systems.", paper.abstractText)
        assertEquals(2, paper.authors.size)
        assertTrue(paper.identifiers.any { it.type == IdentifierType.ARXIV && it.value == "2401.01234" })
        assertTrue(paper.identifiers.any { it.type == IdentifierType.DOI && it.value == "10.5555/quantum.1" })
        assertEquals("v2", paper.manifestations.single().version)
        assertEquals(setOf("quant-ph", "cs.IT"), paper.subjects)
    }

    @Test fun preservesTheArchivePrefixOfLegacyArxivIds() {
        val paper = ArxivProvider().parse(
            """<?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/hep-th/9707234v2</id>
                    <updated>1997-08-21T09:31:53Z</updated>
                    <published>1997-07-29T07:42:59Z</published>
                    <title>Legacy identifier</title>
                    <author><name>Ada Researcher</name></author>
                  </entry>
                </feed>
            """.trimIndent(),
        ).single()

        assertEquals("hep-th/9707234v2", paper.providerRecordId)
        assertTrue(paper.identifiers.any { it.type == IdentifierType.ARXIV && it.value == "hep-th/9707234" })
    }

    @Test fun buildsStableSearchUrlAndCursor() {
        val url = ArxivProvider(endpoint = "https://example.test/api").buildSearchUrl(
            PaperSearchQuery("machine learning", limit = 10, sort = SearchSort.OLDEST),
            start = 20,
        )
        assertEquals(
            "https://example.test/api?search_query=all:machine%20learning&start=20&max_results=10&sortBy=submittedDate&sortOrder=ascending",
            url,
        )
    }

    @Test(expected = ProviderException.InvalidResponse::class)
    fun rejectsMalformedXml() {
        ArxivProvider().parse("<feed>")
    }

    @Test(expected = ProviderException.InvalidResponse::class)
    fun rejectsDoctypeEvenWhenOptionalParserFeaturesAreUnavailable() {
        ArxivProvider().parse(
            """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><feed xmlns="http://www.w3.org/2005/Atom">&xxe;</feed>""",
        )
    }

    @Test fun exactArxivIdUsesIdListInsteadOfBroadSearch() {
        val url = ArxivProvider(endpoint = "https://example.test/api").buildSearchUrl(
            PaperSearchQuery("arXiv:1706.03762v7", limit = 20),
        )

        assertEquals("https://example.test/api?id_list=1706.03762v7&max_results=1", url)
    }

    @Test fun getUsesVersionedRecordId() {
        // URL construction and parser are covered without a live network; transport is tested separately.
        val id = "https://arxiv.org/pdf/2401.01234v2.pdf"
        assertEquals("2401.01234v2", dev.paperreader.logic.domain.identity.IdentifierNormalizer.arxiv(id).versionedId)
    }
}
