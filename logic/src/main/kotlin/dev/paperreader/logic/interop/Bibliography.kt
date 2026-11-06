package dev.paperreader.logic.interop

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate

enum class BibliographyFormat {
    BIBTEX,
    RIS,
}

data class BibliographyDate(
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
) {
    init {
        require(year in 1..9999)
        require(month == null || month in 1..12)
        require(day == null || month != null)
        if (month != null && day != null) LocalDate.of(year, month, day)
    }

    fun toLocalDateOrNull(): LocalDate? = if (month != null && day != null) {
        LocalDate.of(year, month, day)
    } else {
        null
    }

    override fun toString(): String = buildString {
        append(year.toString().padStart(4, '0'))
        month?.let { append('-'); append(it.toString().padStart(2, '0')) }
        day?.let { append('-'); append(it.toString().padStart(2, '0')) }
    }
}

data class BibliographyEntry(
    val title: String,
    val authors: List<String> = emptyList(),
    val identifiers: Set<PaperIdentifier> = emptySet(),
    val abstractText: String? = null,
    val publishedAt: BibliographyDate? = null,
    val sourceUrl: String? = null,
    val entryType: String = "article",
    val citationKey: String? = null,
) {
    init {
        require(title.isNotBlank() && title == title.trim() && title.length <= MAX_FIELD_CHARACTERS)
        require(authors.size <= MAX_AUTHORS_PER_ENTRY)
        require(authors.all { it.isNotBlank() && it == it.trim() && it.length <= MAX_FIELD_CHARACTERS })
        require(abstractText == null || (abstractText.isNotBlank() && abstractText.length <= MAX_FIELD_CHARACTERS))
        require(entryType.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")))
        require(entryType == entryType.lowercase())
        require(citationKey == null || citationKey.matches(Regex("[A-Za-z0-9_:.+/-]{1,128}")))
        require(sourceUrl == null || isSafeWebUrl(sourceUrl))
        require(identifiers.all { it.type != IdentifierType.PROVIDER })
        require(identifiers.all { canonicalIdentifier(it.type, it.value) == it }) {
            "Bibliography identifiers must be canonical"
        }
    }
}

enum class BibliographyErrorCode {
    INVALID_UTF8,
    TOO_LARGE,
    MALFORMED,
    UNSUPPORTED_SYNTAX,
    DUPLICATE_FIELD,
    DUPLICATE_RECORD,
    INVALID_METADATA,
    LIMIT_EXCEEDED,
}

data class BibliographyError(val code: BibliographyErrorCode, val message: String)

sealed interface BibliographyParseResult {
    data class Success(val entries: List<BibliographyEntry>) : BibliographyParseResult
    data class Failure(val error: BibliographyError) : BibliographyParseResult
}

object BibliographyCodec {
    fun parse(format: BibliographyFormat, input: ByteArray): BibliographyParseResult {
        if (input.size > MAX_INPUT_BYTES) return failure(BibliographyErrorCode.TOO_LARGE, "Input exceeds the size limit")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input))
                .toString()
        } catch (_: CharacterCodingException) {
            return failure(BibliographyErrorCode.INVALID_UTF8, "Input is not valid UTF-8")
        }
        return parse(format, text)
    }

    fun parse(format: BibliographyFormat, input: String): BibliographyParseResult {
        if (input.toByteArray(StandardCharsets.UTF_8).size > MAX_INPUT_BYTES) {
            return failure(BibliographyErrorCode.TOO_LARGE, "Input exceeds the size limit")
        }
        return try {
            val entries = when (format) {
                BibliographyFormat.BIBTEX -> BibTeXParser(input).parse()
                BibliographyFormat.RIS -> RisParser(input).parse()
            }
            BibliographyParseResult.Success(entries)
        } catch (error: ParseProblem) {
            BibliographyParseResult.Failure(BibliographyError(error.code, error.message ?: "Invalid bibliography"))
        } catch (error: IllegalArgumentException) {
            BibliographyParseResult.Failure(
                BibliographyError(BibliographyErrorCode.INVALID_METADATA, error.message ?: "Invalid metadata"),
            )
        } catch (_: DateTimeException) {
            failure(BibliographyErrorCode.INVALID_METADATA, "Invalid publication date")
        }
    }

    fun encode(format: BibliographyFormat, entries: List<BibliographyEntry>): String {
        require(entries.size <= MAX_RECORDS) { "Too many bibliography records" }
        val estimatedMaximum = entries.sumOf { entry ->
            256L + entry.title.length.toLong() * 16 + entry.authors.sumOf { it.length.toLong() * 16 } +
                (entry.abstractText?.length?.toLong() ?: 0L) * 16 +
                (entry.sourceUrl?.length?.toLong() ?: 0L) * 16 +
                entry.identifiers.sumOf { it.value.length.toLong() * 16 }
        }
        require(estimatedMaximum <= MAX_INPUT_BYTES.toLong()) { "Encoded bibliography exceeds the size limit" }
        val text = when (format) {
            BibliographyFormat.BIBTEX -> BibTeXWriter.write(entries)
            BibliographyFormat.RIS -> RisWriter.write(entries)
        }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_INPUT_BYTES) {
            "Encoded bibliography exceeds the size limit"
        }
        return text
    }
}

