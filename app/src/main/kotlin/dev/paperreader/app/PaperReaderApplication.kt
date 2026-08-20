package dev.paperreader.app

import android.app.Application
import android.webkit.WebView
import dev.paperreader.app.search.GOOGLE_SEARCH_DATA_SUFFIX
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.extensions.CommunityThemeExtensionManager
import dev.paperreader.app.extensions.ExtensionInstaller
import dev.paperreader.app.extensions.ExtensionNotificationPublisher
import dev.paperreader.app.extensions.SourceExtensionUpdateScheduler
import dev.paperreader.app.extensions.refreshExtensionCatalogs
import dev.paperreader.app.extensions.reconcileInstalledExtensions
import dev.paperreader.app.extensions.TrustedThemeExtension
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.logic.PaperReaderConfiguration
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.plugin.TrustedSourceExtension
import dev.paperreader.logic.plugin.ExtensionStoreRegistry
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class PaperReaderApplication : Application() {
    internal val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val readerWriteMutex = Mutex()
    val preferences: PaperReaderPreferences by lazy { PaperReaderPreferences(this) }
    val extensionStoreRegistry: ExtensionStoreRegistry by lazy {
        ExtensionStoreRegistry(
            directory = noBackupFilesDir.toPath().resolve("extension-stores"),
            userAgent = USER_AGENT,
        )
    }
    val themeExtensionManager: CommunityThemeExtensionManager by lazy {
        CommunityThemeExtensionManager(this, developerThemeExtensions())
    }
    val extensionInstaller: ExtensionInstaller by lazy {
        ExtensionInstaller(
            context = this,
            scope = applicationIoScope,
            onPackagesChanged = { reconcileInstalledExtensions() },
        )
    }
    val extensionNotificationPublisher: ExtensionNotificationPublisher by lazy {
        ExtensionNotificationPublisher(this)
    }
    val sourceExtensionUpdateScheduler: SourceExtensionUpdateScheduler by lazy {
        SourceExtensionUpdateScheduler(this)
    }
    val downloadWorkScheduler: DownloadWorkScheduler by lazy { DownloadWorkScheduler(this) }
    val savedSearchNotificationPublisher: SavedSearchNotificationPublisher by lazy {
        SavedSearchNotificationPublisher(this)
    }
    val savedSearchRefreshScheduler: SavedSearchRefreshScheduler by lazy {
        SavedSearchRefreshScheduler(this, preferences)
    }

    val logic by lazy {
        PaperReaderLogic.open(
            context = this,
            builtInProviders = emptyList(),
            configuration = PaperReaderConfiguration(
                userAgent = USER_AGENT,
                trustedSourceExtensions = developerSourceExtensions(),
                extensionStoreRegistry = extensionStoreRegistry,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        val processName = Application.getProcessName()
        if (processName == "$packageName:google_search") {
            WebView.setDataDirectorySuffix(GOOGLE_SEARCH_DATA_SUFFIX)
            return
        }
        if (!isMainApplicationProcess(processName, packageName)) return
        savedSearchNotificationPublisher.createChannel()
        extensionNotificationPublisher.createChannel()
        sourceExtensionUpdateScheduler.schedule()
        applicationIoScope.launch { themeExtensionManager.refresh() }
        applicationIoScope.launch { savedSearchRefreshScheduler.reconcile() }
        applicationIoScope.launch { reconcileInstalledExtensions() }
        applicationIoScope.launch {
            refreshExtensionCatalogs()
        }
    }
}

private const val USER_AGENT = "PaperReader/0.1 (Android; +https://github.com/ImAno177/PaperReader)"

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
            minimumRequestIntervalMillis = BuildConfig.DEV_SOURCE_MINIMUM_INTERVAL_MILLIS,
            capabilities = BuildConfig.DEV_SOURCE_CAPABILITIES.parseEnumSet<SourceCapability>(),
            roles = BuildConfig.DEV_SOURCE_ROLES.parseEnumSet<SourceRole>(),
            identifierLookupTypes = BuildConfig.DEV_SOURCE_IDENTIFIER_TYPES.parseEnumSet<SourceIdentifierType>(),
            supportedSorts = BuildConfig.DEV_SOURCE_SUPPORTED_SORTS.parseEnumSet<SourceSearchSort>(),
        ),
    )
}

private inline fun <reified T : Enum<T>> String.parseEnumSet(): Set<T> {
    val byWireName = enumValues<T>().associateBy { it.name.lowercase() }
    return split(',').mapTo(linkedSetOf()) { raw ->
        requireNotNull(byWireName[raw.trim().lowercase()]) { "Unknown ${T::class.simpleName}: $raw" }
    }
}

internal fun isMainApplicationProcess(processName: String, packageName: String): Boolean =
    processName == packageName
