package dev.paperreader.app.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.graphics.PathParser
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.extensions.api.CommunityTheme
import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.IPaperThemeCallback
import dev.paperreader.extensions.api.IPaperThemeService
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.ThemeExtensionDescriptor
import dev.paperreader.extensions.api.ThemeSemanticIcon
import dev.paperreader.extensions.api.requireValidIconPathData
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class TrustedThemeExtension(
    val packageName: String,
    val serviceClassName: String,
    val versionCode: Long,
    val signerSha256: String,
    val displayName: String,
    val themeIds: Set<String>,
) {
    init {
        require(packageName.contains('.'))
        require(serviceClassName.startsWith("$packageName."))
        require(versionCode > 0)
        require(signerSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(displayName.isNotBlank())
        require(themeIds.isNotEmpty())
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
    val issues: List<ThemeExtensionIssue> = emptyList(),
)

class CommunityThemeExtensionManager(
    context: Context,
    private val trustedExtensions: List<TrustedThemeExtension>,
) {
    private val applicationContext = context.applicationContext
    private val mutableCatalog = MutableStateFlow(CommunityThemeCatalog())
    private val refreshMutex = Mutex()
    val catalog: StateFlow<CommunityThemeCatalog> = mutableCatalog.asStateFlow()

    suspend fun refresh() = refreshMutex.withLock {
        mutableCatalog.value = mutableCatalog.value.copy(loading = true)
        val themes = mutableListOf<CommunityPaperTheme>()
        val issues = mutableListOf<ThemeExtensionIssue>()
        trustedExtensions.forEach { trusted ->
            try {
                themes += AndroidThemeExtensionTransport(applicationContext, trusted).loadThemes()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                issues += ThemeExtensionIssue(
                    packageName = trusted.packageName,
                    message = error.message?.take(160)?.takeIf(String::isNotBlank) ?: "Theme extension failed validation",
                )
            }
        }
        mutableCatalog.value = CommunityThemeCatalog(
            loading = false,
            themes = themes.sortedBy(CommunityPaperTheme::displayName),
            issues = issues,
        )
    }

    fun theme(storageKey: String): CommunityPaperTheme? =
        mutableCatalog.value.themes.firstOrNull { it.storageKey == storageKey }
}

private class AndroidThemeExtensionTransport(
    context: Context,
    private val trustedRelease: TrustedThemeExtension,
) {
    private val applicationContext = context.applicationContext
    private val component = ComponentName(trustedRelease.packageName, trustedRelease.serviceClassName)

    suspend fun loadThemes(): List<CommunityPaperTheme> = withTimeout(EXTENSION_TIMEOUT_MILLIS) {
        withContext(Dispatchers.IO) {
            verifyInstalledPackage()
            withService { service ->
                val descriptor = ThemeExtensionDescriptor.fromBundle(service.descriptor)
                require(descriptor == trustedRelease.descriptor()) { "Theme descriptor does not match the trusted index" }
                descriptor.themeIds.sorted().map { themeId ->
                    val theme = requestTheme(service, themeId)
                    require(theme.themeId == themeId) { "Theme response ID does not match the request" }
                    val iconPaths = ThemeSemanticIcon.entries.associate { semanticIcon ->
                        val pathData = requestIcon(service, themeId, semanticIcon)
                        requireNotNull(PathParser.createPathFromPathData(pathData)) { "Theme icon path is invalid" }
                        PaperIconKey.valueOf(semanticIcon.name) to pathData
                    }
                    CommunityPaperTheme(
                        packageName = trustedRelease.packageName,
                        definition = theme,
                        iconPaths = iconPaths,
                    )
                }
            }
        }
    }

    private suspend fun requestTheme(service: IPaperThemeService, themeId: String): CommunityTheme {
        val requestId = UUID.randomUUID().toString()
        val response = CompletableDeferred<Bundle>()
        val callback = object : IPaperThemeCallback.Stub() {
            override fun onTheme(theme: Bundle) {
                response.complete(theme)
            }

            override fun onIcon(requestId: String, icon: ParcelFileDescriptor) {
                icon.close()
                response.completeExceptionally(ThemeExtensionProtocolException("Unexpected icon response"))
            }

            override fun onFailure(failure: Bundle) {
                response.completeExceptionally(ThemeExtensionRequestException(ExtensionFailure.fromBundle(failure)))
            }
        }
        return try {
            service.getTheme(requestId, themeId, callback)
            val theme = CommunityTheme.fromBundle(withTimeout(REQUEST_TIMEOUT_MILLIS) { response.await() })
            require(theme.requestId == requestId) { "Theme response request ID does not match" }
            theme
        } catch (cancelled: CancellationException) {
            runCatching { service.cancel(requestId) }
            throw cancelled
        } catch (error: Exception) {
            runCatching { service.cancel(requestId) }
            throw error
        }
    }

    private suspend fun requestIcon(
        service: IPaperThemeService,
        themeId: String,
        semanticIcon: ThemeSemanticIcon,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val response = CompletableDeferred<ParcelFileDescriptor>()
        val callback = object : IPaperThemeCallback.Stub() {
            override fun onTheme(theme: Bundle) {
                response.completeExceptionally(ThemeExtensionProtocolException("Unexpected theme response"))
            }

            override fun onIcon(responseRequestId: String, icon: ParcelFileDescriptor) {
                if (responseRequestId != requestId) {
                    icon.close()
                    response.completeExceptionally(ThemeExtensionProtocolException("Icon response request ID does not match"))
                } else if (!response.complete(icon)) {
                    icon.close()
                }
            }

            override fun onFailure(failure: Bundle) {
                response.completeExceptionally(ThemeExtensionRequestException(ExtensionFailure.fromBundle(failure)))
            }
        }
        return try {
            service.openIcon(requestId, themeId, semanticIcon.wireValue, callback)
            val descriptor = withTimeout(REQUEST_TIMEOUT_MILLIS) { response.await() }
            val bytes = descriptor.use(::readBoundedIcon)
            requireValidIconPathData(bytes)
        } catch (cancelled: CancellationException) {
            runCatching { service.cancel(requestId) }
            throw cancelled
        } catch (error: Exception) {
            runCatching { service.cancel(requestId) }
            throw error
        }
    }

    private suspend fun <T> withService(block: suspend (IPaperThemeService) -> T): T {
        val service = bind()
        return try {
            block(service)
        } finally {
            unbind()
        }
    }

    private var activeConnection: ServiceConnection? = null

    private suspend fun bind(): IPaperThemeService = withTimeout(BIND_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val closed = AtomicBoolean(false)
            lateinit var connection: ServiceConnection
            fun closeConnection() {
                if (!closed.compareAndSet(false, true)) return
                if (activeConnection === connection) activeConnection = null
                runCatching { applicationContext.unbindService(connection) }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (closed.get()) return
                    val service = IPaperThemeService.Stub.asInterface(binder)
                    if (service == null) {
                        closeConnection()
                        if (continuation.isActive) {
                            continuation.resumeWithException(ThemeExtensionProtocolException("Theme Binder is invalid"))
                        }
                    } else {
                        activeConnection = connection
                        continuation.resume(service)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onBindingDied(name: ComponentName) {
                    closeConnection()
                    if (continuation.isActive) {
                        continuation.resumeWithException(ThemeExtensionProtocolException("Theme binding died"))
                    }
                }

                override fun onNullBinding(name: ComponentName) {
                    closeConnection()
                    if (continuation.isActive) {
                        continuation.resumeWithException(ThemeExtensionProtocolException("Theme returned a null binding"))
                    }
                }
            }
            continuation.invokeOnCancellation { closeConnection() }
            val flags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Context.BIND_INCLUDE_CAPABILITIES
            } else {
                0
            }
            val intent = Intent(PaperExtensionContract.THEME_SERVICE_ACTION).setComponent(component)
            val didBind = runCatching { applicationContext.bindService(intent, connection, flags) }.getOrDefault(false)
            if (!didBind) {
                closeConnection()
                if (continuation.isActive) {
                    continuation.resumeWithException(ThemeExtensionProtocolException("Unable to bind theme extension"))
                }
            }
        }
    }

    private fun unbind() {
        val connection = activeConnection ?: return
        activeConnection = null
        runCatching { applicationContext.unbindService(connection) }
    }

    @Suppress("DEPRECATION")
    private fun verifyInstalledPackage() {
        val packageManager = applicationContext.packageManager
        val packageInfo = packageManager.getPackageInfo(
            trustedRelease.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(packageInfo.longVersionCode == trustedRelease.versionCode) { "Theme package version is not trusted" }
        require(
            packageManager.hasSigningCertificate(
                trustedRelease.packageName,
                trustedRelease.signerSha256.hexToBytes(),
                PackageManager.CERT_INPUT_SHA256,
            ),
        ) { "Theme package signer is not trusted" }
        val serviceInfo = packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        require(serviceInfo.enabled && serviceInfo.exported) { "Theme service is not enabled and exported" }
        require(serviceInfo.applicationInfo?.uid != applicationContext.applicationInfo.uid) {
            "Theme extension must run under a separate UID"
        }
        require(
            serviceInfo.metaData?.getInt(PaperExtensionContract.META_API_VERSION, -1) ==
                PaperExtensionContract.API_VERSION,
        ) { "Theme service API version is incompatible" }
        require(
            serviceInfo.metaData?.getString(PaperExtensionContract.META_EXTENSION_KIND) ==
                PaperExtensionContract.EXTENSION_KIND_THEME,
        ) { "Theme service kind is invalid" }
    }

    private fun readBoundedIcon(descriptor: ParcelFileDescriptor): ByteArray {
        val output = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= PaperExtensionContract.MAX_ICON_BYTES) { "Theme icon is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val BIND_TIMEOUT_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 5_000L
        const val EXTENSION_TIMEOUT_MILLIS = 30_000L
    }
}

private class ThemeExtensionRequestException(failure: ExtensionFailure) : Exception(failure.message)

private class ThemeExtensionProtocolException(message: String) : Exception(message)
