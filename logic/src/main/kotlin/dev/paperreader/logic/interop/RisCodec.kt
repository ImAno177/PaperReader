package dev.paperreader.logic.interop

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier

internal class RisParser(private val source: String) {
    fun parse(): List<BibliographyEntry> {
        val entries = mutableListOf<BibliographyEntry>()
        var fields: LinkedHashMap<String, MutableList<String>>? = null
        var recordFieldCount = 0
        var previousTag: String? = null
        source.lineSequence().forEach { line ->
            if (line.startsWith("      ") && previousTag in setOf("AB", "N2")) {
                val values = fields?.get(previousTag) ?: malformed("RIS continuation has no field")
                val combined = values.last() + "\n" + line.drop(6)
                if (combined.length > MAX_FIELD_CHARACTERS) limit("RIS field is too long")
                values[values.lastIndex] = combined
                return@forEach
            }
            if (line.isBlank()) return@forEach
            val match = TAG.matchEntire(line) ?: malformed("RIS input must contain a tagged field or indented abstract continuation")
            val tag = match.groupValues[1]
            val value = match.groupValues[2]
            previousTag = tag
            val currentFields = fields
            if (currentFields == null) {
                if (tag != "TY") malformed("RIS record must begin with TY")
                fields = linkedMapOf("TY" to mutableListOf(value))
                recordFieldCount = 1
                return@forEach
            }
            if (tag == "TY") malformed("RIS record is missing ER")
            if (tag == "ER") {
                if (value.isNotBlank()) malformed("RIS ER terminator must have an empty value")
                entries += makeEntry(currentFields)
                fields = null
                recordFieldCount = 0
                if (entries.size > MAX_RECORDS) limit("Too many RIS records")
                return@forEach
            }
            if (tag !in REPEATABLE && currentFields.containsKey(tag)) {
                problem(BibliographyErrorCode.DUPLICATE_FIELD, "Duplicate RIS field '$tag'")
            }
            if (recordFieldCount >= MAX_FIELDS_PER_RECORD) limit("Too many RIS fields")
            recordFieldCount++
            val values = currentFields.getOrPut(tag) { mutableListOf() }
            if (values.size >= MAX_FIELDS_PER_RECORD) limit("Too many RIS fields")
            if (value.length > MAX_FIELD_CHARACTERS) limit("RIS field is too long")
            values += value
        }
        if (fields != null) malformed("RIS record is missing ER")
        return entries
    }

    private fun makeEntry(fields: Map<String, List<String>>): BibliographyEntry {
        fun oneOf(vararg tags: String): String? {
            val present = tags.mapNotNull { tag -> fields[tag]?.singleOrNull()?.let { tag to it } }
            val values = present.map { it.second.trim() }.distinct()
            if (values.size > 1) malformed("RIS has conflicting ${tags.joinToString("/")} fields")
            return values.singleOrNull()
        }

        val title = oneOf("TI", "T1")?.trim().orEmpty()
        if (title.isBlank()) malformed("RIS record is missing a title")
        val authors = (fields["AU"].orEmpty() + fields["A1"].orEmpty()).map(String::trim)
        if (authors.size > MAX_AUTHORS_PER_ENTRY || authors.any(String::isBlank)) limit("Invalid or excessive RIS authors")
        val identifiers = linkedSetOf<PaperIdentifier>()
        oneOf("DO")?.let { identifiers += canonicalIdentifier(IdentifierType.DOI, it) }
        fields["AN"].orEmpty().forEach { accession ->
            val value = accession.trim()
            when {
                value.startsWith("arxiv:", ignoreCase = true) -> identifiers += canonicalIdentifier(IdentifierType.ARXIV, value)
                value.startsWith("pmid:", ignoreCase = true) -> identifiers += canonicalIdentifier(IdentifierType.PMID, value)
                value.startsWith("pmcid:", ignoreCase = true) -> identifiers += canonicalIdentifier(IdentifierType.PMCID, value)
                value.startsWith("doi:", ignoreCase = true) -> identifiers += canonicalIdentifier(IdentifierType.DOI, value)
            }
        }
        val dates = listOf("Y1", "PY", "DA").mapNotNull { fields[it]?.singleOrNull()?.let { value -> parseDate(value.trim()) } }
        val date = dates.maxByOrNull { if (it.day != null) 3 else if (it.month != null) 2 else 1 }
        if (date != null && dates.any { it.year != date.year || it.month != null && date.month != it.month || it.day != null && date.day != it.day }) {
            malformed("RIS has conflicting publication dates")
        }
        val url = (fields["UR"]?.singleOrNull() ?: fields["L2"]?.singleOrNull())?.trim()?.takeIf(String::isNotEmpty)
        if (url != null && !isSafeWebUrl(url)) malformed("RIS URL must use HTTP(S)")
        val recordType = fields["TY"]?.singleOrNull()?.trim()?.uppercase()
            ?.takeIf(String::isNotEmpty) ?: malformed("RIS record has an invalid type")
        return BibliographyEntry(
            title = title,
            authors = authors,
            identifiers = identifiers,
            abstractText = oneOf("AB", "N2")?.takeIf(String::isNotBlank),
            publishedAt = date,
            sourceUrl = url,
            entryType = fromRisType(recordType),
        )
    }

