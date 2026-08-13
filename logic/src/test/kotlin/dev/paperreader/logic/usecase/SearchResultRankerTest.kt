package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.CitationMetrics
import dev.paperreader.logic.provider.RemotePaper
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultRankerTest {
    @Test
    fun `ranking is stable across provider completion order`() {
        val strong = paper("arxiv", "2", "Attention is all you need", abstractText = "Transformer models")
        val weak = paper("semanticscholar", "1", "A survey", abstractText = "Attention in neural networks")

        val forward = SearchResultRanker.rank("attention transformer", SearchResultClusterer.cluster(listOf(weak, strong)))
        val reverse = SearchResultRanker.rank("attention transformer", SearchResultClusterer.cluster(listOf(strong, weak)))

        assertEquals(listOf("2", "1"), forward.map { it.records.single().providerRecordId })
        assertEquals(forward.map(::stableKey), reverse.map(::stableKey))
    }

    @Test
    fun `citation count only breaks equal textual relevance`() {
        val strong = paper("arxiv", "strong", "Graph attention networks")
        val citedWeak = paper(
            "semanticscholar",
            "weak",
            "A broad machine learning survey",
            abstractText = "Includes a short discussion of graph methods.",
            citations = 100_000,
        )

        val ranked = SearchResultRanker.rank("graph attention", clusters(strong, citedWeak))

        assertEquals("strong", ranked.first().records.single().providerRecordId)
    }

    @Test
    fun `exact canonical identifier outranks title and citations`() {
        val exact = paper(
            "arxiv",
            "1706.03762v7",
            "Attention is all you need",
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
        )
        val popular = paper("semanticscholar", "popular", "1706 03762 analysis", citations = 1_000_000)

        val ranked = SearchResultRanker.rank("https://arxiv.org/abs/1706.03762v7", clusters(popular, exact))

        assertEquals("1706.03762v7", ranked.first().records.single().providerRecordId)
    }

    @Test
    fun `explicit PMID PMCID and provider identifiers are exact matches`() {
        val pmid = paper(
            "pubmed",
            "weak-title",
            "Unrelated title",
            identifiers = setOf(PaperIdentifier(IdentifierType.PMID, "12345")),
        )
        val provider = paper(
            "semanticscholar",
            "s2-123",
            "Another unrelated title",
            identifiers = setOf(PaperIdentifier(IdentifierType.PROVIDER, "s2-123", "semanticscholar")),
        )

        assertEquals("weak-title", SearchResultRanker.rank("PMID:12345", clusters(provider, pmid)).first().records.single().providerRecordId)
        assertEquals("s2-123", SearchResultRanker.rank("semanticscholar:s2-123", clusters(pmid, provider)).first().records.single().providerRecordId)
    }

    @Test
    fun `equal results use publication date then stable provider record key`() {
        val old = paper("zeta", "2", "Shared title", publishedDate = LocalDate.of(2020, 1, 1))
        val newB = paper("beta", "1", "Shared title", publishedDate = LocalDate.of(2024, 1, 1))
        val newA = paper("alpha", "1", "Shared title", publishedDate = LocalDate.of(2024, 1, 1))

        val ranked = SearchResultRanker.rank("shared", clusters(old, newB, newA))

        assertEquals(listOf("alpha", "beta", "zeta"), ranked.map { it.records.single().providerId })
    }

    private fun clusters(vararg records: RemotePaper) = records.map { SearchResultCluster(listOf(it)) }

    private fun stableKey(cluster: SearchResultCluster) = cluster.records
        .map { "${it.providerId}:${it.providerRecordId}" }
        .sorted()
        .joinToString("|")

    private fun paper(
        provider: String,
        id: String,
        title: String,
        abstractText: String? = null,
        identifiers: Set<PaperIdentifier> = emptySet(),
        citations: Int? = null,
        publishedDate: LocalDate? = null,
    ) = RemotePaper(
        providerId = provider,
        providerRecordId = id,
        title = title,
        abstractText = abstractText,
        identifiers = identifiers,
        citationMetrics = citations?.let { CitationMetrics(it, provider, Instant.parse("2026-08-13T00:00:00Z")) },
        publishedDate = publishedDate,
    )
}
