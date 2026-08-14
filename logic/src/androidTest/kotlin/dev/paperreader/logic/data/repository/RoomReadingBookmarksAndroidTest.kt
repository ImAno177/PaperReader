package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingBookmarkId
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import dev.paperreader.logic.provider.RemoteManifestation
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
class RoomReadingBookmarksAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var bookmarks: RoomReadingBookmarkRepository
    private val now = Instant.parse("2026-08-12T00:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        library = RoomLibraryRepository(database, clock)
        bookmarks = RoomReadingBookmarkRepository(database, clock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactDocumentBookmarksToggleInPageOrderAndRejectStaleIdentity() = runBlocking {
        val workId = library.save(remotePaper())
        val manifestationId = library.get(workId)!!.manifestations.single().id
        RoomLocalFileRepository(database).upsert(localArtifact(manifestationId, "a".repeat(64)))

        assertTrue(
            bookmarks.toggle(workId, manifestationId, "A".repeat(64), 8) is
                ToggleReadingBookmarkResult.Added,
        )
        assertTrue(
            bookmarks.toggle(workId, manifestationId, "a".repeat(64), 2) is
                ToggleReadingBookmarkResult.Added,
        )
        assertEquals(
            listOf(2, 8),
            bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().map { it.pageIndex },
        )

        assertEquals(
            ToggleReadingBookmarkResult.DocumentNotCurrent,
            bookmarks.toggle(workId, manifestationId, "b".repeat(64), 2),
        )
        assertEquals(
            ToggleReadingBookmarkResult.ManifestationNotFound,
            bookmarks.toggle(workId, ManifestationId("other"), "a".repeat(64), 2),
        )

        assertEquals(
            ToggleReadingBookmarkResult.Removed,
            bookmarks.toggle(workId, manifestationId, "a".repeat(64), 2),
        )
        assertEquals(
            listOf(8),
            bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().map { it.pageIndex },
        )

        val removed = bookmarks.remove(ReadingBookmarkId("f".repeat(64)))
        assertEquals(dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult.NotFound, removed)
        val bookmark = bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().single()
        assertEquals(dev.paperreader.logic.domain.repository.RemoveReadingBookmarkResult.Removed, bookmarks.remove(bookmark.id))
        assertTrue(bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().isEmpty())
    }

    @Test
    fun blockedPaperRemovalRetainsBookmarksAndSuccessfulRemovalDeletesThem() = runBlocking {
        val workId = library.save(remotePaper())
        val manifestationId = library.get(workId)!!.manifestations.single().id
        val localFiles = RoomLocalFileRepository(database)
        localFiles.upsert(localArtifact(manifestationId, "a".repeat(64)))
        bookmarks.toggle(workId, manifestationId, "a".repeat(64), 0)

        assertEquals(RemovePaperResult.HasLocalArtifacts, library.remove(workId))
        assertEquals(1, bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().size)

        localFiles.remove(manifestationId)
        assertEquals(RemovePaperResult.Removed, library.remove(workId))
        assertTrue(bookmarks.observe(workId, manifestationId, "a".repeat(64)).first().isEmpty())
    }

    @Test
    fun bookmarkRequiresReadableLocalPathAndAcceptsLegacyUppercaseHash() = runBlocking {
        val workId = library.save(remotePaper())
        val manifestationId = library.get(workId)!!.manifestations.single().id
        val uppercaseSha = "A".repeat(64)
        database.libraryDao().upsertFile(
            FileEntity(
                id = "metadata-only",
                manifestationId = manifestationId.value,
                localPath = null,
                sha256 = uppercaseSha,
                byteLength = 42,
                mimeType = "application/pdf",
                extractionStatus = "NOT_STARTED",
                extractionManifestPath = null,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )

        assertEquals(
            ToggleReadingBookmarkResult.DocumentNotCurrent,
            bookmarks.toggle(workId, manifestationId, uppercaseSha, 0),
        )

        database.libraryDao().upsertFile(
            FileEntity(
                id = "metadata-only",
                manifestationId = manifestationId.value,
                localPath = "papers/${manifestationId.value}.pdf",
                sha256 = uppercaseSha,
                byteLength = 42,
                mimeType = "application/pdf",
                extractionStatus = "NOT_STARTED",
                extractionManifestPath = null,
                updatedAtEpochMillis = now.toEpochMilli(),
            ),
        )

        assertTrue(
            bookmarks.toggle(workId, manifestationId, uppercaseSha, 0) is
                ToggleReadingBookmarkResult.Added,
        )
    }

    private fun localArtifact(manifestationId: ManifestationId, sha256: String) = LocalPaperArtifact(
        id = "file-${manifestationId.value}",
        manifestationId = manifestationId,
        storagePath = "papers/${manifestationId.value}.pdf",
        sha256 = sha256,
        byteLength = 42,
        mimeType = "application/pdf",
        updatedAt = now,
    )

    private fun remotePaper() = RemotePaper(
        providerId = "fixture",
        providerRecordId = "bookmark-record",
        title = "Bookmark test paper",
        identifiers = setOf(PaperIdentifier(IdentifierType.DOI, "10.1000/bookmark-test")),
        manifestations = listOf(
            RemoteManifestation(
                type = ManifestationType.VERSION_OF_RECORD,
                pdfUrl = "https://example.org/bookmark.pdf",
            ),
        ),
    )
}
