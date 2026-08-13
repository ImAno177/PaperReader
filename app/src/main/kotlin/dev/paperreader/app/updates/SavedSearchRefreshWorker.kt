package dev.paperreader.app.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.paperreader.app.PaperReaderApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SavedSearchRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PaperReaderApplication
            ?: return Result.failure(workDataOf(FAILURE_CODE_KEY to "INVALID_APPLICATION"))
        return try {
            if (!app.preferences.automaticSavedSearchRefreshEnabled.first()) {
                return Result.success()
            }
            val summary = app.logic.useCases.refreshAllSavedSearches.await()
            val stillEnabled = app.preferences.automaticSavedSearchRefreshEnabled.first()
            val notificationPosted = if (stillEnabled && summary.newlyUnread > 0) {
                app.savedSearchNotificationPublisher.publish(summary.newlyUnread)
            } else {
                false
            }
            Result.success(
                workDataOf(
                    REFRESHED_SEARCHES_KEY to summary.refreshedSearches,
                    SUCCEEDED_PROVIDERS_KEY to summary.succeededProviders,
                    FAILED_PROVIDERS_KEY to summary.failedProviders,
                    NEW_RESULTS_KEY to summary.newlyUnread,
                    NOTIFICATION_POSTED_KEY to notificationPosted,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount == 0) {
                Result.retry()
            } else {
                // Periodic work is scheduled again after this run; avoid a persistent retry storm.
                Result.failure(workDataOf(FAILURE_CODE_KEY to "UNEXPECTED_FAILURE"))
            }
        }
    }

    companion object {
        const val REFRESHED_SEARCHES_KEY = "refreshed_searches"
        const val SUCCEEDED_PROVIDERS_KEY = "succeeded_providers"
        const val FAILED_PROVIDERS_KEY = "failed_providers"
        const val NEW_RESULTS_KEY = "new_results"
        const val NOTIFICATION_POSTED_KEY = "notification_posted"
        const val FAILURE_CODE_KEY = "failure_code"
    }
}
