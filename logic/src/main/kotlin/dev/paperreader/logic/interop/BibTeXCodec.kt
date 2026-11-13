package dev.paperreader.logic.interop

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import java.util.Locale

internal class BibTeXParser(private val source: String) {
    private var position = 0
    private val citationKeys = mutableSetOf<String>()

    fun parse(): List<BibliographyEntry> {
        val entries = mutableListOf<BibliographyEntry>()
        while (true) {
            skipWhitespace()
            if (position == source.length) return entries
            if (source[position] != '@') malformed("Expected a BibTeX entry")
            position++
            val type = readToken().lowercase(Locale.ROOT)
            if (type in setOf("string", "preamble", "comment")) {
                unsupported("BibTeX @${type} directives are not evaluated or imported")
            }
            val opener = nextNonWhitespace()
            if (opener != '{' && opener != '(') malformed("Expected an entry delimiter")
            position++
            val closer = if (opener == '{') '}' else ')'
            val key = readUntilCommaOrClose(closer).trim()
            if (key.isEmpty() || key.length > 128 || !key.matches(Regex("[A-Za-z0-9_:.+/-]+"))) {
                malformed("Invalid citation key")
            }
            if (!citationKeys.add(key.lowercase(Locale.ROOT))) {
                problem(BibliographyErrorCode.DUPLICATE_RECORD, "Duplicate BibTeX citation key")
            }
            if (position >= source.length || source[position] != ',') malformed("Expected a comma after citation key")
            position++

            val fields = linkedMapOf<String, String>()
            while (true) {
                skipWhitespace()
                if (position >= source.length) malformed("Unterminated BibTeX entry")
                if (source[position] == closer) {
                    position++
                    break
                }
                if (fields.size >= MAX_FIELDS_PER_RECORD) limit("Too many BibTeX fields")
                val field = readFieldName()
                if (field.isEmpty()) malformed("Invalid BibTeX field name")
                skipWhitespace()
                if (position >= source.length || source[position] != '=') malformed("Expected '=' after BibTeX field")
                position++
                skipWhitespace()
                val value = readValue(field)
                if (fields.putIfAbsent(field, value) != null) {
                    problem(BibliographyErrorCode.DUPLICATE_FIELD, "Duplicate BibTeX field '$field'")
                }
                skipWhitespace()
                if (position < source.length && source[position] == '#') unsupported("BibTeX value concatenation is not supported")
                if (position < source.length && source[position] == ',') {
                    position++
                    skipWhitespace()
                    if (position < source.length && source[position] == ',') malformed("Unexpected repeated comma")
                } else if (position < source.length && source[position] != closer) {
                    malformed("Expected a comma or entry delimiter")
                }
            }
            if (entries.size >= MAX_RECORDS) limit("Too many BibTeX records")
            entries += entry(type, key, fields)
        }
    }

    private fun entry(type: String, key: String, fields: Map<String, String>): BibliographyEntry {
        val title = fields["title"]?.let(::decodeBibEscapes)?.trim().orEmpty()
        if (title.isBlank()) malformed("BibTeX record is missing a title")
        val authors = fields["author"]?.let(::splitBibAuthors)?.map(::decodeBibAuthor).orEmpty()
        val identifiers = linkedSetOf<PaperIdentifier>()
        fields["doi"]?.let { identifiers += canonicalIdentifier(IdentifierType.DOI, decodeBibEscapes(it)) }
        fields["arxiv"]?.let { identifiers += canonicalIdentifier(IdentifierType.ARXIV, decodeBibEscapes(it)) }
        val archive = fields["archiveprefix"] ?: fields["eprinttype"]
        fields["eprint"]?.let { raw ->
            val eprint = decodeBibEscapes(raw)
            val isArxiv = archive.equals("arxiv", ignoreCase = true) ||
                runCatching { canonicalIdentifier(IdentifierType.ARXIV, eprint) }.isSuccess
            if (isArxiv) identifiers += canonicalIdentifier(IdentifierType.ARXIV, eprint)
        }
        fields["pmid"]?.let { identifiers += canonicalIdentifier(IdentifierType.PMID, decodeBibEscapes(it)) }
        fields["pmcid"]?.let { identifiers += canonicalIdentifier(IdentifierType.PMCID, decodeBibEscapes(it)) }
        val date = dateFromFields(
            date = fields["date"]?.let(::decodeBibEscapes),
            year = fields["year"]?.let(::decodeBibEscapes),
            month = fields["month"]?.let(::decodeBibEscapes),
            day = fields["day"]?.let(::decodeBibEscapes),
        )
        val url = fields["url"]?.let(::decodeBibEscapes)?.trim()?.takeIf(String::isNotEmpty)
        if (url != null && !isSafeWebUrl(url)) malformed("BibTeX URL must use HTTP(S)")
        return BibliographyEntry(
            title = title,
            authors = authors,
            identifiers = identifiers,
            abstractText = fields["abstract"]?.let(::decodeBibEscapes)?.takeIf(String::isNotBlank),
            publishedAt = date,
            sourceUrl = url,
            entryType = type,
            citationKey = key,
        )
    }

