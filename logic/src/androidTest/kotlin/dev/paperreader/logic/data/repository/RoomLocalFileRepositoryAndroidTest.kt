package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLocalFileRepositoryAndroidTest {
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun upsertReadsCanonicalFileMetadataAndRemoveIsIdempotent() = runBlocking {
        val repository = RoomLocalFileRepository(database)
        val manifestationId = ManifestationId("manifestation")
        val artifact = LocalPaperArtifact(
            id = "file-1",
            manifestationId = manifestationId,
            storagePath = "papers/file.pdf",
            sha256 = "A".repeat(64),
            byteLength = 42,
            mimeType = "application/pdf",
            updatedAt = Instant.EPOCH,
        )

        repository.upsert(artifact)

        val loaded = repository.get(manifestationId)
        assertEquals("a".repeat(64), loaded?.sha256)
        assertEquals("papers/file.pdf", loaded?.storagePath)
        assertEquals(42L, loaded?.byteLength)

        repository.remove(manifestationId)
        repository.remove(manifestationId)
        assertNull(repository.get(manifestationId))
    }
}
