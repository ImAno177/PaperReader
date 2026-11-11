package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.backup.LibraryArchiveException
import dev.paperreader.logic.data.AnnotationEntity
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.ReadingBookmarkEntity
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.readingBookmarkId
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryArchiveAndroidTest {
    private val databases = mutableListOf<LibraryDatabase>()
    private val roots = mutableListOf<Path>()

    @After
    fun cleanUp() {
        databases.forEach(LibraryDatabase::close)
        roots.forEach { root ->
            if (Files.exists(root)) Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun fullBackupRecoversPreviewAndRestoresExactPdfAnchorsIdempotently() = runBlocking {
        val source = fixture()
        val bytes = archive(source)
        val target = fixture(populate = false)
        val preview = target.repository.prepareRestore(bytes.inputStream())
        assertEquals(1, preview.documents)
        assertEquals(1, preview.metadata.newWorks)
        assertTrue(target.database.libraryDao().getAllWorks().isEmpty())
        val recovered = repository(target.database, target.root).recoverPendingRestore()!!
        assertEquals(preview.token, recovered.token)
        val report = target.repository.restore(recovered.token)
        assertEquals(1, report.restoredDocuments)
        val dao = target.database.libraryDao()
        val stored = dao.getStoredFiles().single()
        assertEquals(source.sha, stored.sha256)
        assertArrayEquals(PDF, Files.readAllBytes(target.root.resolve(stored.localPath!!)))
        assertFalse(Path.of(stored.localPath).isAbsolute)
        assertEquals(2024, dao.getAllWorks().single().publicationYear)
        assertNull(dao.getAllWorks().single().publishedDateEpochDay)
        assertEquals(source.sha, dao.getAllAnnotations().single().documentSha256)
        assertEquals(stored.manifestationId, target.database.readingBookmarkDao().getAll().single().manifestationId)
        assertNull(target.repository.recoverPendingRestore())

        val repeated = target.repository.prepareRestore(bytes.inputStream())
        val second = target.repository.restore(repeated.token)
        assertEquals(0, second.restoredDocuments)
        assertEquals(1, second.skippedDocuments)
        assertEquals(stored, dao.getStoredFiles().single())
        assertEquals(1, dao.getAllWorks().size)
        assertEquals(1, Files.list(target.root.resolve("papers")).use { it.count() }.toInt())

        Files.delete(target.root.resolve(stored.localPath))
        val missing = target.repository.prepareRestore(bytes.inputStream())
        assertEquals(0, missing.conflictingDocuments)
        val repaired = target.repository.restore(missing.token)
        assertEquals(1, repaired.restoredDocuments)
        assertEquals(0, repaired.skippedDocuments)
        val recoveredFile = dao.getStoredFiles().single()
        assertEquals(stored.id, recoveredFile.id)
        assertEquals(source.sha, recoveredFile.sha256)
        assertArrayEquals(PDF, Files.readAllBytes(target.root.resolve(recoveredFile.localPath!!)))
        assertEquals(source.sha, dao.getAllAnnotations().single().documentSha256)
    }

    @Test
    fun restoringDifferentPdfPreservesExistingFileAndItsExactDocumentBinding() = runBlocking {
        val source = fixture()
        val target = fixture()
        val dao = target.database.libraryDao()
        val existing = dao.getStoredFiles().single()
        val different = "%PDF-1.4\nA different original\n%%EOF".toByteArray()
        Files.write(target.root.resolve(existing.localPath!!), different)
        val expected = existing.copy(sha256 = hash(different), byteLength = different.size.toLong())
        dao.upsertFile(expected)
        val preview = target.repository.prepareRestore(archive(source).inputStream())
        assertEquals(1, preview.conflictingDocuments)
        val report = target.repository.restore(preview.token)
        assertEquals(0, report.restoredDocuments)
        assertEquals(1, report.skippedDocuments)
        assertEquals(expected, dao.getStoredFiles().single())
        assertArrayEquals(different, Files.readAllBytes(target.root.resolve(existing.localPath)))
        assertEquals(source.sha, dao.getAllAnnotations().single().documentSha256)

        Files.delete(target.root.resolve(existing.localPath))
        val missing = target.repository.prepareRestore(archive(source).inputStream())
        assertEquals(0, missing.conflictingDocuments)
        assertEquals(1, target.repository.restore(missing.token).restoredDocuments)
        val recoveredFile = dao.getStoredFiles().single()
        assertEquals(source.sha, recoveredFile.sha256)
        assertArrayEquals(PDF, Files.readAllBytes(target.root.resolve(recoveredFile.localPath!!)))
    }

    @Test
    fun corruptLocalPdfIsRejectedWithoutChangingLibraryAndCancelledInputLeavesNoPendingRestore() = runBlocking {
        val source = fixture()
        val originalWorks = source.database.libraryDao().getAllWorks()
        val stored = source.database.libraryDao().getStoredFiles().single()
        Files.write(source.root.resolve(stored.localPath!!), PDF.copyOf().also { it[it.lastIndex - 2] = 65 })
        try {
            source.repository.prepareExport()
            fail("A changed PDF must fail its exact hash check")
        } catch (_: LibraryArchiveException) { }
        assertEquals(originalWorks, source.database.libraryDao().getAllWorks())
        val target = fixture(populate = false)
        val cancelled = object : java.io.InputStream() {
            override fun read(): Int = throw CancellationException("User cancelled")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = throw CancellationException("User cancelled")
        }
        try {
            target.repository.prepareRestore(cancelled)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(target.database.libraryDao().getAllWorks().isEmpty())
        assertNull(target.repository.recoverPendingRestore())
        assertEquals(0, Files.list(target.root.resolve("sessions")).use { it.count() }.toInt())
    }

    @Test
    fun missingLocalPdfIsSkippedWhileMetadataStillExports() = runBlocking {
        val source = fixture()
        val stored = source.database.libraryDao().getStoredFiles().single()
        Files.delete(source.root.resolve(stored.localPath!!))

        val prepared = source.repository.prepareExport()

        assertEquals(0, prepared.documents)
        assertEquals(1, prepared.skippedDocuments)
        assertEquals(source.database.libraryDao().getAllWorks().size, prepared.summary.works)
        source.repository.discard(prepared.token)
    }

    @Test
    fun recoveryCleansOnlyUnreferencedArchiveArtifactsAfterInterruptedProcess() = runBlocking {
        val source = fixture()
        val stored = source.database.libraryDao().getStoredFiles().single()
        val kept = source.root.resolve("papers/restored-${UUID.randomUUID()}.pdf")
        Files.move(source.root.resolve(stored.localPath!!), kept)
        source.database.libraryDao().upsertFile(stored.copy(localPath = source.root.relativize(kept).toString()))
        val orphan = source.root.resolve("papers/restored-${UUID.randomUUID()}.pdf")
        Files.write(orphan, PDF)
        val unrelated = source.root.resolve("papers/unrelated.pdf")
        Files.write(unrelated, PDF)
        val unpack = source.root.resolve("sessions/unpack-${UUID.randomUUID()}")
        Files.createDirectories(unpack.resolve("documents"))
        Files.write(unpack.resolve("documents/staged.pdf"), PDF)

        repository(source.database, source.root).reconcileStaging()

        assertTrue(Files.exists(kept))
        assertTrue(Files.exists(unrelated))
        assertFalse(Files.exists(orphan))
        assertFalse(Files.exists(unpack))
        assertNotNull(source.database.libraryDao().getFileByLocalPath(source.root.relativize(kept).toString()))
    }

    @Test
    fun cancellationClosesBlockedDocumentProviderBeforeReleasingSession() = runBlocking {
        val target = fixture(populate = false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val blocked = object : java.io.InputStream() {
            override fun read(): Int {
                entered.countDown()
                closed.await()
                throw java.io.IOException("Closed")
            }
            override fun close() { closed.countDown() }
        }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            target.repository.prepareRestore(blocked)
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            kotlinx.coroutines.withTimeout(5000) { job.join() }
            assertEquals(0L, closed.count)
            assertTrue(target.database.libraryDao().getAllWorks().isEmpty())
            assertNull(target.repository.recoverPendingRestore())
        } finally {
            blocked.close()
            job.cancel()
        }
    }

    private suspend fun archive(fixture: Fixture): ByteArray {
        val prepared = fixture.repository.prepareExport()
        return try {
            ByteArrayOutputStream().also { fixture.repository.writeExport(prepared.token, it) }.toByteArray()
        } finally {
            fixture.repository.discard(prepared.token)
        }
    }

    private suspend fun fixture(populate: Boolean = true): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build().also(databases::add)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "archive-test-").also(roots::add)
        val sha = hash(PDF)
        if (populate) {
            val workId = RoomLibraryRepository(database).save(RemotePaper(
                "fixture", "archive-paper", "A paper: complete title",
                identifiers = setOf(PaperIdentifier(IdentifierType.DOI, "10.1234/archive")),
                publicationYear = 2024,
                manifestations = listOf(RemoteManifestation(ManifestationType.PREPRINT, version = "v1")),
            ))
            val dao = database.libraryDao()
            val manifestation = dao.getAllManifestations().single()
            Files.createDirectories(root.resolve("papers"))
            Files.write(root.resolve("papers/original.pdf"), PDF)
            dao.upsertFile(FileEntity("original", manifestation.id, "papers/original.pdf", sha,
                PDF.size.toLong(), "application/pdf", "NOT_STARTED", null, 1))
            dao.upsertAnnotation(AnnotationEntity("note", workId.value, sha, "pdf-page-0", 0, 4,
                "", "Text", "", 0, "Exact original", null, 1, 1))
            database.readingBookmarkDao().upsertAll(listOf(ReadingBookmarkEntity(
                workId.value, manifestation.id, sha, 0,
                readingBookmarkId(workId, dev.paperreader.logic.domain.ManifestationId(manifestation.id), sha, 0).value, 1,
            )))
        }
        return Fixture(database, root, repository(database, root), sha)
    }

    private fun repository(database: LibraryDatabase, root: Path) = RoomLibraryArchiveRepository(
        database, RoomMetadataBackupRepository(database), root, root.resolve("sessions"),
    )

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(val database: LibraryDatabase, val root: Path,
        val repository: RoomLibraryArchiveRepository, val sha: String)

    private companion object {
        val PDF = "%PDF-1.4\nOriginal scientific document\n%%EOF".toByteArray()
    }
}
