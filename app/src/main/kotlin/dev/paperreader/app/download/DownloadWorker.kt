package dev.paperreader.app.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.logic.task.DownloadExecutionResult
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskState
import kotlinx.coroutines.CancellationException

class DownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TASK_ID_KEY)?.takeIf(String::isNotBlank)?.let(::TaskId)
            ?: return Result.failure(workDataOf(FAILURE_CODE_KEY to "MISSING_TASK_ID"))
        val app = applicationContext as? PaperReaderApplication
            ?: return Result.failure(workDataOf(FAILURE_CODE_KEY to "INVALID_APPLICATION"))
        return try {
            when (val execution = app.logic.downloads.execute(taskId)) {
                is DownloadExecutionResult.Succeeded -> Result.success()
                DownloadExecutionResult.Cancelled -> Result.success()
                is DownloadExecutionResult.Failed -> Result.failure(
                    workDataOf(FAILURE_CODE_KEY to execution.failureCode),
                )
                is DownloadExecutionResult.Retry -> {
                    val retryAfter = execution.retryAfterMillis
                    if (retryAfter != null) {
                        if (app.logic.tasks.get(taskId)?.state == TaskState.QUEUED) {
                            app.downloadWorkScheduler.enqueueRetry(taskId, retryAfter)
                            // Cancellation may win after the pre-enqueue read; remove that stale delayed chain.
                            if (app.logic.tasks.get(taskId)?.state != TaskState.QUEUED) {
                                app.downloadWorkScheduler.cancel(taskId)
                            }
                        }
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            when (app.logic.tasks.get(taskId)?.state) {
                TaskState.CANCELLED, TaskState.SUCCEEDED -> Result.success()
                TaskState.FAILED -> Result.failure()
                TaskState.QUEUED, TaskState.RUNNING, null -> Result.retry()
            }
        }
    }

    companion object {
        const val TASK_ID_KEY = "task_id"
        const val FAILURE_CODE_KEY = "failure_code"
    }
}