    private fun readValue(field: String): String {
        if (position >= source.length) malformed("Missing BibTeX value")
        return when (source[position]) {
            '{' -> readBracedValue()
            '"' -> readQuotedValue()
            else -> {
                val start = position
                while (position < source.length && source[position] != ',' && source[position] != '}' &&
                    source[position] != ')' && !source[position].isWhitespace()
                ) {
                    if (source[position] == '#') unsupported("BibTeX value concatenation is not supported")
                    position++
                }
                val value = source.substring(start, position)
                val isMonthName = field == "month" && value.lowercase(Locale.ROOT) in MONTH_MACROS
                if (value.isEmpty() || !(value.matches(Regex("[0-9]{1,4}")) || isMonthName)) {
                    unsupported("Only braced, quoted, numeric, and standard month values are supported")
                }
                value
            }
        }.also {
            if (it.length > MAX_FIELD_CHARACTERS) limit("BibTeX field is too long")
        }
    }

    private fun readBracedValue(): String {
        position++
        val start = position
        var depth = 1
        while (position < source.length) {
            when (source[position]) {
                '\\' -> position = (position + 2).coerceAtMost(source.length)
                '{' -> {
                    depth++
                    if (depth > MAX_BRACE_DEPTH) limit("BibTeX brace nesting is too deep")
                    position++
                }
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val value = source.substring(start, position)
                        position++
                        return value
                    }
                    position++
                }
                else -> position++
            }
        }
        malformed("Unterminated braced BibTeX value")
    }

    private fun readQuotedValue(): String {
        position++
        val start = position
        var braceDepth = 0
        while (position < source.length) {
            when (source[position]) {
                '\\' -> position = (position + 2).coerceAtMost(source.length)
                '{' -> {
                    braceDepth++
                    if (braceDepth > MAX_BRACE_DEPTH) limit("BibTeX brace nesting is too deep")
                    position++
                }
                '}' -> {
                    if (braceDepth == 0) malformed("Unbalanced brace in quoted BibTeX value")
                    braceDepth--
                    position++
                }
                '"' -> if (braceDepth == 0) {
                    val value = source.substring(start, position)
                    position++
                    return value
                } else position++
                else -> position++
            }
        }
        malformed("Unterminated quoted BibTeX value")
    }

    private fun readFieldName(): String {
        val start = position
        while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_' || source[position] == '-')) {
            position++
        }
        return source.substring(start, position).lowercase(Locale.ROOT)
    }

    private fun readToken(): String {
        skipWhitespace()
        val start = position
        while (position < source.length && (source[position].isLetterOrDigit() || source[position] == '_' || source[position] == '-')) {
            position++
        }
        if (position == start) malformed("Missing BibTeX entry type")
        return source.substring(start, position)
    }

    private fun readUntilCommaOrClose(closer: Char): String {
        val start = position
        while (position < source.length && source[position] != ',' && source[position] != closer) position++
        if (position >= source.length) malformed("Unterminated BibTeX citation key")
        return source.substring(start, position)
    }

    private fun nextNonWhitespace(): Char {
        skipWhitespace()
        if (position >= source.length) malformed("Missing BibTeX entry delimiter")
        return source[position]
    }

    private fun skipWhitespace() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    private fun malformed(message: String): Nothing = problem(BibliographyErrorCode.MALFORMED, message)
    private fun unsupported(message: String): Nothing = problem(BibliographyErrorCode.UNSUPPORTED_SYNTAX, message)
    private fun limit(message: String): Nothing = problem(BibliographyErrorCode.LIMIT_EXCEEDED, message)
    private fun problem(code: BibliographyErrorCode, message: String): Nothing = throw ParseProblem(code, message)

    private companion object {
        val MONTH_MACROS = setOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    }
}

