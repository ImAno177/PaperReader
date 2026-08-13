package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.task.TaskCoordinator
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryRemovalAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: RoomLibraryRepository
    private val now = Instant.parse("2026-08-11T12:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLibraryRepository(database, Clock.fixed(now, ZoneOffset.UTC))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun removalIsTransactionalAndCascadesDependentRows() = runBlocking {
        val workId = repository.save(remote("record-1"))
        repository.updateReadingState(ReadingState(workId = workId, updatedAt = now))
        database.readingHistoryDao().record(workId.value, now.toEpochMilli(), 60_000)
        val tasks = TaskCoordinator(RoomTaskRepository(database), Clock.fixed(now, ZoneOffset.UTC))
        val task = tasks.enqueue(TaskKind.DOWNLOAD, workId, "https://example.org/paper.pdf")
        tasks.transition(task.id, TaskState.RUNNING)
        tasks.transition(task.id, TaskState.SUCCEEDED)

        assertEquals(RemovePaperResult.Removed, repository.remove(workId))

        assertNull(repository.get(workId))
        assertTrue(database.readingHistoryDao().observeHistory().first().isEmpty())
        assertNull(RoomTaskRepository(database).get(task.id))
    }

    @Test
    fun activeTaskPreventsRemoval() = runBlocking {
        val workId = repository.save(remote("record-active"))
        val tasks = TaskCoordinator(RoomTaskRepository(database), Clock.fixed(now, ZoneOffset.UTC))
        tasks.enqueue(TaskKind.DOWNLOAD, workId, "https://example.org/active.pdf")

        assertEquals(RemovePaperResult.HasActiveTasks, repository.remove(workId))
        assertNotNull(repository.get(workId))
    }

    @Test
    fun localArtifactPreventsMetadataFromOrphaningItsFile() = runBlocking {
        val workId = repository.save(remote("record-local"))
        val manifestationId = repository.get(workId)!!.manifestations.single().id.value
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO files (
                id, manifestationId, localPath, sha256, byteLength, mimeType,
                extractionStatus, extractionManifestPath, updatedAtEpochMillis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "file-1",
                manifestationId,
                "/private/paper.pdf",
                "a".repeat(64),
                42L,
                "application/pdf",
                "NOT_STARTED",
                null,
                now.toEpochMilli(),
            ),
        )

        assertEquals(RemovePaperResult.HasLocalArtifacts, repository.remove(workId))
        assertNotNull(repository.get(workId))
    }

    @Test
    fun localArtifactIsIncludedInReactiveLibraryProjection() = runBlocking {
        val workId = repository.save(remote("record-projection"))
        val manifestationId = repository.get(workId)!!.manifestations.single().id
        RoomLocalFileRepository(database).upsert(
            LocalPaperArtifact(
                id = "file-projection",
                manifestationId = manifestationId,
                storagePath = "papers/fixture/paper.pdf",
                sha256 = "b".repeat(64),
                byteLength = 128L,
                mimeType = "application/pdf",
                updatedAt = now,
            ),
        )

        val projected = repository.library.first().single()

        assertEquals("b".repeat(64), projected.localArtifacts[manifestationId]?.sha256)
        assertEquals(128L, projected.localArtifacts[manifestationId]?.byteLength)
    }

    private fun remote(recordId: String) = RemotePaper(
        providerId = "fixture",
        providerRecordId = recordId,
        title = "Saved paper $recordId",
        identifiers = setOf(PaperIdentifier(IdentifierType.DOI, "10.1000/$recordId")),
        manifestations = listOf(
            RemoteManifestation(
                type = ManifestationType.VERSION_OF_RECORD,
                pdfUrl = "https://example.org/$recordId.pdf",
            ),
        ),
    )
}
