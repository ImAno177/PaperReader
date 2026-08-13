package dev.paperreader.logic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import dev.paperreader.logic.data.AnnotationEntity
import dev.paperreader.logic.data.CollectionEntity
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.IdentifierEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.ReadingBookmarkEntity
import dev.paperreader.logic.data.ReadingHistoryEntity
import dev.paperreader.logic.data.ReadingStateEntity
import dev.paperreader.logic.data.TaskEntity
import dev.paperreader.logic.data.WorkCollectionEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.readingBookmarkId
import dev.paperreader.logic.domain.repository.CreateSavedSearchResult
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMetadataBackupAndroidTest {
    private val databases = mutableListOf<LibraryDatabase>()

    @After
    fun tearDown() = databases.forEach(LibraryDatabase::close)

    @Test
    fun roundTripAcrossDatabasesRemapsExactDocumentAndPreservesLocalArtifacts() = runBlocking {
        val sourceDb = newDatabase()
        val source = populateSource(sourceDb)
        val archive = export(sourceDb)
        val targetDb = newDatabase()
        val targetLibrary = RoomLibraryRepository(targetDb, FIXED_CLOCK)
        val targetWork = targetLibrary.save(paper(manifestations = emptyList()))
        val localManifestation = ManifestationEntity(
            id = TARGET_MANIFESTATION_ID,
            workId = targetWork.value,
            type = ManifestationType.PREPRINT.name,
            sourceProvider = "fixture",
            sourceRecordId = "paper-1",
            version = "v1",
            landingPageUrl = "https://local.example/newer",
            pdfUrl = "https://local.example/newer.pdf",
            license = "CC-BY-4.0",
            publishedDateEpochDay = null,
            updatedAtEpochMillis = NEWER_TIME,
        )
        targetDb.libraryDao().upsertManifestations(listOf(localManifestation))
        targetDb.libraryDao().upsertFile(
            FileEntity(
                id = "target-file",
                manifestationId = TARGET_MANIFESTATION_ID,
                localPath = "/private/target.pdf",
                sha256 = DOCUMENT_SHA,
                byteLength = 42,
                mimeType = "application/pdf",
                extractionStatus = "READY",
                extractionManifestPath = null,
                updatedAtEpochMillis = NEWER_TIME,
            ),
        )
        targetDb.taskDao().insertTask(task("target-task", targetWork.value))
        val repository = repository(targetDb)

        val preview = repository.preview(archive.archiveBytes) as MetadataRestorePreviewResult.Ready
        assertEquals(0, preview.preview.newWorks)
        assertEquals(1, preview.preview.mergedWorks)
        assertEquals(0, preview.preview.skippedWorks)
        assertEquals(0, preview.preview.dormantBookmarks)
        assertEquals(0, preview.preview.dormantAnnotations)
        assertEquals(1, preview.preview.summary.savedSearches)
        assertEquals(1, preview.preview.summary.savedSearchHits)

        val first = repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied
        val firstSavedHit = targetDb.savedSearchDao().getAllHits().single()
        targetDb.savedSearchDao().upsertHits(listOf(firstSavedHit.copy(unread = true)))
        val second = repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied

        assertEquals(1, first.appliedWorks)
        assertEquals(1, first.appliedSavedSearches)
        assertEquals(1, first.appliedSavedSearchHits)
        assertEquals(0, first.skippedRecords)
        assertEquals(first.preview, second.preview)
        assertEquals(1, targetDb.libraryDao().getAllWorks().size)
        assertEquals(1, targetDb.libraryDao().getAllCollections().size)
        assertEquals(1, targetDb.libraryDao().getAllWorkCollections().size)
        val state = targetDb.libraryDao().getAllReadingStates().single()
        assertEquals(TARGET_MANIFESTATION_ID, state.manifestationId)
        assertEquals(DOCUMENT_SHA, state.documentSha256)
        assertEquals(1, targetDb.libraryDao().getAllReadingHistory().size)
        val bookmark = targetDb.readingBookmarkDao().getAll().single()
        assertEquals(TARGET_MANIFESTATION_ID, bookmark.manifestationId)
        assertEquals(DOCUMENT_SHA, bookmark.documentSha256)
        assertEquals(1, targetDb.libraryDao().getAllAnnotations().size)
        assertEquals("target-file", targetDb.libraryDao().getFile(TARGET_MANIFESTATION_ID)?.id)
        assertEquals("target-task", targetDb.taskDao().getTask("target-task")?.id)
        assertNull(targetDb.taskDao().getTask("source-task"))
        assertNull(targetDb.libraryDao().getFile(source.manifestationId))
        val mergedManifestation = targetDb.libraryDao().getAllManifestations().single()
        assertEquals(TARGET_MANIFESTATION_ID, mergedManifestation.id)
        assertEquals("https://local.example/newer", mergedManifestation.landingPageUrl)
        assertEquals("https://local.example/newer.pdf", mergedManifestation.pdfUrl)
        assertEquals("CC-BY-4.0", mergedManifestation.license)
        assertEquals(NEWER_TIME, mergedManifestation.updatedAtEpochMillis)
        assertEquals(1, targetDb.savedSearchDao().getAllSearches().size)
        assertEquals(listOf("fixture"), targetDb.savedSearchDao().getAllSources().map { it.providerId })
        val restoredHit = targetDb.savedSearchDao().getAllHits().single()
        assertEquals(targetWork.value, restoredHit.linkedWorkId)
        assertTrue(restoredHit.unread)
    }

    @Test
    fun sameTitleWithDifferentDoiRemainsSeparateAndAnchorsStayDormant() = runBlocking {
        val sourceDb = newDatabase()
        populateSource(sourceDb)
        val archive = export(sourceDb)
        val targetDb = newDatabase()
        RoomLibraryRepository(targetDb, FIXED_CLOCK).save(
            paper(
                providerId = "other",
                providerRecordId = "other-record",
                doi = "10.1000/different-work",
                manifestations = emptyList(),
            ),
        )
        val repository = repository(targetDb)

        val preview = repository.preview(archive.archiveBytes) as MetadataRestorePreviewResult.Ready
        assertEquals(1, preview.preview.newWorks)
        assertEquals(0, preview.preview.mergedWorks)
        assertEquals(1, preview.preview.dormantBookmarks)
        assertEquals(1, preview.preview.dormantAnnotations)

        repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied
        assertEquals(2, targetDb.libraryDao().getAllWorks().size)
        assertEquals(1, targetDb.readingBookmarkDao().getAll().size)
        assertEquals(1, targetDb.libraryDao().getAllAnnotations().size)
    }

    @Test
    fun legacyNoncanonicalLocalDoiStillMergesExactlyAndRestoreIsIdempotent() = runBlocking {
        val sourceDb = newDatabase()
        populateSource(sourceDb)
        val archive = export(sourceDb)
        val targetDb = newDatabase()
        val dao = targetDb.libraryDao()
        dao.upsertWork(
            WorkEntity(
                id = "legacy-local-work",
                title = "Legacy local record",
                abstractText = null,
                subjects = "",
                publishedDateEpochDay = null,
                createdAtEpochMillis = FIXED_TIME,
                updatedAtEpochMillis = FIXED_TIME,
            ),
        )
        dao.upsertIdentifiers(
            listOf(IdentifierEntity("legacy-local-work", "DOI", "doi:$SOURCE_DOI", "")),
        )
        val repository = repository(targetDb)

        val preview = repository.preview(archive.archiveBytes) as MetadataRestorePreviewResult.Ready
        assertEquals(0, preview.preview.newWorks)
        assertEquals(1, preview.preview.mergedWorks)

        repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied
        val identifierCountAfterFirstRestore = dao.getAllIdentifiers().size
        repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied

        assertEquals(1, dao.getAllWorks().size)
        assertEquals("legacy-local-work", dao.getAllWorks().single().id)
        assertEquals(identifierCountAfterFirstRestore, dao.getAllIdentifiers().size)
        assertTrue(dao.getAllIdentifiers().any { it.value == SOURCE_DOI })
    }

    @Test
    fun annotationIdCollisionNeverOverwritesDifferentLocalAnchor() = runBlocking {
        val sourceDb = newDatabase()
        populateSource(sourceDb, annotationId = "shared-annotation")
        val archive = export(sourceDb)
        val targetDb = newDatabase()
        val targetLibrary = RoomLibraryRepository(targetDb, FIXED_CLOCK)
        targetLibrary.save(paper(manifestations = emptyList()))
        val otherWork = targetLibrary.save(
            paper(
                providerId = "other",
                providerRecordId = "other-record",
                doi = "10.1000/other-annotation-work",
                manifestations = emptyList(),
            ),
        )
        val localAnnotation = annotation(
            id = "shared-annotation",
            workId = otherWork.value,
            sha = "b".repeat(64),
            quote = "Local quote",
            note = "Never replace this",
        )
        targetDb.libraryDao().upsertAnnotations(listOf(localAnnotation))
        val repository = repository(targetDb)

        val preview = repository.preview(archive.archiveBytes) as MetadataRestorePreviewResult.Ready
        assertTrue(preview.preview.conflicts.any { it.code == "annotation_conflict" })
        assertEquals(1, preview.preview.skippedRecords)

        val result = repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied
        assertEquals(0, result.appliedAnnotations)
        assertEquals(1, result.skippedRecords)
        assertEquals(localAnnotation, targetDb.libraryDao().getAnnotation("shared-annotation"))
    }

    @Test
    fun ambiguousExactAliasSkipsTheWorkAndEveryDependentRecordWithHonestCounts() = runBlocking {
        val sourceDb = newDatabase()
        val source = populateSource(sourceDb)
        val archive = export(sourceDb)
        val targetDb = newDatabase()
        val dao = targetDb.libraryDao()
        listOf("local-one", "local-two").forEach { id ->
            dao.upsertWork(WorkEntity(id, "Local", null, "", null, 1, 1))
            dao.upsertIdentifiers(listOf(IdentifierEntity(id, "DOI", SOURCE_DOI, "")))
        }
        val repository = repository(targetDb)

        val preview = repository.preview(archive.archiveBytes) as MetadataRestorePreviewResult.Ready
        assertEquals(0, preview.preview.newWorks)
        assertEquals(0, preview.preview.mergedWorks)
        assertEquals(1, preview.preview.skippedWorks)
        assertTrue(preview.preview.skippedRecords >= 6)
        assertTrue(preview.preview.conflicts.any { it.code == "alias_conflict" })

        val result = repository.restore(archive.archiveBytes) as MetadataRestoreResult.Applied
        assertEquals(0, result.appliedWorks)
        assertEquals(1, result.skippedWorks)
        assertEquals(preview.preview.skippedRecords, result.skippedRecords)
        assertEquals(2, dao.getAllWorks().size)
        assertTrue(dao.getAllWorkCollections().isEmpty())
        assertTrue(dao.getAllReadingStates().isEmpty())
        assertTrue(dao.getAllReadingHistory().isEmpty())
        assertTrue(targetDb.readingBookmarkDao().getAll().isEmpty())
        assertNull(dao.getAnnotation(source.annotationId))
    }

    @Test
    fun invalidArchivePerformsZeroWrites() = runBlocking {
        val targetDb = newDatabase()
        val repository = repository(targetDb)

        val result = repository.restore(byteArrayOf(1, 2, 3, 4))

        assertTrue(result is MetadataRestoreResult.Rejected)
        assertTrue(targetDb.libraryDao().getAllWorks().isEmpty())
        assertTrue(targetDb.libraryDao().getAllCollections().isEmpty())
        assertTrue(targetDb.readingBookmarkDao().getAll().isEmpty())
    }

    @Test
    fun malformedLegacyRowsRejectExportWithoutEscapingTheTypedContract() = runBlocking {
        val database = newDatabase()
        val dao = database.libraryDao()
        dao.upsertWork(
            WorkEntity(
                id = "legacy-work",
                title = "Legacy metadata",
                abstractText = null,
                subjects = "",
                publishedDateEpochDay = null,
                createdAtEpochMillis = FIXED_TIME,
                updatedAtEpochMillis = FIXED_TIME,
            ),
        )
        dao.upsertIdentifiers(
            listOf(
                IdentifierEntity(
                    workId = "legacy-work",
                    type = "BROKEN_IDENTIFIER_TYPE",
                    value = "not-canonical",
                    authority = "",
                ),
            ),
        )

        val result = repository(database).export()

        assertTrue(result is MetadataBackupExportResult.Rejected)
        assertEquals("invalid_local_data", (result as MetadataBackupExportResult.Rejected).error.code)
    }

    @Test
    fun olderProviderMetadataCanBeExportedAfterARecentLocalSave() = runBlocking {
        val database = newDatabase()
        val olderPaper = paper().copy(updatedAt = Instant.parse("2017-06-12T00:00:00Z"))
        RoomLibraryRepository(database, FIXED_CLOCK).save(olderPaper)

        val result = repository(database).export()

        assertTrue(result is MetadataBackupExportResult.Success)
    }

    private suspend fun populateSource(
        database: LibraryDatabase,
        annotationId: String = "annotation-1",
    ): SourceFixture {
        val library = RoomLibraryRepository(database, FIXED_CLOCK)
        val workId = library.save(paper())
        val manifestation = database.libraryDao().getManifestations(workId.value).single()
        val collectionId = database.libraryDao().insertCollection(
            CollectionEntity(
                name = "Reading list",
                sortOrder = 0,
                createdAtEpochMillis = FIXED_TIME,
                updatedAtEpochMillis = FIXED_TIME,
            ),
        )
        database.libraryDao().upsertWorkCollections(listOf(WorkCollectionEntity(workId.value, collectionId)))
        database.libraryDao().upsertReadingState(
            ReadingStateEntity(
                workId = workId.value,
                manifestationId = manifestation.id,
                documentSha256 = DOCUMENT_SHA,
                blockId = "page-2",
                characterOffset = 17,
                pageIndex = 2,
                progression = 0.5,
                status = "READING",
                updatedAtEpochMillis = FIXED_TIME,
            ),
        )
        database.libraryDao().upsertReadingHistory(
            listOf(ReadingHistoryEntity(workId.value, FIXED_TIME, 60_000, 2)),
        )
        val manifestationId = ManifestationId(manifestation.id)
        database.readingBookmarkDao().upsertAll(
            listOf(
                ReadingBookmarkEntity(
                    workId = workId.value,
                    manifestationId = manifestation.id,
                    documentSha256 = DOCUMENT_SHA,
                    pageIndex = 2,
                    id = readingBookmarkId(workId, manifestationId, DOCUMENT_SHA, 2).value,
                    createdAtEpochMillis = FIXED_TIME,
                ),
            ),
        )
        database.libraryDao().upsertAnnotations(
            listOf(annotation(annotationId, workId.value, DOCUMENT_SHA, "Quoted text", "Research note")),
        )
        val savedSearches = RoomSavedSearchRepository(database, FIXED_CLOCK)
        val savedSearch = savedSearches.create("graph learning", setOf("fixture"))
        val savedSearchId = (savedSearch as CreateSavedSearchResult.Created).searchId
        savedSearches.recordSuccess(
            id = savedSearchId,
            providerId = "fixture",
            records = listOf(paper()),
            checkedAt = Instant.ofEpochMilli(FIXED_TIME),
        )
        database.libraryDao().upsertFile(
            FileEntity(
                id = "source-file",
                manifestationId = manifestation.id,
                localPath = "/private/source.pdf",
                sha256 = DOCUMENT_SHA,
                byteLength = 42,
                mimeType = "application/pdf",
                extractionStatus = "READY",
                extractionManifestPath = null,
                updatedAtEpochMillis = FIXED_TIME,
            ),
        )
        database.taskDao().insertTask(task("source-task", workId.value))
        return SourceFixture(workId, manifestation.id, annotationId)
    }

    private fun annotation(
        id: String,
        workId: String,
        sha: String,
        quote: String,
        note: String,
    ) = AnnotationEntity(
        id = id,
        workId = workId,
        documentSha256 = sha,
        blockId = "page-2",
        startOffset = 1,
        endOffset = 1 + quote.length,
        quotePrefix = "Before",
        quoteExact = quote,
        quoteSuffix = "After",
        pageIndex = 2,
        note = note,
        color = "yellow",
        createdAtEpochMillis = FIXED_TIME,
        updatedAtEpochMillis = FIXED_TIME,
    )

    private fun task(id: String, workId: String) = TaskEntity(
        id = id,
        kind = "DOWNLOAD",
        workId = workId,
        targetKey = "$id-target",
        state = "QUEUED",
        progress = 0.0,
        attempt = 0,
        failureCode = null,
        createdAtEpochMillis = FIXED_TIME,
        updatedAtEpochMillis = FIXED_TIME,
    )

    private fun paper(
        providerId: String = "fixture",
        providerRecordId: String = "paper-1",
        doi: String = SOURCE_DOI,
        manifestations: List<RemoteManifestation> = listOf(
            RemoteManifestation(
                type = ManifestationType.PREPRINT,
                version = "v1",
                landingPageUrl = "https://source.example/paper",
                pdfUrl = "https://source.example/paper.pdf",
                license = "CC-BY",
            ),
        ),
    ) = RemotePaper(
        providerId = providerId,
        providerRecordId = providerRecordId,
        title = "An identical title must not merge papers",
        abstractText = "Line one\nLine two",
        authors = listOf(PaperAuthor("Ada Lovelace")),
        identifiers = setOf(PaperIdentifier(IdentifierType.DOI, doi)),
        subjects = setOf("Computer science"),
        updatedAt = Instant.ofEpochMilli(FIXED_TIME),
        manifestations = manifestations,
    )

    private suspend fun export(database: LibraryDatabase): MetadataBackupExport =
        (repository(database).export() as MetadataBackupExportResult.Success).export

    private fun repository(database: LibraryDatabase) = RoomMetadataBackupRepository(
        database = database,
        installedProviderIds = { setOf("fixture") },
        clock = FIXED_CLOCK,
    )

    private fun newDatabase(): LibraryDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)
    }

    private data class SourceFixture(
        val workId: WorkId,
        val manifestationId: String,
        val annotationId: String,
    )

    private companion object {
        const val SOURCE_DOI = "10.1000/backup-test"
        const val DOCUMENT_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TARGET_MANIFESTATION_ID = "target-manifestation"
        const val FIXED_TIME = 1_786_467_600_000L
        const val NEWER_TIME = FIXED_TIME + 86_400_000L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_TIME), ZoneOffset.UTC)
    }
}
