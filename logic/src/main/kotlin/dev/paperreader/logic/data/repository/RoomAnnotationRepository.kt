package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
import dev.paperreader.logic.data.AnnotationEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.MAX_ANNOTATION_NOTE_LENGTH
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.annotationId
import dev.paperreader.logic.domain.canonicalDocumentSha256
import dev.paperreader.logic.domain.repository.AnnotationRepository
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomAnnotationRepository(
    private val database: LibraryDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : AnnotationRepository {
    private val dao = database.libraryDao()

    override fun observe(workId: WorkId, documentSha256: String): Flow<List<Annotation>> =
        dao.observeAnnotations(workId.value, canonicalDocumentSha256(documentSha256))
            .map { rows -> rows.map(AnnotationEntity::toDomain) }

    override suspend fun save(
        workId: WorkId,
        selection: AnnotationSelection,
        note: String?,
    ): SaveAnnotationResult = database.withTransaction {
        if (dao.getWork(workId.value) == null) return@withTransaction SaveAnnotationResult.PaperNotFound
        val readingState = dao.getReadingState(workId.value)
        if (!readingState?.documentSha256.equals(selection.documentSha256, ignoreCase = true)) {
            return@withTransaction SaveAnnotationResult.DocumentNotCurrent
        }
        val id = annotationId(workId, selection)
        val existing = dao.getAnnotation(id)
        val overlaps = dao.getOverlappingAnnotations(
            workId = workId.value,
            documentSha256 = selection.documentSha256,
            blockId = selection.blockId,
            startOffset = selection.startOffset,
            endOffset = selection.endOffset,
        )
        if (overlaps.any { it.id != id }) return@withTransaction SaveAnnotationResult.OverlapsExisting
        val normalizedNote = try {
            normalizedNote(note)
        } catch (_: IllegalArgumentException) {
            return@withTransaction SaveAnnotationResult.InvalidNote
        }
        val now = clock.instant()
        val entity = AnnotationEntity(
            id = id,
            workId = workId.value,
            documentSha256 = selection.documentSha256,
            blockId = selection.blockId,
            startOffset = selection.startOffset,
            endOffset = selection.endOffset,
            quotePrefix = selection.quotePrefix,
            quoteExact = selection.quoteExact,
            quoteSuffix = selection.quoteSuffix,
            pageIndex = selection.pageIndex,
            note = normalizedNote,
            color = HIGHLIGHT_COLOR,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        dao.upsertAnnotation(entity)
        SaveAnnotationResult.Saved(entity.toDomain(), created = existing == null)
    }

    override suspend fun updateNote(id: String, note: String?): UpdateAnnotationNoteResult =
        database.withTransaction {
            val existing = dao.getAnnotation(id) ?: return@withTransaction UpdateAnnotationNoteResult.NotFound
            val normalized = try {
                normalizedNote(note)
            } catch (_: IllegalArgumentException) {
                return@withTransaction UpdateAnnotationNoteResult.InvalidNote
            }
            val updated = existing.copy(note = normalized, updatedAtEpochMillis = clock.instant().toEpochMilli())
            dao.upsertAnnotation(updated)
            UpdateAnnotationNoteResult.Updated(updated.toDomain())
        }

    override suspend fun remove(id: String): RemoveAnnotationResult =
        if (dao.deleteAnnotation(id) == 1) RemoveAnnotationResult.Removed else RemoveAnnotationResult.NotFound

    private fun normalizedNote(note: String?): String? = note
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.also { require(it.length <= MAX_ANNOTATION_NOTE_LENGTH) { "Annotation note is too long" } }

    private companion object {
        const val HIGHLIGHT_COLOR = "highlight"
    }
}

private fun AnnotationEntity.toDomain() = Annotation(
    id = id,
    workId = WorkId(workId),
    documentSha256 = documentSha256.lowercase(Locale.ROOT),
    blockId = blockId,
    startOffset = startOffset,
    endOffset = endOffset,
    quotePrefix = quotePrefix,
    quoteExact = quoteExact,
    quoteSuffix = quoteSuffix,
    pageIndex = pageIndex,
    note = note,
    color = color,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)
