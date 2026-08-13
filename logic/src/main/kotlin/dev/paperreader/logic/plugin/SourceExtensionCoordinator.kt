package dev.paperreader.logic.plugin

import android.content.Context
import android.content.pm.PackageManager
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.provider.UntrustedProviderPlugin
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
        trustedExtensions().forEach { trusted ->
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
    )
}
