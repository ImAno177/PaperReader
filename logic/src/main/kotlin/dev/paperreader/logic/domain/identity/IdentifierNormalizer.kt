package dev.paperreader.logic.domain.identity

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class NormalizedArxivId(
    val baseId: String,
    val version: Int?,
) {
    val versionedId: String = version?.let { "${baseId}v$it" } ?: baseId
}

object IdentifierNormalizer {
    private val doiPattern = Regex("^10\\.\\d{4,9}/\\S+$", RegexOption.IGNORE_CASE)
    private val doiResolverPrefix = Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE)
    private val modernArxivPattern = Regex("^(\\d{4}\\.\\d{4,5})(?:v(\\d+))?$", RegexOption.IGNORE_CASE)
    private val legacyArxivPattern =
        Regex("^([a-z][a-z0-9.-]*/\\d{7})(?:v(\\d+))?$", RegexOption.IGNORE_CASE)

    fun doi(raw: String): String {
        var candidate = raw.trim()
        candidate = candidate.replace(Regex("^doi:\\s*", RegexOption.IGNORE_CASE), "")
        val resolverUrl = doiResolverPrefix.containsMatchIn(candidate)
        candidate = candidate.replace(doiResolverPrefix, "")
        if (resolverUrl) candidate = candidate.substringBefore('?').substringBefore('#')
        candidate = decodePercentEscapes(candidate).trim().lowercase()
        require(doiPattern.matches(candidate)) { "Invalid DOI: $raw" }
        return candidate
    }

    fun arxiv(raw: String): NormalizedArxivId {
        var candidate = raw.trim()
        candidate = candidate.replace(Regex("^arxiv:\\s*", RegexOption.IGNORE_CASE), "")
        candidate = candidate.replace(
            Regex("^https?://(?:www\\.)?arxiv\\.org/(?:abs|html|pdf)/", RegexOption.IGNORE_CASE),
            "",
        )
        candidate = candidate.substringBefore('?').substringBefore('#')
        candidate = candidate.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")

        val match = modernArxivPattern.matchEntire(candidate)
            ?: legacyArxivPattern.matchEntire(candidate)
            ?: throw IllegalArgumentException("Invalid arXiv ID: $raw")
        return NormalizedArxivId(
            baseId = match.groupValues[1].lowercase(),
            version = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt(),
        )
    }

    fun canonical(identifier: PaperIdentifier): PaperIdentifier = when (identifier.type) {
        IdentifierType.DOI -> identifier.copy(value = doi(identifier.value))
        IdentifierType.ARXIV -> identifier.copy(value = arxiv(identifier.value).baseId)
        IdentifierType.PMID,
        IdentifierType.PMCID,
        -> identifier.copy(value = identifier.value.trim().uppercase())
        IdentifierType.PROVIDER -> identifier.copy(
            value = identifier.value.trim(),
            authority = identifier.authority?.trim()?.lowercase(),
        )
    }

    private fun decodePercentEscapes(value: String): String {
        // URLDecoder treats '+' as a space; '+' is valid DOI data, so protect it first.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }
}

object IdentityResolver {
    fun exactKeys(identifiers: Iterable<PaperIdentifier>): Set<String> = identifiers
        .map(IdentifierNormalizer::canonical)
        .mapTo(linkedSetOf()) { identifier ->
            when (identifier.type) {
                IdentifierType.PROVIDER ->
                    "provider:${identifier.authority}:${identifier.value}"
                else -> "${identifier.type.name.lowercase()}:${identifier.value}"
            }
        }

    fun hasExactMatch(
        first: Iterable<PaperIdentifier>,
        second: Iterable<PaperIdentifier>,
    ): Boolean = exactKeys(first).intersect(exactKeys(second)).isNotEmpty()
}
