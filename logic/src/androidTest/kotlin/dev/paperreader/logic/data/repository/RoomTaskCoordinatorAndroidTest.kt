package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.task.TaskCoordinator
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTaskCoordinatorAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: RoomTaskRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTaskRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun staleRunningSnapshotCannotOverwriteNewerProgress() = runBlocking {
        val coordinator = TaskCoordinator(repository, FIXED_CLOCK)
        val queued = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("work-1"), "manifestation-1")
        val running = coordinator.transition(queued.id, TaskState.RUNNING)
        val higher = running.copy(progress = 0.8)
        val staleLower = running.copy(progress = 0.4)

        assertTrue(repository.updateIfCurrent(higher, running))
        assertFalse(repository.updateIfCurrent(staleLower, running))
        assertEquals(0.8, repository.get(queued.id)?.progress ?: -1.0, 0.0)
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-12T00:00:00Z"),
            ZoneOffset.UTC,
        )
    }
}
