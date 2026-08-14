package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import dev.paperreader.logic.provider.RemotePaper

@RunWith(AndroidJUnit4::class)
class RoomReadingHistoryAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var history: RoomReadingHistoryRepository
    private val now = Instant.parse("2026-08-14T00:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = RoomLibraryRepository(database, Clock.fixed(now, ZoneOffset.UTC))
        history = RoomReadingHistoryRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun recordsAggregateSessionsAndRemovesAWorkHistory() = runBlocking {
        val workId = library.save(RemotePaper("arxiv", "history-1", "History paper", identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "2501.00001"))))
        history.record(workId, now, Duration.ofSeconds(30))
        history.record(workId, now.plusSeconds(60), Duration.ofSeconds(45))

        val entry = history.history.first().single()
        assertEquals(workId, entry.workId)
        assertEquals("History paper", entry.title)
        assertEquals(now.plusSeconds(60), entry.lastReadAt)
        assertEquals(Duration.ofSeconds(75), entry.totalReadDuration)
        assertEquals(2, entry.sessionCount)
        assertTrue(entry.progression in 0.0..1.0)

        history.remove(workId)
        assertEquals(emptyList<Any>(), history.history.first())
    }

    @Test
    fun negativeReadingDurationIsRejectedBeforeWriting() = runBlocking {
        val workId = WorkId("missing")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { history.record(workId, now, Duration.ofSeconds(-1)) }
        }
        assertEquals(emptyList<Any>(), history.history.first())
    }
}
