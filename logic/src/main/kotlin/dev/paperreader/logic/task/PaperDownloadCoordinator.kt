package dev.paperreader.logic.task

import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.LocalFileRepository
import dev.paperreader.logic.network.PdfDownloadException
import dev.paperreader.logic.network.PdfDownloader
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadedPaper(
    val manifestationId: ManifestationId,
    val contentUri: String,
    val sha256: String,
    val byteLength: Long,
)

sealed interface DownloadRequestResult {
    data class Enqueued(val task: PaperTask) : DownloadRequestResult
    data class AlreadyDownloaded(val paper: DownloadedPaper) : DownloadRequestResult
}

sealed interface DownloadExecutionResult {
    data class Succeeded(val paper: DownloadedPaper) : DownloadExecutionResult
    data object Cancelled : DownloadExecutionResult
    data class Retry(
        val failureCode: String,
        val retryAfterMillis: Long?,
    ) : DownloadExecutionResult

    data class Failed(val failureCode: String) : DownloadExecutionResult
}

sealed interface RetryDownloadResult {
    data class Enqueued(val task: PaperTask) : RetryDownloadResult
    data class AlreadyDownloaded(val paper: DownloadedPaper) : RetryDownloadResult
    data class NotRetryable(val task: PaperTask) : RetryDownloadResult
    data object NotFound : RetryDownloadResult
}

sealed interface DeleteDownloadResult {
    data object Deleted : DeleteDownloadResult
    data object NotFound : DeleteDownloadResult
    data object ActiveTask : DeleteDownloadResult
}

sealed class DownloadRequestException(message: String) : IllegalArgumentException(message) {
    class PaperNotFound(workId: WorkId) : DownloadRequestException("Paper not found: ${workId.value}")
    class ManifestationNotFound(manifestationId: ManifestationId) :
        DownloadRequestException("Manifestation not found: ${manifestationId.value}")

    class PdfUnavailable(manifestationId: ManifestationId) :
        DownloadRequestException("No safe PDF URL for manifestation: ${manifestationId.value}")
}

/**
 * Durable PDF-download use case. Room owns queue and artifact state; WorkManager only invokes [execute].
 */
