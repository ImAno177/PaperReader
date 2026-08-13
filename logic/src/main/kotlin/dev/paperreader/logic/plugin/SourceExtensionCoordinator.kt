package dev.paperreader.logic.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.OrphanedProviderPlugin
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.provider.UntrustedProviderPlugin
import dev.paperreader.extensions.api.PaperExtensionContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SourceExtensionCoordinator(
    context: Context,
    private val providers: MutableProviderManager,
    private val developerExtensions: List<TrustedSourceExtension>,
    private val stores: ExtensionStoreRegistry,
) {
    private val applicationContext = context.applicationContext
    private val reconcileMutex = Mutex()

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        reconcileMutex.withLock { reconcileNow() }
    }

    fun reconcileNow() {
        providers.unregisterByOrigin(ProviderOrigin.COMMUNITY_PLUGIN)
        val available = mutableListOf<AvailableProviderPlugin>()
        val untrusted = mutableListOf<UntrustedProviderPlugin>()
        val trustedExtensions = trustedExtensions()
        trustedExtensions.forEach { trusted ->
            if (!isInstalled(trusted.packageName)) {
                available += trusted.available(installedVersionCode = null)
                return@forEach
            }
            try {
                val transport = AndroidSourceExtensionTransport(
                    context = applicationContext,
                    descriptor = trusted.descriptor(),
                    trustedRelease = trusted,
                )
                val installedVersion = transport.verifyInstalledPackage()
                providers.register(
                    provider = CommunitySourceProvider(transport),
                    origin = ProviderOrigin.COMMUNITY_PLUGIN,
                    packageName = trusted.packageName,
                    versionCode = installedVersion,
                )
                if (installedVersion < trusted.versionCode) {
                    available += trusted.available(installedVersion)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                untrusted += UntrustedProviderPlugin(
                    packageName = trusted.packageName,
                    signerSha256 = trusted.signerSha256.lowercase(),
                    reason = error.message?.take(160)?.takeIf(String::isNotBlank)
                        ?: "Provider package failed trust validation",
                )
            }
        }
        providers.updateAvailable(available)
        providers.updateUntrusted(untrusted)
        providers.updateOrphaned(findOrphanedPackages(trustedExtensions.mapTo(hashSetOf(), TrustedSourceExtension::packageName)))
    }

    @Suppress("DEPRECATION")
    private fun findOrphanedPackages(trustedPackages: Set<String>): List<OrphanedProviderPlugin> {
        val packageManager = applicationContext.packageManager
        return packageManager.queryIntentServices(
            Intent(PaperExtensionContract.SOURCE_SERVICE_ACTION),
            PackageManager.GET_META_DATA,
        ).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            val packageName = service.packageName
            if (
                packageName in trustedPackages || !service.enabled || !service.exported ||
                service.metaData?.getInt(PaperExtensionContract.META_API_VERSION, -1) != PaperExtensionContract.API_VERSION ||
                service.metaData?.getString(PaperExtensionContract.META_EXTENSION_KIND) != PaperExtensionContract.EXTENSION_KIND_SOURCE
            ) return@mapNotNull null
            val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
                ?: return@mapNotNull null
            OrphanedProviderPlugin(
                packageName = packageName,
                displayName = resolved.loadLabel(packageManager).toString().ifBlank { packageName },
                versionCode = packageInfo.longVersionCode,
            )
        }.distinctBy(OrphanedProviderPlugin::packageName)
    }

    private fun trustedExtensions(): List<TrustedSourceExtension> {
        val developerPackages = developerExtensions.mapTo(mutableSetOf(), TrustedSourceExtension::packageName)
        return developerExtensions + stores.trustedSourceExtensions().filterNot { it.packageName in developerPackages }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        applicationContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun TrustedSourceExtension.available(installedVersionCode: Long?) = AvailableProviderPlugin(
        packageName = packageName,
        displayName = displayName,
        versionCode = versionCode,
        providerIds = setOf(providerId),
        versionName = versionName,
        installedVersionCode = installedVersionCode,
        installUrl = installUrl,
        serviceClassName = serviceClassName,
        signerSha256 = signerSha256,
        apkSha256 = apkSha256,
        apkSizeBytes = apkSizeBytes,
    )
}
