package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.local.LocalPdfSourceException
import dev.paperreader.logic.local.LocalPdfSourceMetadata
import dev.paperreader.logic.local.LocalPdfSourceResolver
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLocalPdfImportAndroidTest {
    private lateinit var database: LibraryDatabase
    private lateinit var filesRoot: java.nio.file.Path
    private lateinit var sources: FakeLocalPdfSourceResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesRoot = context.cacheDir.toPath().resolve("local-import-${UUID.randomUUID()}")
        sources = FakeLocalPdfSourceResolver()
    }

    @After
    fun tearDown() {
        database.close()
        if (Files.exists(filesRoot)) {
            Files.walk(filesRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun validImportCreatesOneWorkManifestationAndPrivateFileAndIsIdempotent() = runBlocking {
        val uri = "content://fixture/attention"
        val bytes = "%PDF-1.7\nAttention".toByteArray()
        sources.add(uri, "Attention Is All You Need.pdf", bytes)
        val repository = repository()

        val prepared = repository.prepare(uri) as PrepareLocalPdfResult.Ready
        assertEquals("Attention Is All You Need", prepared.candidate.suggestedTitle)
        val first = repository.import(
            prepared.candidate.importToken,
            prepared.candidate.suggestedTitle,
        ) as LocalPdfImportResult.Imported
        val repeated = repository.prepare(uri) as PrepareLocalPdfResult.Ready
        val second = repository.import(
            repeated.candidate.importToken,
            "A different title",
        ) as LocalPdfImportResult.AlreadyImported

        assertEquals(first.document, second.document)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllManifestations().size)
        assertEquals("Attention Is All You Need", database.libraryDao().getAllWorks().single().title)
        val stored = database.libraryDao().getFile(first.document.manifestationId.value)!!
        val path = filesRoot.resolve(checkNotNull(stored.localPath)).normalize()
        assertTrue(path.startsWith(filesRoot))
        assertEquals(bytes.toList(), Files.readAllBytes(path).toList())
    }

    @Test
    fun sameTitleWithDifferentHashesCreatesSeparateWorks() = runBlocking {
        val firstUri = "content://fixture/first"
        val secondUri = "content://fixture/second"
        sources.add(firstUri, "Same title.pdf", "%PDF-1.4\nfirst".toByteArray())
        sources.add(secondUri, "Same title.pdf", "%PDF-1.4\nsecond".toByteArray())
        val repository = repository()

        assertTrue(repository.import(prepareToken(repository, firstUri), "Same title") is LocalPdfImportResult.Imported)
        assertTrue(repository.import(prepareToken(repository, secondUri), "Same title") is LocalPdfImportResult.Imported)

        assertEquals(2, database.libraryDao().getAllWorks().size)
    }

    @Test
    fun malformedOversizedAndCancelledSourcesLeaveNoRowsOrFiles() = runBlocking {
        val invalidUri = "content://fixture/invalid"
        val largeUri = "content://fixture/large"
        val cancelledUri = "content://fixture/cancelled"
        sources.add(invalidUri, "invalid.pdf", "not a pdf".toByteArray())
        sources.add(largeUri, "large.pdf", "%PDF-".toByteArray() + ByteArray(128))
        sources.cancelOnOpen(cancelledUri, "cancelled.pdf")
        val repository = repository(maximumBytes = 32)

        assertEquals(
            LocalPdfImportFailure.INVALID_PDF,
            (repository.prepare(invalidUri) as PrepareLocalPdfResult.Rejected).reason,
        )
        assertEquals(
            LocalPdfImportFailure.TOO_LARGE,
            (repository.prepare(largeUri) as PrepareLocalPdfResult.Rejected).reason,
        )
        try {
            repository.prepare(cancelledUri)
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllManifestations().isEmpty())
        assertFalse(Files.exists(filesRoot.resolve("papers")))
        assertEquals(0L, stagingFileCount())
    }

    @Test
    fun identityConflictRollsBackPublishedFileAndDatabaseRows() = runBlocking {
        val uri = "content://fixture/conflict"
        val bytes = "%PDF-1.7\nconflict".toByteArray()
        val sha = sha256(bytes)
        sources.add(uri, "Conflict.pdf", bytes)
        database.libraryDao().upsertWork(
            WorkEntity(
                id = "w-local-$sha",
                title = "Conflicting restored row",
                abstractText = null,
                subjects = "",
                publishedDateEpochDay = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )

        val repository = repository()
        val result = repository.import(prepareToken(repository, uri), "Conflict") as LocalPdfImportResult.Rejected

        assertEquals(LocalPdfImportFailure.COMMIT_FAILED, result.reason)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertTrue(database.libraryDao().getAllManifestations().isEmpty())
        assertFalse(Files.exists(filesRoot.resolve("papers/local/$sha.pdf")))
        assertEquals(0L, stagingFileCount())
    }

    @Test
    fun metadataBackupRestoresIdentifierlessLocalWorkAndSameShaReattachesWithoutDuplication() = runBlocking {
        val uri = "content://fixture/portable"
        val bytes = "%PDF-1.7\nportable local paper".toByteArray()
        sources.add(uri, "Portable paper.pdf", bytes)
        val repository = repository()
        val imported = (
            repository.import(prepareToken(repository, uri), "Portable paper") as LocalPdfImportResult.Imported
            ).document
        val export = RoomMetadataBackupRepository(
            database,
            installedProviderIds = { setOf(LOCAL_PDF_SOURCE_ID) },
        ).export() as MetadataBackupExportResult.Success

        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val targetRoot = context.cacheDir.toPath().resolve("local-import-target-${UUID.randomUUID()}")
        val targetSources = FakeLocalPdfSourceResolver().apply {
            add(uri, "Portable paper.pdf", bytes)
        }
        try {
            val backup = RoomMetadataBackupRepository(
                targetDatabase,
                installedProviderIds = { setOf(LOCAL_PDF_SOURCE_ID) },
            )
            val preview = backup.preview(export.export.archiveBytes) as MetadataRestorePreviewResult.Ready
            assertEquals(1, preview.preview.newWorks)
            assertTrue(preview.preview.missingProviders.isEmpty())
            assertTrue(backup.restore(export.export.archiveBytes) is MetadataRestoreResult.Applied)

            assertEquals(imported.workId.value, targetDatabase.libraryDao().getAllWorks().single().id)
            assertEquals(
                imported.manifestationId.value,
                targetDatabase.libraryDao().getAllManifestations().single().id,
            )
            assertEquals(null, targetDatabase.libraryDao().getFile(imported.manifestationId.value))

            val targetRepository = RoomLocalPdfImportRepository(
                database = targetDatabase,
                sourceResolver = targetSources,
                filesDirectory = targetRoot,
                sessionDirectory = targetRoot.resolve("sessions"),
                maximumBytes = 1_024,
            )
            val reattached = targetRepository.import(
                prepareToken(targetRepository, uri),
                "A title that must not overwrite restored metadata",
            )
            assertTrue(reattached is LocalPdfImportResult.Imported)
            assertEquals(1, targetDatabase.libraryDao().getAllWorks().size)
            assertEquals("Portable paper", targetDatabase.libraryDao().getAllWorks().single().title)

            val repeatedPreview = backup.preview(export.export.archiveBytes) as MetadataRestorePreviewResult.Ready
            assertEquals(1, repeatedPreview.preview.mergedWorks)
            assertEquals(0, repeatedPreview.preview.skippedWorks)
        } finally {
            targetDatabase.close()
            if (Files.exists(targetRoot)) {
                Files.walk(targetRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    @Test
    fun preparedPdfSurvivesRepositoryRecreationAndRevokedSourceGrant() = runBlocking {
        val uri = "content://fixture/revoked-after-prepare"
        val bytes = "%PDF-1.7\ndurable staging".toByteArray()
        sources.add(uri, "Durable paper.pdf", bytes)
        val firstRepository = repository()
        val prepared = firstRepository.prepare(uri) as PrepareLocalPdfResult.Ready

        sources.remove(uri)
        val recreatedRepository = repository()
        val recovered = checkNotNull(recreatedRepository.recoverPending())
        assertEquals(prepared.candidate, recovered)
        val result = recreatedRepository.import(recovered.importToken, "Durable paper")

        assertTrue(result is LocalPdfImportResult.Imported)
        assertEquals(bytes.toList(), Files.readAllBytes(filesRoot.resolve("papers/local/${sha256(bytes)}.pdf")).toList())
        assertEquals(0L, stagingFileCount())
    }

    @Test
    fun rejectedReplacementDoesNotDestroyTheExistingPreparedSession() = runBlocking {
        val firstUri = "content://fixture/first-durable"
        val invalidUri = "content://fixture/invalid-replacement"
        sources.add(firstUri, "First durable.pdf", "%PDF-1.7\nfirst durable".toByteArray())
        sources.add(invalidUri, "Invalid replacement.pdf", "not a pdf".toByteArray())
        val repository = repository()
        val first = repository.prepare(firstUri) as PrepareLocalPdfResult.Ready

        assertEquals(
            LocalPdfImportFailure.INVALID_PDF,
            (repository.prepare(invalidUri) as PrepareLocalPdfResult.Rejected).reason,
        )

        assertEquals(first.candidate, repository.recoverPending())
        repository.discard(first.candidate.importToken)
        assertEquals(0L, stagingFileCount())
    }

    @Test
    fun startupRecoveryRemovesOnlyUnpublishedFilesLeftBeforeRoomCommit() = runBlocking {
        val bytes = "%PDF-1.7\npublished before process death".toByteArray()
        val sha = sha256(bytes)
        val orphan = filesRoot.resolve("papers/local/$sha.pdf")
        val temporary = filesRoot.resolve("papers/local/.local-pdf-dead-process.part")
        Files.createDirectories(checkNotNull(orphan.parent))
        Files.write(orphan, bytes)
        Files.write(temporary, bytes)
        val repository = repository()

        assertEquals(null, repository.recoverPending())

        assertFalse(Files.exists(orphan))
        assertFalse(Files.exists(temporary))
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
    }

    @Test
    fun sameShaAsProviderDownloadCreatesLocalProvenanceInsteadOfClaimingDuplicate() = runBlocking {
        val uri = "content://fixture/provider-same-sha"
        val bytes = "%PDF-1.7\nshared document bytes".toByteArray()
        val sha = sha256(bytes)
        sources.add(uri, "Imported copy.pdf", bytes)
        val providerPath = filesRoot.resolve("papers/provider-copy.pdf")
        Files.createDirectories(checkNotNull(providerPath.parent))
        Files.write(providerPath, bytes)
        database.libraryDao().upsertWork(
            WorkEntity("provider-work", "Provider work", null, "", null, 1, 1),
        )
        database.libraryDao().upsertManifestations(
            listOf(
                ManifestationEntity(
                    id = "provider-manifestation",
                    workId = "provider-work",
                    type = ManifestationType.PREPRINT.name,
                    sourceProvider = "arxiv",
                    sourceRecordId = "1234.5678v1",
                    version = "v1",
                    landingPageUrl = null,
                    pdfUrl = null,
                    license = null,
                    publishedDateEpochDay = null,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
        database.libraryDao().upsertFile(
            FileEntity(
                id = "provider-file",
                manifestationId = "provider-manifestation",
                localPath = "papers/provider-copy.pdf",
                sha256 = sha,
                byteLength = bytes.size.toLong(),
                mimeType = "application/pdf",
                extractionStatus = "NOT_STARTED",
                extractionManifestPath = null,
                updatedAtEpochMillis = 1,
            ),
        )
        val repository = repository()

        val result = repository.import(prepareToken(repository, uri), "Imported copy")

        assertTrue(result is LocalPdfImportResult.Imported)
        assertEquals(2, database.libraryDao().getAllWorks().size)
        val localManifestation = database.libraryDao().getAllManifestations()
            .single { it.sourceProvider == LOCAL_PDF_SOURCE_ID }
        assertEquals(sha, localManifestation.sourceRecordId)
        assertEquals(1, database.libraryDao().getFilesForWork("provider-work").size)
        assertEquals(1, database.libraryDao().getFilesForWork("w-local-$sha").size)
    }

    @Test
    fun sameShaOnNonCanonicalLocalOwnerIsRejectedWithoutSelectingAnArbitraryWork() = runBlocking {
        val uri = "content://fixture/non-canonical-local-owner"
        val bytes = "%PDF-1.7\nidentity conflict".toByteArray()
        val sha = sha256(bytes)
        sources.add(uri, "Identity conflict.pdf", bytes)
        val existingPath = filesRoot.resolve("papers/existing-local.pdf")
        Files.createDirectories(checkNotNull(existingPath.parent))
        Files.write(existingPath, bytes)
        database.libraryDao().upsertWork(
            WorkEntity("unrelated-work", "Unrelated work", null, "", null, 1, 1),
        )
        database.libraryDao().upsertManifestations(
            listOf(
                ManifestationEntity(
                    id = "non-canonical-local-manifestation",
                    workId = "unrelated-work",
                    type = ManifestationType.OTHER.name,
                    sourceProvider = LOCAL_PDF_SOURCE_ID,
                    sourceRecordId = sha,
                    version = null,
                    landingPageUrl = null,
                    pdfUrl = null,
                    license = null,
                    publishedDateEpochDay = null,
                    updatedAtEpochMillis = 1,
                ),
            ),
        )
        database.libraryDao().upsertFile(
            FileEntity(
                id = "non-canonical-local-file",
                manifestationId = "non-canonical-local-manifestation",
                localPath = "papers/existing-local.pdf",
                sha256 = sha,
                byteLength = bytes.size.toLong(),
                mimeType = "application/pdf",
                extractionStatus = "NOT_STARTED",
                extractionManifestPath = null,
                updatedAtEpochMillis = 1,
            ),
        )
        val repository = repository()

        val result = repository.import(prepareToken(repository, uri), "Must not attach")

        assertEquals(
            LocalPdfImportFailure.COMMIT_FAILED,
            (result as LocalPdfImportResult.Rejected).reason,
        )
        assertEquals(listOf("unrelated-work"), database.libraryDao().getAllWorks().map { it.id })
        assertEquals(1, database.libraryDao().getAllManifestations().size)
        assertTrue(Files.isRegularFile(existingPath))
        assertFalse(Files.exists(filesRoot.resolve("papers/local/$sha.pdf")))
        assertEquals(0L, stagingFileCount())
    }

    private fun repository(maximumBytes: Long = 1_024) = RoomLocalPdfImportRepository(
        database = database,
        sourceResolver = sources,
        filesDirectory = filesRoot,
        sessionDirectory = filesRoot.resolve("sessions"),
        maximumBytes = maximumBytes,
    )

    private suspend fun prepareToken(
        repository: RoomLocalPdfImportRepository,
        uri: String,
    ): String = (repository.prepare(uri) as PrepareLocalPdfResult.Ready).candidate.importToken

    private fun stagingFileCount(): Long {
        val staging = filesRoot.resolve("sessions")
        return if (Files.isDirectory(staging)) Files.list(staging).use { it.count() } else 0
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

private class FakeLocalPdfSourceResolver : LocalPdfSourceResolver {
    private data class Entry(val displayName: String, val bytes: ByteArray?, val cancels: Boolean)
    private val entries = mutableMapOf<String, Entry>()

    fun add(uri: String, displayName: String, bytes: ByteArray) {
        entries[uri] = Entry(displayName, bytes, false)
    }

    fun cancelOnOpen(uri: String, displayName: String) {
        entries[uri] = Entry(displayName, null, true)
    }

    fun remove(uri: String) {
        entries.remove(uri)
    }

    override fun inspect(sourceUri: String): LocalPdfSourceMetadata {
        val entry = entries[sourceUri] ?: throw LocalPdfSourceException.InvalidUri()
        return LocalPdfSourceMetadata(entry.displayName, entry.bytes?.size?.toLong())
    }

    override fun open(sourceUri: String): InputStream {
        val entry = entries[sourceUri] ?: throw LocalPdfSourceException.InvalidUri()
        if (entry.cancels) {
            return object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw CancellationException("cancelled")
            }
        }
        return ByteArrayInputStream(checkNotNull(entry.bytes))
    }
}
