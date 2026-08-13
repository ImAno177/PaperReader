package dev.paperreader.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DownloadWorkScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(taskId: TaskId) {
        workManager.enqueueUniqueWork(
            uniqueName(taskId),
            ExistingWorkPolicy.KEEP,
            request(taskId, initialDelayMillis = 0),
        )
    }

    suspend fun enqueueRetry(taskId: TaskId, initialDelayMillis: Long) {
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(
                uniqueName(taskId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(taskId, initialDelayMillis.coerceAtLeast(0)),
            ).result.get()
        }
    }

    suspend fun cancel(taskId: TaskId) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork(uniqueName(taskId)).result.get()
        }
    }

    suspend fun recover(logic: PaperReaderLogic) {
        logic.tasks.tasks.first()
            .filter { task ->
                task.kind == TaskKind.DOWNLOAD && task.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
            }
            .forEach { enqueue(it.id) }
    }

    private fun request(taskId: TaskId, initialDelayMillis: Long) =
        OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.TASK_ID_KEY to taskId.value))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(DOWNLOAD_TAG)
            .build()

    private fun uniqueName(taskId: TaskId) = "paper-download-${taskId.value}"

    private companion object {
        const val DOWNLOAD_TAG = "paper-download"
    }
}