class PaperDownloadCoordinator internal constructor(
    private val library: LibraryRepository,
    private val localFiles: LocalFileRepository,
    private val tasks: TaskCoordinator,
    private val downloader: PdfDownloader,
    filesDirectory: Path,
    private val contentUriForFile: (Path) -> String,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumAttempts: Int = 3,
) {
    private val filesRoot = filesDirectory.toAbsolutePath().normalize()
    private val papersRoot = filesRoot.resolve(PAPERS_DIRECTORY).normalize()

    init {
        require(maximumAttempts > 0)
        require(papersRoot.startsWith(filesRoot))
    }

    suspend fun requestDownload(
        workId: WorkId,
        manifestationId: ManifestationId,
    ): DownloadRequestResult {
        requireManifestation(workId, manifestationId)
        downloadedPaper(manifestationId)?.let { return DownloadRequestResult.AlreadyDownloaded(it) }

        var task = tasks.find(TaskKind.DOWNLOAD, workId, manifestationId.value)
        if (task?.state == TaskState.SUCCEEDED) {
            tasks.removeTerminal(task.id)
            task = null
        } else if (task?.state in setOf(TaskState.FAILED, TaskState.CANCELLED)) {
            tasks.removeTerminal(checkNotNull(task).id)
            task = null
        }
        return DownloadRequestResult.Enqueued(
            task ?: tasks.enqueue(TaskKind.DOWNLOAD, workId, manifestationId.value),
        )
    }

    suspend fun cancelDownload(taskId: TaskId): CancelTaskResult {
        val task = tasks.get(taskId) ?: return CancelTaskResult.NotFound
        if (task.kind != TaskKind.DOWNLOAD) return CancelTaskResult.NotFound
        return tasks.cancel(taskId)
    }

    suspend fun retryDownload(taskId: TaskId): RetryDownloadResult {
        val task = tasks.get(taskId) ?: return RetryDownloadResult.NotFound
        val workId = task.workId
        if (task.kind != TaskKind.DOWNLOAD || workId == null) return RetryDownloadResult.NotFound
        if (task.state in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
            return RetryDownloadResult.NotRetryable(task)
        }
        val manifestationId = ManifestationId(task.targetKey)
        downloadedPaper(manifestationId)?.let { paper ->
            tasks.remove(taskId)
            return RetryDownloadResult.AlreadyDownloaded(paper)
        }
        if (task.state !in setOf(TaskState.FAILED, TaskState.CANCELLED)) {
            return RetryDownloadResult.NotRetryable(task)
        }
        requireManifestation(workId, manifestationId)
        return when (val result = tasks.retry(taskId)) {
            is RetryTaskResult.Retried -> RetryDownloadResult.Enqueued(result.task)
            is RetryTaskResult.NotRetryable -> RetryDownloadResult.NotRetryable(result.task)
            RetryTaskResult.NotFound -> RetryDownloadResult.NotFound
        }
    }

    suspend fun removeDownloadTask(taskId: TaskId): RemoveTaskResult {
        val task = tasks.get(taskId) ?: return RemoveTaskResult.NotFound
        if (task.kind != TaskKind.DOWNLOAD) return RemoveTaskResult.NotFound
        return tasks.remove(taskId)
    }

    suspend fun execute(taskId: TaskId): DownloadExecutionResult {
        var task = tasks.get(taskId)
            ?: return DownloadExecutionResult.Failed(FAILURE_TASK_NOT_FOUND)
        if (task.kind != TaskKind.DOWNLOAD || task.workId == null) {
            return DownloadExecutionResult.Failed(FAILURE_INVALID_TASK)
        }

        if (task.state == TaskState.SUCCEEDED) {
            downloadedPaper(ManifestationId(task.targetKey))?.let {
                return DownloadExecutionResult.Succeeded(it)
            }
            tasks.removeTerminal(task.id)
            task = tasks.enqueue(TaskKind.DOWNLOAD, task.workId, task.targetKey)
        }
        if (task.state == TaskState.CANCELLED) {
            return DownloadExecutionResult.Cancelled
        }
        if (task.state == TaskState.FAILED) {
            return DownloadExecutionResult.Failed(task.failureCode ?: FAILURE_UNEXPECTED)
        }
        if (task.state == TaskState.RUNNING) {
            tasks.transition(task.id, TaskState.FAILED, FAILURE_INTERRUPTED)
            task = tasks.transition(task.id, TaskState.QUEUED)
        }
        val running = tasks.transition(task.id, TaskState.RUNNING)
        val manifestationId = ManifestationId(running.targetKey)
        val manifestation = try {
            requireManifestation(checkNotNull(running.workId), manifestationId)
        } catch (_: DownloadRequestException.PaperNotFound) {
            return failPermanently(running, FAILURE_PAPER_NOT_FOUND)
        } catch (_: DownloadRequestException.ManifestationNotFound) {
            return failPermanently(running, FAILURE_MANIFESTATION_NOT_FOUND)
        } catch (_: DownloadRequestException.PdfUnavailable) {
            return failPermanently(running, FAILURE_PDF_UNAVAILABLE)
        }

        return try {
            val destination = papersRoot.resolve(stableHash(manifestationId.value)).normalize()
            check(destination.startsWith(papersRoot))
            val previous = localFiles.get(manifestationId)
            val downloaded = downloader.download(
                url = checkNotNull(manifestation.pdfUrl),
                destinationDirectory = destination,
                onProgress = { progress -> tasks.updateProgress(running.id, progress) },
            )
            val relativePath = filesRoot.relativize(downloaded.path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/')
            val artifact = LocalPaperArtifact(
                id = "file-${stableHash(manifestationId.value)}",
                manifestationId = manifestationId,
                storagePath = relativePath,
                sha256 = downloaded.sha256,
                byteLength = downloaded.byteLength,
                mimeType = PDF_MIME_TYPE,
                updatedAt = clock.instant(),
            )
            val paper = toDownloadedPaper(artifact, downloaded.path)
            try {
                localFiles.upsert(artifact)
            } catch (error: Exception) {
                runCatching { Files.deleteIfExists(downloaded.path) }
                throw error
            }
            try {
                tasks.transition(running.id, TaskState.SUCCEEDED)
            } catch (conflict: Exception) {
                if (conflict is CancellationException) throw conflict
                if (tasks.get(running.id)?.state == TaskState.CANCELLED) {
                    rollbackCancelledCommit(previous, artifact, downloaded.path)
                    return DownloadExecutionResult.Cancelled
                }
                throw conflict
            }
            deleteReplacedFile(previous, downloaded.path)
            DownloadExecutionResult.Succeeded(paper)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (conflict: ConcurrentTaskUpdateException) {
            if (tasks.get(running.id)?.state == TaskState.CANCELLED) {
                DownloadExecutionResult.Cancelled
            } else {
                throw conflict
            }
        } catch (invalidTransition: IllegalArgumentException) {
            if (tasks.get(running.id)?.state == TaskState.CANCELLED) {
                DownloadExecutionResult.Cancelled
            } else {
                throw invalidTransition
            }
        } catch (error: PdfDownloadException.RetryableHttp) {
            retryOrFail(running, "HTTP_${error.statusCode}", error.retryAfterMillis)
        } catch (_: PdfDownloadException.TooLarge) {
            failPermanently(running, FAILURE_TOO_LARGE)
        } catch (_: PdfDownloadException.InvalidPdf) {
            failPermanently(running, FAILURE_INVALID_PDF)
        } catch (error: PdfDownloadException.Http) {
            failPermanently(running, "HTTP_${error.statusCode}")
        } catch (_: IOException) {
            retryOrFail(running, FAILURE_IO, null)
        } catch (_: Exception) {
            retryOrFail(running, FAILURE_UNEXPECTED, null)
        }
    }

    suspend fun downloadedPaper(manifestationId: ManifestationId): DownloadedPaper? = withContext(Dispatchers.IO) {
        val artifact = localFiles.get(manifestationId) ?: return@withContext null
        val path = resolveStoredPath(artifact.storagePath)
        val isCurrentArtifact = runCatching {
            Files.isRegularFile(path) &&
                Files.size(path) == artifact.byteLength &&
                fileSha256(path).equals(artifact.sha256, ignoreCase = true)
        }.getOrDefault(false)
        if (!isCurrentArtifact) {
            localFiles.remove(manifestationId)
            runCatching { Files.deleteIfExists(path) }
            return@withContext null
        }
        toDownloadedPaper(artifact, path)
    }

    suspend fun deleteDownload(
        workId: WorkId,
        manifestationId: ManifestationId,
    ): DeleteDownloadResult = withContext(Dispatchers.IO) {
        requireManifestation(workId, manifestationId, requirePdfUrl = false)
        val task = tasks.find(TaskKind.DOWNLOAD, workId, manifestationId.value)
        if (task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
            return@withContext DeleteDownloadResult.ActiveTask
        }
        val artifact = localFiles.get(manifestationId) ?: return@withContext DeleteDownloadResult.NotFound
        val path = resolveStoredPath(artifact.storagePath)
        Files.deleteIfExists(path)
        localFiles.remove(manifestationId)
        if (task != null) tasks.removeTerminal(task.id)
        DeleteDownloadResult.Deleted
    }

    private suspend fun requireManifestation(
        workId: WorkId,
        manifestationId: ManifestationId,
        requirePdfUrl: Boolean = true,
    ): PaperManifestation {
        val paper = library.get(workId) ?: throw DownloadRequestException.PaperNotFound(workId)
        val manifestation = paper.manifestations.firstOrNull { it.id == manifestationId }
            ?: throw DownloadRequestException.ManifestationNotFound(manifestationId)
        if (requirePdfUrl && manifestation.pdfUrl.safeHttpUrlOrNull() == null) {
            throw DownloadRequestException.PdfUnavailable(manifestationId)
        }
        return manifestation
    }

    private suspend fun retryOrFail(
        running: PaperTask,
        failureCode: String,
        retryAfterMillis: Long?,
    ): DownloadExecutionResult {
        if (running.attempt >= maximumAttempts) {
            return failPermanently(running, "${failureCode}_RETRY_EXHAUSTED")
        }
        tasks.transition(running.id, TaskState.FAILED, failureCode)
        tasks.transition(running.id, TaskState.QUEUED)
        return DownloadExecutionResult.Retry(failureCode, retryAfterMillis)
    }

    private suspend fun failPermanently(
        running: PaperTask,
        failureCode: String,
    ): DownloadExecutionResult.Failed {
        tasks.transition(running.id, TaskState.FAILED, failureCode)
        return DownloadExecutionResult.Failed(failureCode)
    }

    private fun resolveStoredPath(storagePath: String): Path {
        val relative = Paths.get(storagePath)
        require(!relative.isAbsolute) { "Stored artifact path must be relative" }
        val resolved = filesRoot.resolve(relative).normalize()
        require(resolved.startsWith(papersRoot)) { "Stored artifact escaped the papers directory" }
        return resolved
    }

    private fun toDownloadedPaper(artifact: LocalPaperArtifact, path: Path) = DownloadedPaper(
        manifestationId = artifact.manifestationId,
        contentUri = contentUriForFile(path),
        sha256 = artifact.sha256,
        byteLength = artifact.byteLength,
    )

    private fun deleteReplacedFile(previous: LocalPaperArtifact?, current: Path) {
        if (previous == null) return
        val previousPath = resolveStoredPath(previous.storagePath)
        if (previousPath != current.toAbsolutePath().normalize()) {
            runCatching { Files.deleteIfExists(previousPath) }
        }
    }

    private suspend fun rollbackCancelledCommit(
        previous: LocalPaperArtifact?,
        committed: LocalPaperArtifact,
        downloadedPath: Path,
    ) {
        if (previous == null) {
            localFiles.remove(committed.manifestationId)
        } else {
            localFiles.upsert(previous)
        }
        val normalizedDownload = downloadedPath.toAbsolutePath().normalize()
        val previousPath = previous?.let { resolveStoredPath(it.storagePath) }
        if (previousPath != normalizedDownload) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(normalizedDownload) }
        }
    }

    companion object {
        private const val PAPERS_DIRECTORY = "papers"
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val FAILURE_TASK_NOT_FOUND = "TASK_NOT_FOUND"
        private const val FAILURE_INVALID_TASK = "INVALID_TASK"
        private const val FAILURE_INTERRUPTED = "INTERRUPTED"
        private const val FAILURE_PAPER_NOT_FOUND = "PAPER_NOT_FOUND"
        private const val FAILURE_MANIFESTATION_NOT_FOUND = "MANIFESTATION_NOT_FOUND"
        private const val FAILURE_PDF_UNAVAILABLE = "PDF_UNAVAILABLE"
        private const val FAILURE_TOO_LARGE = "PDF_TOO_LARGE"
        private const val FAILURE_INVALID_PDF = "INVALID_PDF"
        private const val FAILURE_IO = "IO"
        private const val FAILURE_UNEXPECTED = "UNEXPECTED"
    }
}

private fun String?.safeHttpUrlOrNull(): String? = this?.takeIf { value ->
    runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun fileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
