package dev.paperreader.app.extensions

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.plugin.ExtensionReleaseKind
import dev.paperreader.logic.plugin.VerifiedExtensionRelease
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SourceExtensionInstallState {
    data object Pending : SourceExtensionInstallState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : SourceExtensionInstallState
    data object Installing : SourceExtensionInstallState
    data object AwaitingConfirmation : SourceExtensionInstallState
    data object Installed : SourceExtensionInstallState
    data class Failed(val message: String) : SourceExtensionInstallState
    data object Cancelled : SourceExtensionInstallState
}

/**
 * Serializes source APK installs and refuses bytes that are not bound to a verified store release.
 * Android PackageInstaller remains the authority for the final user confirmation and install result.
 */
class SourceExtensionInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val onPackagesChanged: suspend () -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val queue = Channel<AvailableProviderPlugin>(capacity = Channel.UNLIMITED)
    private val queuedPackages = ConcurrentHashMap.newKeySet<String>()
    private val mutableStates = MutableStateFlow<Map<String, SourceExtensionInstallState>>(emptyMap())
    val states: StateFlow<Map<String, SourceExtensionInstallState>> = mutableStates.asStateFlow()

    init {
        scope.launch {
            for (release in queue) {
                try {
                    installVerifiedRelease(release)
                } catch (error: Exception) {
                    setState(
                        release.packageName,
                        SourceExtensionInstallState.Failed(
                            error.message?.take(200)?.takeIf(String::isNotBlank)
                                ?: "Extension installation failed",
                        ),
                    )
                } finally {
                    queuedPackages.remove(release.packageName)
                }
            }
        }
    }

    fun enqueue(release: AvailableProviderPlugin) {
        if (!queuedPackages.add(release.packageName)) return
        setState(release.packageName, SourceExtensionInstallState.Pending)
        if (queue.trySend(release).isFailure) {
            queuedPackages.remove(release.packageName)
            setState(release.packageName, SourceExtensionInstallState.Failed("Installer queue is unavailable"))
        }
    }

    fun enqueue(release: VerifiedExtensionRelease) {
        if (release.kind != ExtensionReleaseKind.SOURCE || !release.compatible || release.providerId == null) {
            setState(release.packageName, SourceExtensionInstallState.Failed("This is not a compatible source extension"))
            return
        }
        enqueue(
            AvailableProviderPlugin(
                packageName = release.packageName,
                displayName = release.displayName,
                versionCode = release.versionCode,
                providerIds = setOf(requireNotNull(release.providerId)),
                versionName = release.versionName,
                installUrl = release.installUrl,
                serviceClassName = release.serviceClassName,
                signerSha256 = release.signerSha256,
                apkSha256 = release.apkSha256,
                apkSizeBytes = release.apkSizeBytes,
            ),
        )
    }

    fun dismiss(packageName: String) {
        val current = mutableStates.value[packageName]
        if (current is SourceExtensionInstallState.Failed ||
            current is SourceExtensionInstallState.Cancelled ||
            current is SourceExtensionInstallState.Installed
        ) {
            mutableStates.update { it - packageName }
        }
    }

    fun handleInstallerStatus(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                setState(packageName, SourceExtensionInstallState.AwaitingConfirmation)
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) {
                    setState(packageName, SourceExtensionInstallState.Failed("Android did not provide an install confirmation"))
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { applicationContext.startActivity(confirmation) }
                        .onFailure {
                            setState(
                                packageName,
                                SourceExtensionInstallState.Failed("Android install confirmation could not be opened"),
                            )
                        }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                setState(packageName, SourceExtensionInstallState.Installed)
                scope.launch { onPackagesChanged() }
            }

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                setState(packageName, SourceExtensionInstallState.Cancelled)

            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.take(160)
                    ?.takeIf(String::isNotBlank)
                setState(
                    packageName,
                    SourceExtensionInstallState.Failed(detail ?: "Android rejected the extension installation"),
                )
            }
        }
    }

    fun handlePackageChanged(packageName: String?) {
        if (packageName != null && mutableStates.value.containsKey(packageName)) {
            setState(packageName, SourceExtensionInstallState.Installed)
        }
        scope.launch { onPackagesChanged() }
    }

    private suspend fun installVerifiedRelease(release: AvailableProviderPlugin) = withContext(Dispatchers.IO) {
        val installUrl = requireNotNull(release.installUrl) { "The signed release has no install URL" }
        val expectedHash = requireNotNull(release.apkSha256) { "The signed release has no APK SHA-256" }
        val expectedSize = requireNotNull(release.apkSizeBytes) { "The signed release has no APK byte size" }
        val serviceClassName = requireNotNull(release.serviceClassName) { "The signed release has no service class" }
        val signerSha256 = requireNotNull(release.signerSha256) { "The signed release has no signer fingerprint" }
        require(expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) { "The signed APK hash is invalid" }
        require(expectedSize in 1..MAX_APK_BYTES) { "The signed APK size is invalid" }

        val cacheDirectory = File(applicationContext.cacheDir, "source-extension-apks").apply { mkdirs() }
        val target = File(cacheDirectory, "${release.packageName}-${release.versionCode}.apk")
        val temporary = File(cacheDirectory, "${target.name}.part")
        temporary.delete()
        target.delete()
        try {
            download(
                rawUrl = installUrl,
                destination = temporary,
                expectedSize = expectedSize,
                onProgress = { read ->
                    setState(release.packageName, SourceExtensionInstallState.Downloading(read, expectedSize))
                },
            )
            verifyDownloadedApk(temporary, expectedHash, expectedSize)
            require(temporary.renameTo(target)) { "Verified APK could not be published to the install cache" }
            preflight(
                apk = target,
                expectedPackage = release.packageName,
                expectedService = serviceClassName,
                expectedVersionCode = release.versionCode,
                expectedSignerSha256 = signerSha256,
            )
            commitPackageInstallerSession(release.packageName, target)
        } finally {
            temporary.delete()
            target.delete()
        }
    }

    private fun download(
        rawUrl: String,
        destination: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ) {
        var current = URI(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireAllowedDownloadUri(current)
            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PaperReader/0.1 (Android)")
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }
            try {
                when (connection.responseCode) {
                    in 300..399 -> {
                        require(redirectCount < MAX_REDIRECTS) { "Extension download redirected too many times" }
                        val location = connection.getHeaderField("Location")
                            ?.takeIf(String::isNotBlank)
                            ?: error("Extension download redirect has no location")
                        current = current.resolve(location)
                    }

                    HttpURLConnection.HTTP_OK -> {
                        val contentLength = connection.contentLengthLong
                        require(contentLength < 0 || contentLength == expectedSize) {
                            "Extension download length does not match the signed registry"
                        }
                        connection.inputStream.buffered().use { input ->
                            FileOutputStream(destination).buffered().use { output ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= expectedSize) { "Extension download exceeded its signed size" }
                                    output.write(buffer, 0, count)
                                    onProgress(total)
                                }
                                output.flush()
                                require(total == expectedSize) { "Extension download ended before its signed size" }
                            }
                        }
                        return
                    }

                    else -> error("Extension download failed with HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Extension download failed")
    }

    private fun preflight(
        apk: File,
        expectedPackage: String,
        expectedService: String,
        expectedVersionCode: Long,
        expectedSignerSha256: String,
    ) {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        val archive = getPackageArchiveInfo(apk, flags) ?: error("Downloaded file is not a readable Android package")
        require(archive.packageName == expectedPackage) { "APK package name does not match the signed registry" }
        require(archive.longVersionCode == expectedVersionCode) { "APK version does not match the signed registry" }
        require(archive.signerFingerprints().any { it.equals(expectedSignerSha256, ignoreCase = true) }) {
            "APK signing certificate does not match the signed registry"
        }
        val service = archive.services.orEmpty().singleOrNull { it.name == expectedService }
            ?: error("APK does not contain the signed source service")
        require(service.exported) { "Source service is not exported" }
        require(service.metaData?.getInt(PaperExtensionContract.META_API_VERSION, -1) == PaperExtensionContract.API_VERSION) {
            "Source service API version is incompatible"
        }
        require(service.metaData?.getString(PaperExtensionContract.META_EXTENSION_KIND) == PaperExtensionContract.EXTENSION_KIND_SOURCE) {
            "APK is not a source extension"
        }
    }

    private fun commitPackageInstallerSession(packageName: String, apk: File) {
        val installer = applicationContext.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(parameters)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("extension.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(applicationContext, SourceExtensionInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    applicationContext,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                setState(packageName, SourceExtensionInstallState.Installing)
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageArchiveInfo(apk: File, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            applicationContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }

    private fun PackageInfo.signerFingerprints(): Set<String> {
        val verifiedSigningInfo = requireNotNull(signingInfo) { "APK has no signing information" }
        val certificates = if (verifiedSigningInfo.hasMultipleSigners()) {
            verifiedSigningInfo.apkContentsSigners
        } else {
            verifiedSigningInfo.signingCertificateHistory
        }
        return certificates.mapTo(linkedSetOf()) { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).toHex()
        }
    }

    private fun setState(packageName: String, state: SourceExtensionInstallState) {
        mutableStates.update { it + (packageName to state) }
    }
}

class SourceExtensionInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? PaperReaderApplication ?: return
        application.sourceExtensionInstaller.handleInstallerStatus(intent)
    }
}

class SourceExtensionPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? PaperReaderApplication ?: return
        application.sourceExtensionInstaller.handlePackageChanged(intent.data?.schemeSpecificPart)
    }
}

internal fun requireAllowedDownloadUri(
    uri: URI,
    resolveHost: (String) -> List<InetAddress> = ::resolveHostAddresses,
) {
    require(uri.scheme.equals("https", ignoreCase = true)) { "Extension APK URL must use HTTPS" }
    require(uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null) {
        "Extension APK URL contains forbidden authority data"
    }
    val host = requireNotNull(uri.host).lowercase()
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
        "Extension APK host is local"
    }
    val addresses = runCatching { resolveHost(host) }
        .getOrElse { throw IllegalArgumentException("Extension APK host could not be resolved", it) }
    require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
        "Extension APK host does not resolve to a public address"
    }
}

private fun resolveHostAddresses(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return false
    }
    val bytes = address.address.map(Byte::toInt).map { it and 0xff }
    return when (address) {
        is Inet4Address -> when {
            bytes[0] == 0 || bytes[0] == 127 || bytes[0] >= 224 -> false
            bytes[0] == 100 && bytes[1] in 64..127 -> false
            bytes[0] == 192 && bytes[1] == 0 && bytes[2] in setOf(0, 2) -> false
            bytes[0] == 198 && (bytes[1] in 18..19 || bytes[1] == 51 && bytes[2] == 100) -> false
            bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 -> false
            else -> true
        }
        is Inet6Address -> {
            val uniqueLocal = (bytes[0] and 0xfe) == 0xfc
            val documentation = bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)
            !uniqueLocal && !documentation
        }
        else -> false
    }
}

internal fun verifyDownloadedApk(file: File, expectedHash: String, expectedSize: Long) {
    require(file.length() == expectedSize) { "Downloaded APK size does not match the signed registry" }
    require(sha256(file).equals(expectedHash, ignoreCase = true)) {
        "Downloaded APK SHA-256 does not match the signed registry"
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private const val ACTION_INSTALL_STATUS = "dev.paperreader.app.action.SOURCE_EXTENSION_INSTALL_STATUS"
private const val EXTRA_PACKAGE_NAME = "source_extension_package"
private const val MAX_APK_BYTES = 100L * 1024L * 1024L
private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 60_000
private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
