package dev.paperreader.logic.task

import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.LocalFileRepository
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.network.PdfDownloader
import dev.paperreader.logic.provider.RemotePaper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaperDownloadCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsVerifiedArtifactAndCompletesDurableTask() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\nreal paper"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val request = fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
            assertTrue(request is DownloadRequestResult.Enqueued)
            val task = (request as DownloadRequestResult.Enqueued).task

            val result = fixture.coordinator.execute(task.id)

            assertTrue(result is DownloadExecutionResult.Succeeded)
            val succeeded = result as DownloadExecutionResult.Succeeded
            assertEquals("content://test/${succeeded.paper.sha256}.pdf", succeeded.paper.contentUri)
            assertEquals(TaskState.SUCCEEDED, fixture.tasks.get(task.id)?.state)
            assertEquals(1.0, fixture.tasks.get(task.id)?.progress)
            assertEquals(succeeded.paper.sha256, fixture.files.get(MANIFESTATION_ID)?.sha256)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun queuesRetryForTransientHttpThenSucceedsOnNextAttempt() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("%PDF-1.7\nrecovered"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task

            val first = fixture.coordinator.execute(task.id)
            assertTrue(first is DownloadExecutionResult.Retry)
            assertEquals("HTTP_503", (first as DownloadExecutionResult.Retry).failureCode)
            assertEquals(TaskState.QUEUED, fixture.tasks.get(task.id)?.state)
            assertEquals(1, fixture.tasks.get(task.id)?.attempt)

            val second = fixture.coordinator.execute(task.id)
            assertTrue(second is DownloadExecutionResult.Succeeded)
            assertEquals(TaskState.SUCCEEDED, fixture.tasks.get(task.id)?.state)
            assertEquals(2, fixture.tasks.get(task.id)?.attempt)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stopsAutomaticRetriesAfterConfiguredAttemptLimit() = runTest {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task

            repeat(2) {
                assertTrue(fixture.coordinator.execute(task.id) is DownloadExecutionResult.Retry)
            }
            val final = fixture.coordinator.execute(task.id)

            assertEquals(
                DownloadExecutionResult.Failed("HTTP_503_RETRY_EXHAUSTED"),
                final,
            )
            assertEquals(TaskState.FAILED, fixture.tasks.get(task.id)?.state)
            assertEquals(3, fixture.tasks.get(task.id)?.attempt)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsInvalidPdfWithoutCreatingLocalArtifact() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not a PDF"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task

            val result = fixture.coordinator.execute(task.id)

            assertEquals(DownloadExecutionResult.Failed("INVALID_PDF"), result)
            assertEquals(TaskState.FAILED, fixture.tasks.get(task.id)?.state)
            assertNull(fixture.files.get(MANIFESTATION_ID))
            assertFalse(temporaryFolder.root.walkTopDown().any { it.extension == "pdf" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun deletionRemovesFileArtifactAndTerminalTaskForCleanRedownload() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\ndownloaded"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task
            fixture.coordinator.execute(task.id)

            assertEquals(
                DeleteDownloadResult.Deleted,
                fixture.coordinator.deleteDownload(WORK_ID, MANIFESTATION_ID),
            )
            assertNull(fixture.files.get(MANIFESTATION_ID))
            assertNull(fixture.tasks.get(task.id))
            assertFalse(temporaryFolder.root.walkTopDown().any { it.extension == "pdf" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sameSizeMutationInvalidatesArtifactBeforeItCanBeOpened() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\nreal paper"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task
            fixture.coordinator.execute(task.id)
            val artifact = checkNotNull(fixture.files.get(MANIFESTATION_ID))
            val storedFile = temporaryFolder.root.toPath().resolve(artifact.storagePath)
            val replacement = "%PDF-1.7\nfake paper".toByteArray()
            assertEquals(artifact.byteLength, replacement.size.toLong())
            Files.write(storedFile, replacement)

            assertNull(fixture.coordinator.downloadedPaper(MANIFESTATION_ID))
            assertNull(fixture.files.get(MANIFESTATION_ID))
            assertFalse(Files.exists(storedFile))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refusesDeletionWhileDownloadTaskIsActive() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)

            assertEquals(
                DeleteDownloadResult.ActiveTask,
                fixture.coordinator.deleteDownload(WORK_ID, MANIFESTATION_ID),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancelledQueuedDownloadIsNotRecoveredByExecution() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\nshould not download"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task

            assertTrue(fixture.coordinator.cancelDownload(task.id) is CancelTaskResult.Cancelled)
            assertEquals(DownloadExecutionResult.Cancelled, fixture.coordinator.execute(task.id))
            assertEquals(TaskState.CANCELLED, fixture.tasks.get(task.id)?.state)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun manualRetryGetsFreshAttemptsAfterAutomaticRetriesAreExhausted() = runTest {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(MockResponse().setBody("%PDF-1.7\nmanual retry succeeds"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task
            repeat(3) { fixture.coordinator.execute(task.id) }

            val retried = fixture.coordinator.retryDownload(task.id) as RetryDownloadResult.Enqueued
            val result = fixture.coordinator.execute(retried.task.id)

            assertEquals(0, retried.task.attempt)
            assertTrue(result is DownloadExecutionResult.Succeeded)
            assertEquals(1, fixture.tasks.get(task.id)?.attempt)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun clearingCompletedUpdateKeepsVerifiedLocalPdf() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\nkeep this paper"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task
            fixture.coordinator.execute(task.id)

            assertEquals(RemoveTaskResult.Removed, fixture.coordinator.removeDownloadTask(task.id))
            assertNull(fixture.tasks.get(task.id))
            assertTrue(fixture.coordinator.downloadedPaper(MANIFESTATION_ID) != null)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellationWinningArtifactCommitRaceRollsBackUnpublishedPdf() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\ncommit race"))
        server.start()
        try {
            val fixture = fixture(server.url("/paper.pdf").toString())
            val task = (fixture.coordinator.requestDownload(WORK_ID, MANIFESTATION_ID)
                as DownloadRequestResult.Enqueued).task
            val artifactWritten = CompletableDeferred<Unit>()
            val allowCompletion = CompletableDeferred<Unit>()
            fixture.files.afterUpsert = {
                artifactWritten.complete(Unit)
                allowCompletion.await()
            }

            val execution = async { fixture.coordinator.execute(task.id) }
            artifactWritten.await()
            assertTrue(fixture.coordinator.cancelDownload(task.id) is CancelTaskResult.Cancelled)
            allowCompletion.complete(Unit)

            assertEquals(DownloadExecutionResult.Cancelled, execution.await())
            assertEquals(TaskState.CANCELLED, fixture.tasks.get(task.id)?.state)
            assertNull(fixture.files.get(MANIFESTATION_ID))
            assertFalse(temporaryFolder.root.walkTopDown().any { it.extension == "pdf" })
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = DownloadRequestException.PdfUnavailable::class)
    fun rejectsUnsafePersistedPdfUrlBeforeEnqueueing() = runTest {
        fixture("file:///data/local/private.pdf").coordinator
            .requestDownload(WORK_ID, MANIFESTATION_ID)
    }

    private fun fixture(pdfUrl: String): Fixture {
        val taskRepository = FakeTaskRepository()
        val tasks = TaskCoordinator(taskRepository, FIXED_CLOCK)
        val files = FakeLocalFileRepository()
        val paper = LibraryPaper(
            work = PaperWork(
                id = WORK_ID,
                title = "Real paper",
                createdAt = NOW,
                updatedAt = NOW,
            ),
            manifestations = listOf(
                PaperManifestation(
                    id = MANIFESTATION_ID,
                    workId = WORK_ID,
                    type = ManifestationType.PREPRINT,
                    sourceProvider = "arxiv",
                    sourceRecordId = "1706.03762",
                    pdfUrl = pdfUrl,
                    updatedAt = NOW,
                ),
            ),
        )
        val coordinator = PaperDownloadCoordinator(
            library = FakeLibraryRepository(paper),
            localFiles = files,
            tasks = tasks,
            downloader = PdfDownloader(OkHttpClient(), "PaperReader/Test", null, 1_024 * 1_024),
            filesDirectory = temporaryFolder.root.toPath(),
            contentUriForFile = { path -> "content://test/${path.fileName}" },
            clock = FIXED_CLOCK,
        )
        return Fixture(coordinator, tasks, files)
    }

    private data class Fixture(
        val coordinator: PaperDownloadCoordinator,
        val tasks: TaskCoordinator,
        val files: FakeLocalFileRepository,
    )

    private companion object {
        val WORK_ID = WorkId("work-1")
        val MANIFESTATION_ID = ManifestationId("manifestation-1")
        val NOW: Instant = Instant.parse("2026-08-11T00:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}

private class FakeLibraryRepository(private val paper: LibraryPaper) : LibraryRepository {
    override val library: Flow<List<LibraryPaper>> = MutableStateFlow(listOf(paper))
    override val collections: Flow<List<PaperCollection>> = MutableStateFlow(emptyList())
    override suspend fun save(remote: RemotePaper): WorkId = error("Not used")
    override suspend fun get(workId: WorkId): LibraryPaper? = paper.takeIf { it.work.id == workId }
    override suspend fun updateReadingState(state: ReadingState) = error("Not used")
    override suspend fun remove(workId: WorkId): RemovePaperResult = error("Not used")
    override suspend fun createCollection(name: String): CreateCollectionResult = error("Not used")
    override suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult = error("Not used")
    override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult = error("Not used")
    override suspend fun setPaperCollections(
        workId: WorkId,
        collectionIds: Set<CollectionId>,
    ): SetPaperCollectionsResult = error("Not used")
}

private class FakeLocalFileRepository : LocalFileRepository {
    private val artifacts = mutableMapOf<ManifestationId, LocalPaperArtifact>()
    var afterUpsert: suspend () -> Unit = {}
    override suspend fun get(manifestationId: ManifestationId): LocalPaperArtifact? = artifacts[manifestationId]
    override suspend fun upsert(artifact: LocalPaperArtifact) {
        artifacts[artifact.manifestationId] = artifact
        afterUpsert()
    }
    override suspend fun remove(manifestationId: ManifestationId) {
        artifacts.remove(manifestationId)
    }
}

private class FakeTaskRepository : PaperTaskRepository {
    private val rows = linkedMapOf<TaskId, PaperTask>()
    private val mutableTasks = MutableStateFlow<List<PaperTask>>(emptyList())
    override val tasks: Flow<List<PaperTask>> = mutableTasks
    override suspend fun get(id: TaskId): PaperTask? = rows[id]
    override suspend fun insertIfAbsent(task: PaperTask): Boolean {
        if (rows.putIfAbsent(task.id, task) != null) return false
        publish()
        return true
    }
    override suspend fun updateIfCurrent(task: PaperTask, expected: PaperTask): Boolean {
        if (rows[task.id] != expected) return false
        rows[task.id] = task
        publish()
        return true
    }
    override suspend fun deleteIfState(id: TaskId, expectedState: TaskState): Boolean {
        if (rows[id]?.state != expectedState) return false
        rows.remove(id)
        publish()
        return true
    }
    private fun publish() {
        mutableTasks.value = rows.values.toList()
    }
}
