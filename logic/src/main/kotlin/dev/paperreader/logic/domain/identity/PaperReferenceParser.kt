package dev.paperreader.logic.domain.identity

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier

data class PaperReferenceQuery(
    val identifier: PaperIdentifier,
    val query: String,
)

object PaperReferenceParser {
    private const val MAX_SHARED_TEXT_LENGTH = 16_384
    private val supportedUrlPattern = Regex(
        """https?://(?:(?:www\.)?arxiv\.org/(?:abs|html|pdf)/|(?:dx\.)?doi\.org/)\S+""",
        RegexOption.IGNORE_CASE,
    )

    fun parseSharedText(raw: String): PaperReferenceQuery? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_SHARED_TEXT_LENGTH) return null

        val references = buildList {
            parseExact(text)?.let(::add)
            supportedUrlPattern.findAll(text).forEach { match ->
                parseExact(match.value)?.let(::add)
            }
        }.distinctBy { reference ->
            "${reference.identifier.type}:${reference.query}"
        }

        return references.singleOrNull()
    }

    private fun parseExact(raw: String): PaperReferenceQuery? {
        runCatching { IdentifierNormalizer.doi(raw) }.getOrNull()?.let { doi ->
            return PaperReferenceQuery(
                identifier = PaperIdentifier(IdentifierType.DOI, doi),
                query = doi,
            )
        }
        runCatching { IdentifierNormalizer.arxiv(raw) }.getOrNull()?.let { arxiv ->
            return PaperReferenceQuery(
                identifier = PaperIdentifier(IdentifierType.ARXIV, arxiv.baseId),
                query = arxiv.versionedId,
            )
        }
        return null
    }
}