    private fun malformed(message: String): Nothing = problem(BibliographyErrorCode.MALFORMED, message)
    private fun limit(message: String): Nothing = problem(BibliographyErrorCode.LIMIT_EXCEEDED, message)
    private fun problem(code: BibliographyErrorCode, message: String): Nothing = throw ParseProblem(code, message)

    private companion object {
        val TAG = Regex("^([A-Z0-9]{2})  - ?(.*)$")
        val REPEATABLE = setOf("AU", "A1", "AN")
    }
}

internal object RisWriter {
    fun write(entries: List<BibliographyEntry>): String = BoundedBibliographyText().apply {
        entries.forEachIndexed { index, entry ->
            if (index > 0) append('\n')
            appendTag("TY", toRisType(entry.entryType))
            entry.authors.forEach { appendTag("AU", safeLine(it)) }
            appendTag("TI", safeLine(entry.title))
            entry.publishedAt?.let { date ->
                val value = buildString {
                    append(date.year.toString().padStart(4, '0'))
                    date.month?.let { append('/'); append(it.toString().padStart(2, '0')) }
                    date.day?.let { append('/'); append(it.toString().padStart(2, '0')) }
                }
                appendTag(if (date.month == null) "PY" else "Y1", value)
            }
            val doiIdentifiers = entry.identifiers.filter { it.type == IdentifierType.DOI }.sortedBy(PaperIdentifier::value)
            doiIdentifiers.firstOrNull()?.let { appendTag("DO", safeLine(it.value)) }
            (entry.identifiers.filter { it.type != IdentifierType.DOI } + doiIdentifiers.drop(1))
                .sortedBy { it.type.name }
                .forEach { identifier ->
                    val label = when (identifier.type) {
                        IdentifierType.ARXIV -> "arXiv"
                        IdentifierType.PMID -> "PMID"
                        IdentifierType.PMCID -> "PMCID"
                        IdentifierType.DOI -> "DOI"
                        IdentifierType.PROVIDER -> error("Provider IDs are not bibliographic")
                    }
                    appendTag("AN", "$label:${safeLine(identifier.value)}")
                }
            entry.abstractText?.let { text ->
                val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                appendTag("AB", safeLine(lines.first()))
                lines.drop(1).forEach { append("      ").append(safeLine(it)).append("\r\n") }
            }
            entry.sourceUrl?.let { appendTag("UR", safeLine(it)) }
            appendTag("ER", "")
        }
    }.toString()

    private fun BoundedBibliographyText.appendTag(tag: String, value: String) {
        append(tag).append("  - ").append(value).append("\r\n")
    }

    private fun safeLine(value: String): String {
        require('\n' !in value && '\r' !in value && '\u0000' !in value) { "RIS values must not contain line breaks or NUL" }
        return value
    }

    private fun toRisType(type: String): String = when (type.lowercase()) {
        "article", "journalarticle" -> "JOUR"
        "inproceedings", "conference" -> "CPAPER"
        "book" -> "BOOK"
        "incollection", "chapter" -> "CHAP"
        "phdthesis", "mastersthesis", "thesis" -> "THES"
        else -> "GEN"
    }

}

private fun fromRisType(type: String): String = when (type) {
    "JOUR" -> "article"
    "CPAPER" -> "inproceedings"
    "BOOK" -> "book"
    "CHAP" -> "incollection"
    "THES" -> "thesis"
    else -> "misc"
}
