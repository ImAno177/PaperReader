package dev.paperreader.logic.domain.identity

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier

/** A verified identifier plus the exact query understood by the built-in providers. */
data class PaperReferenceQuery(
    val identifier: PaperIdentifier,
    val query: String,
)

/**
 * Parses only unambiguous DOI/arXiv share payloads.
 *
 * A payload may be the identifier itself or ordinary share text containing one supported URL. If
 * it contains multiple distinct paper references, it is rejected instead of guessing which paper
 * the user intended to open.
 */
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
