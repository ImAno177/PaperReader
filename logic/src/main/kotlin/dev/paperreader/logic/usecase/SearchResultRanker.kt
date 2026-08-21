package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import java.text.Normalizer

/** Deterministic local ranking applied after exact-alias clustering. */
object SearchResultRanker {
    private val mainTitleSeparator = Regex("(?::|\\s[-–—]\\s)")

    fun rank(query: String, clusters: List<SearchResultCluster>): List<SearchResultCluster> {
        val normalizedQuery = query.normalizedSearchText()
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotEmpty).toSet()
        val exactKeys = query.exactIdentifierKeys()

        return clusters.sortedWith(
            compareByDescending<SearchResultCluster> { cluster ->
                exactKeys.isNotEmpty() && cluster.records.any { record ->
                    IdentityResolver.exactKeys(record.identifiers).any(exactKeys::contains)
                }
            }.thenByDescending { cluster ->
                cluster.records.any { it.title.normalizedSearchText() == normalizedQuery }
            }.thenByDescending { cluster ->
                cluster.records.maxOf { record -> record.titlePhraseTier(normalizedQuery, queryTokens.size) }
            }.thenByDescending { cluster ->
                cluster.records.maxOf { record -> record.textualScore(normalizedQuery, queryTokens) }
            }.thenByDescending { cluster ->
                cluster.records.maxOfOrNull { record ->
                    record.citationMetrics?.takeIf { it.sourceId == "semanticscholar" }?.count ?: 0
                } ?: 0
            }.thenByDescending { cluster ->
                cluster.records.maxOfOrNull { it.publishedDate ?: java.time.LocalDate.MIN }
            }.thenBy { cluster ->
                cluster.records
                    .map { "${it.providerId.lowercase()}:${it.providerRecordId.lowercase()}" }
                    .sorted()
                    .joinToString("|")
            },
        )
    }

    private fun dev.paperreader.logic.provider.RemotePaper.titlePhraseTier(
        normalizedQuery: String,
        queryTokenCount: Int,
    ): Int {
        if (queryTokenCount < 2) return 0
        val titleText = title.normalizedSearchText()
        val mainTitleText = title.split(mainTitleSeparator, limit = 2).first().normalizedSearchText()
        return when {
            mainTitleText == normalizedQuery -> 3
            titleText.startsWith("$normalizedQuery ") -> 2
            titleText.endsWith(" $normalizedQuery") || titleText.contains(" $normalizedQuery ") -> 1
            else -> 0
        }
    }

    private fun dev.paperreader.logic.provider.RemotePaper.textualScore(
        normalizedQuery: String,
        queryTokens: Set<String>,
    ): Int {
        if (queryTokens.isEmpty()) return 0
        val titleText = title.normalizedSearchText()
        val abstractText = abstractText.orEmpty().normalizedSearchText()
        val authorText = authors.joinToString(" ") { it.displayName }.normalizedSearchText()
        fun coverage(text: String, weight: Int): Int =
            queryTokens.count { token -> text.containsToken(token) } * weight / queryTokens.size

        return coverage(titleText, 7_500) +
            coverage(abstractText, 2_000) +
            coverage(authorText, 500) +
            if (normalizedQuery.isNotEmpty() && titleText.contains(normalizedQuery)) 1_000 else 0
    }

    private fun String.exactIdentifierKeys(): Set<String> = buildSet {
        runCatching { IdentifierNormalizer.doi(this@exactIdentifierKeys) }
            .getOrNull()
            ?.let { add(IdentityResolver.exactKeys(listOf(PaperIdentifier(IdentifierType.DOI, it))).single()) }
        runCatching { IdentifierNormalizer.arxiv(this@exactIdentifierKeys).baseId }
            .getOrNull()
            ?.let { add(IdentityResolver.exactKeys(listOf(PaperIdentifier(IdentifierType.ARXIV, it))).single()) }
        Regex("(?i)^pmid:\\s*(\\d+)$").matchEntire(trim())?.groupValues?.get(1)?.let { value ->
            add(IdentityResolver.exactKeys(listOf(PaperIdentifier(IdentifierType.PMID, value))).single())
        }
        Regex("(?i)^(?:pmcid:\\s*)?(PMC\\d+)$").matchEntire(trim())?.groupValues?.get(1)?.let { value ->
            add(IdentityResolver.exactKeys(listOf(PaperIdentifier(IdentifierType.PMCID, value))).single())
        }
        Regex("(?i)^([a-z0-9][a-z0-9._-]*):(.+)$").matchEntire(trim())?.let { match ->
            val authority = match.groupValues[1].lowercase()
            if (authority !in setOf("doi", "arxiv", "pmid", "pmcid")) {
                add(
                    IdentityResolver.exactKeys(
                        listOf(PaperIdentifier(IdentifierType.PROVIDER, match.groupValues[2].trim(), authority)),
                    ).single(),
                )
            }
        }
    }

    private fun String.normalizedSearchText(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
        .lowercase()
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun String.containsToken(token: String): Boolean =
        this == token || startsWith("$token ") || endsWith(" $token") || contains(" $token ")
}
