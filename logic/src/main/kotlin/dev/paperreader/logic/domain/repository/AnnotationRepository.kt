package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.WorkId
import kotlinx.coroutines.flow.Flow

sealed interface SaveAnnotationResult {
    data class Saved(val annotation: Annotation, val created: Boolean) : SaveAnnotationResult
    data object PaperNotFound : SaveAnnotationResult
    data object DocumentNotCurrent : SaveAnnotationResult
    data object OverlapsExisting : SaveAnnotationResult
    data object InvalidNote : SaveAnnotationResult
}

sealed interface UpdateAnnotationNoteResult {
    data class Updated(val annotation: Annotation) : UpdateAnnotationNoteResult
    data object NotFound : UpdateAnnotationNoteResult
    data object InvalidNote : UpdateAnnotationNoteResult
}

sealed interface RemoveAnnotationResult {
    data object Removed : RemoveAnnotationResult
    data object NotFound : RemoveAnnotationResult
}

interface AnnotationRepository {
    fun observe(workId: WorkId, documentSha256: String): Flow<List<Annotation>>

    suspend fun save(
        workId: WorkId,
        selection: AnnotationSelection,
        note: String?,
    ): SaveAnnotationResult

    suspend fun updateNote(id: String, note: String?): UpdateAnnotationNoteResult

    suspend fun remove(id: String): RemoveAnnotationResult
}
