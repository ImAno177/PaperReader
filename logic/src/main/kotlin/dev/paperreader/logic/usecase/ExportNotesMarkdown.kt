package dev.paperreader.logic.usecase

import dev.paperreader.logic.interop.AnnotatedPaperSnapshot
import dev.paperreader.logic.interop.AnnotationMarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportNotesMarkdown(private val snapshot: suspend () -> List<AnnotatedPaperSnapshot>) {
    suspend operator fun invoke(): String = withContext(Dispatchers.Default) {
        AnnotationMarkdownRenderer.render(snapshot())
    }
}
