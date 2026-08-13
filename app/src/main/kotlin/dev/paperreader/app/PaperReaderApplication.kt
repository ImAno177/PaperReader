package dev.paperreader.app

import android.app.Application
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.extensions.CommunityThemeExtensionManager
import dev.paperreader.app.extensions.TrustedThemeExtension
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.logic.PaperReaderConfiguration
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.plugin.TrustedSourceExtension
import dev.paperreader.extensions.api.SourceCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class PaperReaderApplication : Application() {
    internal val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val readerWriteMutex = Mutex()
    val preferences: PaperReaderPreferences by lazy { PaperReaderPreferences(this) }
    val themeExtensionManager: CommunityThemeExtensionManager by lazy {
        CommunityThemeExtensionManager(this, developerThemeExtensions())
    }
    val downloadWorkScheduler: DownloadWorkScheduler by lazy { DownloadWorkScheduler(this) }
    val savedSearchNotificationPublisher: SavedSearchNotificationPublisher by lazy {
        SavedSearchNotificationPublisher(this)
    }
    val savedSearchRefreshScheduler: SavedSearchRefreshScheduler by lazy {
        SavedSearchRefreshScheduler(this, preferences)
    }

    val logic: PaperReaderLogic by lazy {
        PaperReaderLogic.open(
            context = this,
            configuration = PaperReaderConfiguration(
                userAgent = "PaperReader/0.1 (Android)",
                trustedSourceExtensions = developerSourceExtensions(),
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainApplicationProcess(Application.getProcessName(), packageName)) return
        savedSearchNotificationPublisher.createChannel()
        applicationIoScope.launch { themeExtensionManager.refresh() }
        applicationIoScope.launch { savedSearchRefreshScheduler.reconcile() }
    }
}

private fun developerThemeExtensions(): List<TrustedThemeExtension> {
    if (!BuildConfig.DEBUG || BuildConfig.DEV_THEME_SIGNER_SHA256.isBlank()) return emptyList()
    return listOf(
        TrustedThemeExtension(
            packageName = BuildConfig.DEV_THEME_PACKAGE,
            serviceClassName = BuildConfig.DEV_THEME_SERVICE,
            versionCode = BuildConfig.DEV_THEME_VERSION_CODE,
            signerSha256 = BuildConfig.DEV_THEME_SIGNER_SHA256,
            displayName = BuildConfig.DEV_THEME_DISPLAY_NAME,
            themeIds = setOf(BuildConfig.DEV_THEME_ID),
        ),
    )
}

private fun developerSourceExtensions(): List<TrustedSourceExtension> {
    if (!BuildConfig.DEBUG || BuildConfig.DEV_SOURCE_SIGNER_SHA256.isBlank()) return emptyList()
    return listOf(
        TrustedSourceExtension(
            packageName = BuildConfig.DEV_SOURCE_PACKAGE,
            serviceClassName = BuildConfig.DEV_SOURCE_SERVICE,
            versionCode = BuildConfig.DEV_SOURCE_VERSION_CODE,
            signerSha256 = BuildConfig.DEV_SOURCE_SIGNER_SHA256,
            providerId = BuildConfig.DEV_SOURCE_PROVIDER_ID,
            displayName = BuildConfig.DEV_SOURCE_DISPLAY_NAME,
            minimumRequestIntervalMillis = 1_000,
            capabilities = setOf(
                SourceCapability.SEARCH,
                SourceCapability.DETAILS,
                SourceCapability.PDF_LINK,
            ),
        ),
    )
}

internal fun isMainApplicationProcess(processName: String, packageName: String): Boolean =
    processName == packageName
