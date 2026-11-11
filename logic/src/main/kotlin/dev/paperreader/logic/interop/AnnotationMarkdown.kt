package dev.paperreader.logic.interop

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.MAX_ANNOTATION_CONTEXT_LENGTH
import dev.paperreader.logic.domain.MAX_ANNOTATION_NOTE_LENGTH
import dev.paperreader.logic.domain.MAX_ANNOTATION_QUOTE_LENGTH

data class AnnotatedPaperSnapshot(
    val workId: WorkId,
    val title: String,
    val identifiers: Set<PaperIdentifier>,
    val annotations: List<Annotation>,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_FIELD_CHARACTERS)
        require(identifiers.all { it.type != IdentifierType.PROVIDER })
        require(identifiers.all { it.value.length <= MAX_FIELD_CHARACTERS })
        require(identifiers.all { IdentifierNormalizer.canonical(it) == it })
        require(annotations.all { it.workId == workId })
        require(annotations.all {
            it.blockId.length <= 160 &&
                it.quotePrefix.length <= MAX_ANNOTATION_CONTEXT_LENGTH &&
                it.quoteExact.length <= MAX_ANNOTATION_QUOTE_LENGTH &&
                it.quoteSuffix.length <= MAX_ANNOTATION_CONTEXT_LENGTH &&
                (it.note == null || it.note.length <= MAX_ANNOTATION_NOTE_LENGTH)
        })
    }
}

object AnnotationMarkdownRenderer {
    fun render(papers: List<AnnotatedPaperSnapshot>): String {
        require(papers.size <= MAX_RECORDS) { "Too many papers to export" }
        val annotationCount = papers.sumOf { it.annotations.size.toLong() }
        require(annotationCount <= MAX_MARKDOWN_ANNOTATIONS) { "Too many annotations to export" }
        return BoundedMarkdownBuilder().apply {
            papers.forEachIndexed { paperIndex, paper ->
                if (paperIndex > 0) append("\n---\n\n")
                append("# Paper\n\n")
                appendBlock(paper.title)
                append("\n\n")
                if (paper.identifiers.isNotEmpty()) {
                    append("Identifiers\n\n")
                    paper.identifiers.sortedWith(compareBy({ it.type.name }, { it.value }))
                        .forEach { append("- ").append(it.type.name).append(": ").appendInlineCode(it.value).append('\n') }
                    append('\n')
                }
                append("## Notes and highlights\n")
                val annotations = paper.annotations.sortedWith(
                    compareBy<Annotation>({ it.documentSha256 }, { it.pageIndex ?: Int.MAX_VALUE }, { it.blockId }, { it.startOffset }, { it.endOffset }),
                )
                annotations.forEach { annotation ->
                    append("\n### ")
                    append(if (annotation.note.isNullOrBlank()) "Highlight" else "Note")
                    append("\n\n")
                    append("Quote\n\n")
                    appendBlock(annotation.quoteExact)
                    append("\n\n")
                    annotation.note?.takeIf(String::isNotBlank)?.let { note ->
                        append("Note text\n\n")
                        appendBlock(note)
                        append("\n\n")
                    }
                    append("Document SHA-256: ").appendInlineCode(annotation.documentSha256).append("  \n")
                    append("Anchor: ").appendInlineCode(annotation.blockId)
                    append(" offsets ").appendInlineCode("${annotation.startOffset}..${annotation.endOffset}").append("  \n")
                    append("Page: ").appendInlineCode(annotation.pageIndex?.plus(1)?.toString() ?: "unknown").append("  \n")
                    append("Quote context\n\n")
                    appendBlock(annotation.quotePrefix + "⟦" + annotation.quoteExact + "⟧" + annotation.quoteSuffix)
                    append("\n")
                }
            }
            if (papers.isEmpty()) append("# Notes and highlights\n\nNo annotated papers.")
        }.toString()
    }

    private class BoundedMarkdownBuilder {
        private val value = StringBuilder()
        fun append(text: String): BoundedMarkdownBuilder {
            require(text.length <= MAX_MARKDOWN_CHARACTERS - value.length) { "Markdown export exceeds the size limit" }
            value.append(text)
            return this
        }

        fun append(char: Char): BoundedMarkdownBuilder {
            require(value.length < MAX_MARKDOWN_CHARACTERS) { "Markdown export exceeds the size limit" }
            value.append(char)
            return this
        }

        fun appendBlock(text: String) {
            require(text.length <= MAX_FIELD_CHARACTERS) { "Annotation text exceeds the size limit" }
            val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
            append(fence).append("text\n").append(text).append(if (text.endsWith('\n')) "" else "\n").append(fence)
        }

        fun appendInlineCode(text: String): BoundedMarkdownBuilder {
            val fence = "`".repeat(maxOf(1, longestBacktickRun(text) + 1))
            append(fence).append(text).append(fence)
            return this
        }

        override fun toString(): String = value.toString()
    }

    private fun longestBacktickRun(value: String): Int {
        var longest = 0
        var current = 0
        value.forEach { char ->
            if (char == '`') {
                current++
                longest = maxOf(longest, current)
            } else current = 0
        }
        return longest
    }

    private const val MAX_MARKDOWN_ANNOTATIONS = 100_000L
    private const val MAX_MARKDOWN_CHARACTERS = 4 * 1024 * 1024
}
