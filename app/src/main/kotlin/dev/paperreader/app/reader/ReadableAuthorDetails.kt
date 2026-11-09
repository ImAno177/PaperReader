package dev.paperreader.app.reader

private val HTML_TAG_NAME = Regex("""^<\s*(/?)\s*([A-Za-z][A-Za-z0-9:-]*)\b""")
private val HTML_CLASS_ATTRIBUTE = Regex(
    """\sclass\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    RegexOption.IGNORE_CASE,
)
private val AUTHOR_DETAIL_CLASSES = setOf(
    "ltx_author_notes",
    "paperreader-author-details",
    "ltx_note_frontmatter",
)
private val VOID_HTML_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)
private const val AUTHOR_DETAILS_COLLAPSE_THRESHOLD = 160

internal fun collapseLongAuthorDetails(html: String, summaryLabel: String): String {
    val summary = escapeHtmlText(summaryLabel)
    val output = StringBuilder(html.length)
    var copiedThrough = 0
    var searchFrom = 0
    while (searchFrom < html.length) {
        val openStart = html.indexOf('<', searchFrom)
        if (openStart < 0) break
        val openEnd = findHtmlTagEnd(html, openStart) ?: break
        val openTag = html.substring(openStart, openEnd + 1)
        val tagName = HTML_TAG_NAME.find(openTag)
            ?.takeIf { it.groupValues[1].isEmpty() }
            ?.groupValues
            ?.getOrNull(2)
            ?.lowercase()
        val classes = HTML_CLASS_ATTRIBUTE.find(openTag)?.let { match ->
            (1..3).mapNotNull { index -> match.groups[index]?.value }
                .joinToString(" ")
                .split(Regex("\\s+"))
        }.orEmpty()
        if (
            tagName == null || tagName in VOID_HTML_ELEMENTS ||
            classes.none(AUTHOR_DETAIL_CLASSES::contains) || openTag.trimEnd().endsWith("/>")
        ) {
            searchFrom = openEnd + 1
            continue
        }
        val closeEnd = findMatchingHtmlElementEnd(html, openEnd + 1, tagName)
        if (closeEnd == null || closeEnd - openStart + 1 < AUTHOR_DETAILS_COLLAPSE_THRESHOLD) {
            searchFrom = openEnd + 1
            continue
        }
        output.append(html, copiedThrough, openStart)
            .append("<details class=\"paperreader-author-notes\"><summary>")
            .append(summary)
            .append("</summary>")
            .append(html, openStart, closeEnd + 1)
            .append("</details>")
        copiedThrough = closeEnd + 1
        searchFrom = copiedThrough
    }
    if (copiedThrough == 0) return html
    return output.append(html, copiedThrough, html.length).toString()
}

private fun findMatchingHtmlElementEnd(html: String, contentStart: Int, tagName: String): Int? {
    var depth = 1
    var searchFrom = contentStart
    while (searchFrom < html.length) {
        val tagStart = html.indexOf('<', searchFrom)
        if (tagStart < 0) return null
        val tagEnd = findHtmlTagEnd(html, tagStart) ?: return null
        val tag = html.substring(tagStart, tagEnd + 1)
        val match = HTML_TAG_NAME.find(tag)?.groupValues ?: emptyList()
        if (match.getOrNull(2)?.equals(tagName, ignoreCase = true) == true) {
            if (match[1].isNotEmpty()) {
                if (--depth == 0) return tagEnd
            }
            else if (!tag.trimEnd().endsWith("/>")) depth++
        }
        searchFrom = tagEnd + 1
    }
    return null
}

private fun findHtmlTagEnd(html: String, tagStart: Int): Int? {
    var quote: Char? = null
    for (index in tagStart + 1 until html.length) {
        val character = html[index]
        if (quote != null) { if (character == quote) quote = null }
        else when (character) { '\'', '"' -> quote = character; '>' -> return index }
    }
    return null
}

private fun escapeHtmlText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
