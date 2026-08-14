package dev.paperreader.app.extensions

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.paperreader.app.BuildConfig
import dev.paperreader.app.MainActivity
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.logic.plugin.VerifiedExtensionRelease
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SourceExtensionUpdateScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<SourceExtensionUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(30))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "source-extension-update-check"
        internal const val WORK_TAG = "source-extension-updates"
    }
}

class SourceExtensionUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? PaperReaderApplication ?: return Result.failure()
        return try {
            if (application.refreshExtensionCatalogs()) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal suspend fun PaperReaderApplication.refreshExtensionCatalogs(): Boolean {
    var successful = try {
        extensionStoreRegistry.ensurePinned(
            indexUrl = BuildConfig.OFFICIAL_SOURCE_STORE_URL,
            publicKeyBase64 = BuildConfig.OFFICIAL_SOURCE_STORE_PUBLIC_KEY,
            expectedStoreId = BuildConfig.OFFICIAL_SOURCE_STORE_ID,
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    successful = extensionStoreRegistry.refreshAll(setOf(BuildConfig.OFFICIAL_SOURCE_STORE_ID)) && successful
    reconcileInstalledExtensions()
    val sourceUpdates = logic.providers.state.value.available
        .filter { it.installedVersionCode != null && it.apkSha256 != null && it.apkSizeBytes != null }
        .map { ExtensionUpdateCandidate(it.packageName, it.versionCode) }
    val themeCatalog = themeExtensionManager.catalog.value
    val blockedThemePackages = themeCatalog.issues.mapTo(hashSetOf(), ThemeExtensionIssue::packageName)
    val installedThemeVersions = themeCatalog.installedVersions
    val themeUpdates = extensionStoreRegistry.trustedThemeReleases().mapNotNull { release ->
        if (release.packageName in blockedThemePackages) return@mapNotNull null
        if (release.apkSha256 == null || release.apkSizeBytes == null) return@mapNotNull null
        val installedVersion = installedThemeVersions[release.packageName] ?: return@mapNotNull null
        if (release.versionCode > installedVersion) {
            ExtensionUpdateCandidate(release.packageName, release.versionCode)
        } else {
            null
        }
    }
    extensionNotificationPublisher.publishUpdates(sourceUpdates + themeUpdates)
    return successful
}

internal suspend fun PaperReaderApplication.reconcileInstalledExtensions() {
    logic.reconcileSourceExtensions()
    themeExtensionManager.replaceStoreExtensions(
        extensionStoreRegistry.trustedThemeReleases().mapNotNull(VerifiedExtensionRelease::toTrustedThemeExtension),
    )
}

internal data class ExtensionUpdateCandidate(
    val packageName: String,
    val versionCode: Long,
)

class ExtensionNotificationPublisher(context: Context) {
    private val context = context.applicationContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("source-extension-notifications", Context.MODE_PRIVATE)

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.source_extension_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.source_extension_notification_channel_description)
            },
        )
    }

    internal fun publishUpdates(updates: List<ExtensionUpdateCandidate>): Boolean {
        val fingerprint = updates.sortedBy(ExtensionUpdateCandidate::packageName)
            .joinToString("|") { "${it.packageName}:${it.versionCode}" }
        if (fingerprint.isBlank()) {
            preferences.edit().remove(KEY_LAST_NOTIFIED).apply()
            return false
        }
        if (preferences.getString(KEY_LAST_NOTIFIED, null) == fingerprint || !canPost()) return false
        createChannel()
        val openApp = PendingIntent.getActivity(
            context,
            OPEN_EXTENSIONS_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_EXTENSIONS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_paper)
            .setContentTitle(context.getString(R.string.source_extension_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.source_extension_notification_body,
                    updates.size,
                    updates.size,
                ),
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
        preferences.edit().putString(KEY_LAST_NOTIFIED, fingerprint).apply()
        return true
    }

    private fun canPost(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = notificationManager.getNotificationChannel(CHANNEL_ID)?.importance
            ?.let { it != NotificationManager.IMPORTANCE_NONE } ?: true
        return permissionGranted && notificationManager.areNotificationsEnabled() && channelEnabled
    }

    companion object {
        const val ACTION_OPEN_EXTENSIONS = "dev.paperreader.app.action.OPEN_EXTENSIONS"
        private const val OPEN_EXTENSIONS_REQUEST_CODE = 2_002
        const val CHANNEL_ID = "source-extension-updates"
        const val NOTIFICATION_ID = 2_002
        const val KEY_LAST_NOTIFIED = "last_notified_releases"
    }
}
