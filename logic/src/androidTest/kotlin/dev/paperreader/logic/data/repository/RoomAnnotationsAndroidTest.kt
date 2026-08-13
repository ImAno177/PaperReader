package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.AnnotationSelection
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.RemoveAnnotationResult
import dev.paperreader.logic.domain.repository.SaveAnnotationResult
import dev.paperreader.logic.domain.repository.UpdateAnnotationNoteResult
import dev.paperreader.logic.provider.RemotePaper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAnnotationsAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var annotations: RoomAnnotationRepository
    private val now = Instant.parse("2026-08-13T00:00:00Z")
    private val documentSha = "a".repeat(64)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        library = RoomLibraryRepository(database, clock)
        annotations = RoomAnnotationRepository(database, clock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactDocumentAnnotationPersistsUpdatesAndDeletes() = runBlocking {
        val workId = preparedWork()
        val first = annotations.save(workId, selection(), "Review this claim")
        assertTrue(first is SaveAnnotationResult.Saved && first.created)
        assertEquals("Review this claim", annotations.observe(workId, documentSha).first().single().note)

        val updated = annotations.save(workId, selection(), "Compare with Table 2")
        assertTrue(updated is SaveAnnotationResult.Saved && !updated.created)
        val annotation = annotations.observe(workId, documentSha).first().single()
        assertEquals("Compare with Table 2", annotation.note)

        assertTrue(annotations.updateNote(annotation.id, "  ") is UpdateAnnotationNoteResult.Updated)
        assertEquals(null, annotations.observe(workId, documentSha).first().single().note)
        assertEquals(RemoveAnnotationResult.Removed, annotations.remove(annotation.id))
        assertTrue(annotations.observe(workId, documentSha).first().isEmpty())
    }

    @Test
    fun staleAndOverlappingAnchorsFailClosed() = runBlocking {
        val workId = preparedWork()
        assertTrue(annotations.save(workId, selection(), null) is SaveAnnotationResult.Saved)
        assertEquals(
            SaveAnnotationResult.OverlapsExisting,
            annotations.save(
                workId,
                selection().copy(startOffset = 8, endOffset = 18, quoteExact = "erability "),
                null,
            ),
        )
        assertEquals(
            SaveAnnotationResult.DocumentNotCurrent,
            annotations.save(workId, selection().copy(documentSha256 = "b".repeat(64)), null),
        )
        assertEquals(
            UpdateAnnotationNoteResult.InvalidNote,
            annotations.updateNote(
                annotations.observe(workId, documentSha).first().single().id,
                "n".repeat(4_001),
            ),
        )
    }

    private suspend fun preparedWork(): WorkId {
        val workId = library.save(
            RemotePaper(
                providerId = "arxiv",
                providerRecordId = "2501.04510v2",
                title = "Annotation test paper",
                identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "2501.04510")),
            ),
        )
        library.updateReadingState(
            ReadingState(
                workId = workId,
                locator = ReadingLocator(documentSha256 = documentSha),
                updatedAt = now,
            ),
        )
        return workId
    }

    private fun selection() = AnnotationSelection(
        documentSha256 = documentSha,
        blockId = "prx-b00012",
        startOffset = 4,
        endOffset = 17,
        quotePrefix = "The ",
        quoteExact = "vulnerability",
        quoteSuffix = " model",
    )
}
