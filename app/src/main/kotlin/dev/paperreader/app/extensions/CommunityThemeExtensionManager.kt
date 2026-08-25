package dev.paperreader.app.extensions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.ThemeExtensionDescriptor
import dev.paperreader.logic.plugin.ExtensionReleaseKind
import dev.paperreader.logic.plugin.VerifiedExtensionRelease
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TrustedThemeExtension(
    val packageName: String,
    val serviceClassName: String,
    val versionCode: Long,
    val signerSha256: String,
    val displayName: String,
    val themeIds: Set<String>,
    val versionName: String? = null,
    val installUrl: String? = null,
    val minimumVersionCode: Long = versionCode,
) {
    init {
        require(packageName.contains('.'))
        require(serviceClassName.startsWith("$packageName."))
        require(versionCode > 0)
        require(signerSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(displayName.isNotBlank())
        require(themeIds.isNotEmpty())
        require(versionName == null || versionName.isNotBlank())
        installUrl?.let { rawUrl ->
            val uri = URI(rawUrl)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
        }
        require(minimumVersionCode in 1..versionCode)
    }

    internal fun descriptor() = ThemeExtensionDescriptor(
        packageName = packageName,
        displayName = displayName,
        themeIds = themeIds,
    )
}

data class ThemeExtensionIssue(
    val packageName: String,
    val message: String,
)

data class CommunityThemeCatalog(
    val loading: Boolean = true,
    val themes: List<CommunityPaperTheme> = emptyList(),
    val installedVersions: Map<String, Long> = emptyMap(),
    val issues: List<ThemeExtensionIssue> = emptyList(),
)

class CommunityThemeExtensionManager(
    context: Context,
    baselineExtensions: List<TrustedThemeExtension>,
) {
    private val applicationContext = context.applicationContext
    private val baselineExtensions = baselineExtensions.toList()
    private var storeExtensions = emptyList<TrustedThemeExtension>()
    private val mutableCatalog = MutableStateFlow(CommunityThemeCatalog())
    private val refreshMutex = Mutex()
    val catalog: StateFlow<CommunityThemeCatalog> = mutableCatalog.asStateFlow()

    suspend fun refresh() = refreshMutex.withLock { refreshLocked() }

    suspend fun replaceStoreExtensions(next: List<TrustedThemeExtension>) = refreshMutex.withLock {
        val baselinePackages = baselineExtensions.mapTo(mutableSetOf(), TrustedThemeExtension::packageName)
        val normalized = next
            .filterNot { it.packageName in baselinePackages }
            .distinctBy(TrustedThemeExtension::packageName)
        if (normalized == storeExtensions && !mutableCatalog.value.loading) return@withLock
        storeExtensions = normalized
        refreshLocked()
    }

    private suspend fun refreshLocked() = withContext(Dispatchers.IO) {
        mutableCatalog.value = mutableCatalog.value.copy(loading = true)
        val themes = mutableListOf<CommunityPaperTheme>()
        val issues = mutableListOf<ThemeExtensionIssue>()
        val trustedExtensions = baselineExtensions + storeExtensions
        val installedVersions = linkedMapOf<String, Long>()
        trustedExtensions.forEach { trusted ->
            val installedVersion = installedVersion(trusted.packageName) ?: return@forEach
            installedVersions[trusted.packageName] = installedVersion
            try {
                themes += AndroidThemeExtensionTransport(applicationContext, trusted).loadThemes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ThemeExtensionMinimumVersionException) {
                // The signed store update remains available while the revoked theme stays unloaded.
            } catch (error: Exception) {
                issues += ThemeExtensionIssue(
                    packageName = trusted.packageName,
                    message = error.message?.take(160)?.takeIf(String::isNotBlank)
                        ?: applicationContext.getString(R.string.theme_validation_failed),
                )
            }
        }
        val trustedPackages = trustedExtensions.mapTo(hashSetOf(), TrustedThemeExtension::packageName)
        discoverThemePackages()
            .filterNot { it in trustedPackages }
            .sorted()
            .forEach { packageName ->
                issues += ThemeExtensionIssue(
                    packageName = packageName,
                    message = applicationContext.getString(R.string.theme_not_in_trusted_store),
                )
            }
        mutableCatalog.value = CommunityThemeCatalog(
            loading = false,
            themes = themes.sortedBy(CommunityPaperTheme::displayName),
            installedVersions = installedVersions,
            issues = issues,
        )
    }

    private fun installedVersion(packageName: String): Long? = try {
        applicationContext.packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun discoverThemePackages(): Set<String> {
        val intent = Intent(PaperExtensionContract.THEME_SERVICE_ACTION)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            applicationContext.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        return services.mapNotNullTo(linkedSetOf()) { it.serviceInfo?.packageName }
    }

    fun theme(storageKey: String): CommunityPaperTheme? =
        mutableCatalog.value.themes.firstOrNull { it.storageKey == storageKey }
}

internal fun VerifiedExtensionRelease.toTrustedThemeExtension(): TrustedThemeExtension? {
    if (kind != ExtensionReleaseKind.THEME || !compatible) return null
    return TrustedThemeExtension(
        packageName = packageName,
        serviceClassName = serviceClassName,
        versionCode = versionCode,
        signerSha256 = signerSha256,
        displayName = displayName,
        themeIds = themeIds,
        versionName = versionName,
        installUrl = installUrl,
        minimumVersionCode = minimumVersionCode,
    )
}

internal class ThemeExtensionMinimumVersionException : Exception()