internal const val MAX_INPUT_BYTES = 8 * 1024 * 1024
internal const val MAX_RECORDS = 20_000
internal const val MAX_FIELDS_PER_RECORD = 48
internal const val MAX_FIELD_CHARACTERS = 256_000
internal const val MAX_AUTHORS_PER_ENTRY = 256
internal const val MAX_BRACE_DEPTH = 24

internal class ParseProblem(val code: BibliographyErrorCode, message: String) : RuntimeException(message)

internal fun failure(code: BibliographyErrorCode, message: String) =
    BibliographyParseResult.Failure(BibliographyError(code, message))

internal fun canonicalIdentifier(type: IdentifierType, rawValue: String): PaperIdentifier {
    val value = rawValue.trim()
    val canonical = when (type) {
        IdentifierType.DOI -> IdentifierNormalizer.doi(value)
        IdentifierType.ARXIV -> IdentifierNormalizer.arxiv(value).baseId
        IdentifierType.PMID -> value.removePrefixIgnoreCase("PMID:").trim().also {
            require(it.matches(Regex("[0-9]{1,12}"))) { "Invalid PMID" }
        }
        IdentifierType.PMCID -> value.removePrefixIgnoreCase("PMCID:").trim().uppercase().also {
            require(it.matches(Regex("PMC[0-9]{1,12}"))) { "Invalid PMCID" }
        }
        IdentifierType.PROVIDER -> error("Provider identifiers are not bibliography identifiers")
    }
    return IdentifierNormalizer.canonical(PaperIdentifier(type, canonical))
}

internal fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

internal fun isSafeWebUrl(raw: String): Boolean = runCatching {
    val uri = java.net.URI(raw.trim())
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

fun stableBibliographyRecordId(entry: BibliographyEntry): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val fields = buildList {
        add(entry.entryType.lowercase())
        add(entry.title.trim())
        addAll(entry.authors.map(String::trim))
        addAll(entry.identifiers.sortedWith(compareBy({ it.type.name }, { it.value })).map { "${it.type.name}:${it.value}" })
        entry.publishedAt?.let { add(it.toString()) }
        entry.abstractText?.let { add(it.trim()) }
        entry.sourceUrl?.let { add(it.trim()) }
    }
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    val hex = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    return "bib-$hex"
}

internal fun dateFromFields(
    date: String?,
    year: String?,
    month: String?,
    day: String?,
): BibliographyDate? {
    val fromDate = date?.trim()?.takeIf(String::isNotEmpty)?.let(::parseDate)
    val partYear = year?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: if (year?.isNotBlank() == true) throw IllegalArgumentException("Invalid publication year") else null
    val partMonth = month?.trim()?.takeIf(String::isNotEmpty)?.let(::parseMonth)
    val partDay = day?.trim()?.takeIf(String::isNotEmpty)?.toIntOrNull()
        ?: if (day?.isNotBlank() == true) throw IllegalArgumentException("Invalid publication day") else null
    if (fromDate == null && partYear == null && (partMonth != null || partDay != null)) {
        throw IllegalArgumentException("Month and day require a publication year")
    }
    if (fromDate != null && partYear != null && fromDate.year != partYear) {
        throw IllegalArgumentException("Conflicting publication year fields")
    }
    if (fromDate?.month != null && partMonth != null && fromDate.month != partMonth) {
        throw IllegalArgumentException("Conflicting publication month fields")
    }
    if (fromDate?.day != null && partDay != null && fromDate.day != partDay) {
        throw IllegalArgumentException("Conflicting publication day fields")
    }
    val resolvedYear = fromDate?.year ?: partYear ?: return null
    return BibliographyDate(
        year = resolvedYear,
        month = fromDate?.month ?: partMonth,
        day = fromDate?.day ?: partDay,
    )
}

internal fun parseDate(value: String): BibliographyDate {
    val match = Regex("^(\\d{4})(?:[-/](\\d{1,2})(?:[-/](\\d{1,2}))?)?$").matchEntire(value.trim())
        ?: throw IllegalArgumentException("Invalid publication date")
    return BibliographyDate(
        year = match.groupValues[1].toInt(),
        month = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt(),
        day = match.groupValues[3].takeIf(String::isNotEmpty)?.toInt(),
    )
}

private fun parseMonth(value: String): Int {
    value.toIntOrNull()?.let { return it }
    return when (value.trim().lowercase().removeSuffix(".")) {
        "jan", "january" -> 1
        "feb", "february" -> 2
        "mar", "march" -> 3
        "apr", "april" -> 4
        "may" -> 5
        "jun", "june" -> 6
        "jul", "july" -> 7
        "aug", "august" -> 8
        "sep", "sept", "september" -> 9
        "oct", "october" -> 10
        "nov", "november" -> 11
        "dec", "december" -> 12
        else -> throw IllegalArgumentException("Unsupported month value")
    }
}