internal object BibTeXWriter {
    fun write(entries: List<BibliographyEntry>): String = BoundedBibliographyText().apply {
        val usedKeys = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            if (index > 0) append('\n')
            val baseKey = entry.citationKey ?: stableCitationKey(entry)
            require(entry.citationKey == null || usedKeys.add(baseKey.lowercase(Locale.ROOT))) {
                "Duplicate BibTeX citation key"
            }
            var key = baseKey
            var suffix = 2
            while (entry.citationKey == null && !usedKeys.add(key.lowercase(Locale.ROOT))) {
                key = "$baseKey-$suffix"
                suffix++
            }
            append('@').append(entry.entryType).append('{').append(key).append(",\n")
            appendField("title", entry.title)
            if (entry.authors.isNotEmpty()) {
                append("  author = {")
                entry.authors.forEachIndexed { authorIndex, author ->
                    if (authorIndex > 0) append(" and ")
                    append('{').append(escapeBibText(author)).append('}')
                }
                append("},\n")
            }
            entry.publishedAt?.let { date ->
                appendField("year", date.year.toString())
                date.month?.let { appendField("month", MONTHS[it - 1]) }
                date.day?.let { appendField("day", it.toString()) }
            }
            entry.identifiers.filter { it.type == IdentifierType.DOI }
                .minByOrNull { if (it.value.startsWith(ARXIV_DATACITE_DOI_PREFIX, ignoreCase = true)) 1 else 0 }
                ?.let { appendField("doi", it.value) }
            entry.identifiers.firstOrNull { it.type == IdentifierType.ARXIV }?.let {
                appendField("eprint", it.value)
                appendField("archiveprefix", "arXiv")
            }
            entry.identifiers.firstOrNull { it.type == IdentifierType.PMID }?.let { appendField("pmid", it.value) }
            entry.identifiers.firstOrNull { it.type == IdentifierType.PMCID }?.let { appendField("pmcid", it.value) }
            entry.abstractText?.let { appendField("abstract", it) }
            entry.sourceUrl?.let { appendField("url", it) }
            append("}\n")
        }
    }.toString()

    private fun BoundedBibliographyText.appendField(name: String, value: String) {
        append("  ").append(name).append(" = {").append(escapeBibText(value)).append("},\n")
    }

    private fun stableCitationKey(entry: BibliographyEntry): String {
        val raw = entry.authors.firstOrNull()?.substringBefore(',')?.substringBefore(' ')?.ifBlank { null } ?: "paper"
        val safe = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(24).ifBlank { "paper" }
        val year = entry.publishedAt?.year?.toString().orEmpty()
        return "$safe$year"
    }

    private fun escapeBibText(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\textbackslash{}")
                '{' -> append("\\{")
                '}' -> append("\\}")
                '#' -> append("\\#")
                '$' -> append("\\$")
                '%' -> append("\\%")
                '&' -> append("\\&")
                '_' -> append("\\_")
                '^' -> append("\\textasciicircum{}")
                '~' -> append("\\textasciitilde{}")
                else -> append(char)
            }
        }
    }

    private const val ARXIV_DATACITE_DOI_PREFIX = "10.48550/"
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}

private fun decodeBibEscapes(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    val commands = listOf("\\textbackslash{}" to "\\", "\\textasciicircum{}" to "^", "\\textasciitilde{}" to "~")
    while (index < value.length) {
        val command = commands.firstOrNull { value.startsWith(it.first, index) }
        if (command != null) {
            result.append(command.second)
            index += command.first.length
        } else if (value[index] == '\\' && index + 1 < value.length && value[index + 1] in "{}#$%&_" ) {
            result.append(value[index + 1])
            index += 2
        } else {
            result.append(value[index++])
        }
    }
    return result.toString()
}

private fun decodeBibAuthor(raw: String): String {
    var depth = 0
    var index = 0
    var outerGroup = raw.startsWith('{') && raw.endsWith('}')
    while (index < raw.length && outerGroup) {
        when (raw[index]) {
            '\\' -> index++
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0 && index < raw.lastIndex) outerGroup = false
            }
        }
        index++
    }
    return decodeBibEscapes(if (outerGroup) raw.substring(1, raw.lastIndex) else raw)
}

private fun splitBibAuthors(value: String): List<String> {
    val names = mutableListOf<String>()
    var start = 0
    var depth = 0
    var index = 0
    while (index < value.length) {
        when (value[index]) {
            '\\' -> index += 2
            '{' -> { depth++; index++ }
            '}' -> { depth--; if (depth < 0) throw ParseProblem(BibliographyErrorCode.MALFORMED, "Unbalanced author braces"); index++ }
            else -> {
                if (depth == 0 && index + 3 <= value.length && value.regionMatches(index, "and", 0, 3, ignoreCase = true) &&
                    (index == 0 || value[index - 1].isWhitespace()) &&
                    (index + 3 == value.length || value[index + 3].isWhitespace())
                ) {
                    names += value.substring(start, index).trim().also { if (it.isEmpty()) throw ParseProblem(BibliographyErrorCode.MALFORMED, "Empty BibTeX author") }
                    if (names.size >= MAX_AUTHORS_PER_ENTRY) {
                        throw ParseProblem(BibliographyErrorCode.LIMIT_EXCEEDED, "Too many authors")
                    }
                    index += 3
                    while (index < value.length && value[index].isWhitespace()) index++
                    start = index
                } else index++
            }
        }
    }
    if (depth != 0) throw ParseProblem(BibliographyErrorCode.MALFORMED, "Unbalanced author braces")
    if (value.substring(start).isNotBlank()) names += value.substring(start).trim()
    if (names.size > MAX_AUTHORS_PER_ENTRY) throw ParseProblem(BibliographyErrorCode.LIMIT_EXCEEDED, "Too many authors")
    return names
}
