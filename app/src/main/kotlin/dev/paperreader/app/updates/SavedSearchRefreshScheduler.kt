package dev.paperreader.app.updates

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.paperreader.app.settings.PaperReaderPreferences
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SavedSearchRefreshScheduler(
    context: Context,
    private val preferences: PaperReaderPreferences,
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val mutationMutex = Mutex()

    suspend fun setEnabled(enabled: Boolean): Boolean = mutationMutex.withLock {
        val previous = preferences.automaticSavedSearchRefreshEnabled.first()
        if (previous == enabled) {
            return@withLock try {
                apply(enabled)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        preferences.setAutomaticSavedSearchRefreshEnabled(enabled)
        try {
            apply(enabled)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            preferences.setAutomaticSavedSearchRefreshEnabled(previous)
            try {
                apply(previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup reconciliation retries the restored preference on the next process launch.
            }
            false
        }
    }

    suspend fun reconcile(): Boolean = mutationMutex.withLock {
        try {
            apply(preferences.automaticSavedSearchRefreshEnabled.first())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun apply(enabled: Boolean) = withContext(Dispatchers.IO) {
        val operation = if (enabled) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest(),
            )
        } else {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
        operation.result.get()
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "saved-search-periodic-refresh"
        internal const val WORK_TAG = "saved-search-refresh"
        internal const val REPEAT_INTERVAL_HOURS = 24L

        internal fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SavedSearchRefreshWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(30))
                .addTag(WORK_TAG)
                .build()
    }
}
