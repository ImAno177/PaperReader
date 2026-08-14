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
import dev.paperreader.logic.plugin.ExtensionReleaseKind
import dev.paperreader.logic.plugin.VerifiedExtensionRelease
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExtensionInstallState {
    data object Pending : ExtensionInstallState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ExtensionInstallState
    data object Installing : ExtensionInstallState
    data object AwaitingConfirmation : ExtensionInstallState
    data object Installed : ExtensionInstallState
    data class Failed(val message: String) : ExtensionInstallState
    data object Cancelled : ExtensionInstallState
}

/**
 * Serializes source and theme APK installs and refuses bytes not bound to a verified store release.
 * Android PackageInstaller remains the authority for the final user confirmation and install result.
 */
class ExtensionInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val onPackagesChanged: suspend () -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val sessionPreferences = applicationContext.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
    private val packageInstaller = applicationContext.packageManager.packageInstaller
    private val sessionCommitLock = Any()
    private val queue = Channel<InstallRequest>(capacity = Channel.UNLIMITED)
    private val queuedPackages = ConcurrentHashMap.newKeySet<String>()
    private val cancelledPackages = ConcurrentHashMap.newKeySet<String>()
    private val restoredSessions = readPersistedSessions()
    private val activeSessions = ConcurrentHashMap<String, ActiveInstallSession>(restoredSessions)
    private val downloader = VerifiedExtensionApkDownloader()
    private val mutableStates = MutableStateFlow<Map<String, ExtensionInstallState>>(
        restoredSessions.keys.associateWith { ExtensionInstallState.Installing },
    )
    val states: StateFlow<Map<String, ExtensionInstallState>> = mutableStates.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reconcileRestoredSessions(restoredSessions) }
        scope.launch {
            for (release in queue) {
                try {
                    checkNotCancelled(release.packageName)
                    installVerifiedRelease(release)
                } catch (_: UserInstallCancellationException) {
                    setState(release.packageName, ExtensionInstallState.Cancelled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (mutableStates.value[release.packageName] !is ExtensionInstallState.Cancelled) {
                        setState(
                            release.packageName,
                            ExtensionInstallState.Failed(
                                error.message?.take(200)?.takeIf(String::isNotBlank)
                                    ?: "Extension installation failed",
                            ),
                        )
                    }
                } finally {
                    queuedPackages.remove(release.packageName)
                    cancelledPackages.remove(release.packageName)
                }
            }
        }
    }

    private fun enqueue(release: InstallRequest) {
        if (!queuedPackages.add(release.packageName)) return
        cancelledPackages.remove(release.packageName)
        setState(release.packageName, ExtensionInstallState.Pending)
        if (queue.trySend(release).isFailure) {
            queuedPackages.remove(release.packageName)
            setState(release.packageName, ExtensionInstallState.Failed("Installer queue is unavailable"))
        }
    }

    fun enqueue(release: VerifiedExtensionRelease) {
        val request = runCatching { release.installRequest() }.getOrElse { error ->
            setState(
                release.packageName,
                ExtensionInstallState.Failed(
                    error.message?.take(200)?.takeIf(String::isNotBlank)
                        ?: "This extension cannot be installed",
                ),
            )
            return
        }
        enqueue(request)
    }

    fun dismiss(packageName: String) {
        val current = mutableStates.value[packageName]
        if (current is ExtensionInstallState.Failed ||
            current is ExtensionInstallState.Cancelled ||
            current is ExtensionInstallState.Installed
        ) {
            mutableStates.update { it - packageName }
        }
    }

    fun cancelOrDismiss(packageName: String) {
        when (mutableStates.value[packageName]) {
            ExtensionInstallState.Pending,
            is ExtensionInstallState.Downloading,
            ExtensionInstallState.Installing,
            ExtensionInstallState.AwaitingConfirmation,
            -> cancel(packageName)
            else -> dismiss(packageName)
        }
    }

    private fun cancel(packageName: String) {
        synchronized(sessionCommitLock) {
            cancelledPackages += packageName
            if (!abandonSessionLocked(packageName)) {
                cancelledPackages.remove(packageName)
                setState(
                    packageName,
                    ExtensionInstallState.Failed("Android still owns this install; cancel it in the system confirmation"),
                )
                return
            }
            setState(packageName, ExtensionInstallState.Cancelled)
        }
        downloader.cancel(packageName)
    }

    fun handleInstallerStatus(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val callbackSessionId = intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, INVALID_SESSION_ID)
        if (!intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID)) return
        val platformSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, INVALID_SESSION_ID)
        synchronized(sessionCommitLock) {
            val activeSession = activeSessions[packageName] ?: return
            if (!isCurrentInstallSession(activeSession.sessionId, callbackSessionId, platformSessionId)) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                forgetSessionLocked(packageName, activeSession.sessionId)
                cancelledPackages.remove(packageName)
            }
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (packageName in cancelledPackages) {
                        abandonSessionLocked(packageName)
                        setState(packageName, ExtensionInstallState.Cancelled)
                        return
                    }
                    setState(packageName, ExtensionInstallState.AwaitingConfirmation)
                    val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirmation == null) {
                        failPendingSessionLocked(packageName, "Android did not provide an install confirmation")
                    } else {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { applicationContext.startActivity(confirmation) }
                            .onFailure {
                                failPendingSessionLocked(packageName, "Android install confirmation could not be opened")
                            }
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    setState(packageName, ExtensionInstallState.Installed)
                    scope.launch { onPackagesChanged() }
                }

                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    setState(packageName, ExtensionInstallState.Cancelled)

                else -> {
                    val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?.take(160)
                        ?.takeIf(String::isNotBlank)
                    setState(
                        packageName,
                        ExtensionInstallState.Failed(detail ?: "Android rejected the extension installation"),
                    )
                }
            }
        }
    }

    fun handlePackageChanged(packageName: String?, installed: Boolean) {
        if (!installed && packageName != null) {
            synchronized(sessionCommitLock) {
                activeSessions[packageName]?.let { forgetSessionLocked(packageName, it.sessionId) }
            }
            cancelledPackages.remove(packageName)
            mutableStates.update { it - packageName }
        }
        scope.launch { onPackagesChanged() }
    }

    private suspend fun installVerifiedRelease(release: InstallRequest) = withContext(Dispatchers.IO) {
        checkNotCancelled(release.packageName)
        val installUrl = release.installUrl
        val expectedHash = release.apkSha256
        val expectedSize = release.apkSizeBytes
        val serviceClassName = release.serviceClassName
        val signerSha256 = release.signerSha256
        require(expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) { "The signed APK hash is invalid" }
        require(expectedSize in 1..MAX_APK_BYTES) { "The signed APK size is invalid" }

        val cacheDirectory = File(applicationContext.cacheDir, "extension-apks").apply { mkdirs() }
        val target = File(cacheDirectory, "${release.packageName}-${release.versionCode}.apk")
        val temporary = File(cacheDirectory, "${target.name}.part")
        temporary.delete()
        target.delete()
        try {
            downloader.download(
                packageName = release.packageName,
                rawUrl = installUrl,
                destination = temporary,
                expectedSize = expectedSize,
                checkCancelled = { checkNotCancelled(release.packageName) },
                onProgress = { read ->
                    setState(release.packageName, ExtensionInstallState.Downloading(read, expectedSize))
                },
            )
            verifyDownloadedApk(temporary, expectedHash, expectedSize)
            checkNotCancelled(release.packageName)
            require(temporary.renameTo(target)) { "Verified APK could not be published to the install cache" }
            preflight(
                apk = target,
                expectedPackage = release.packageName,
                expectedService = serviceClassName,
                expectedVersionCode = release.versionCode,
                expectedSignerSha256 = signerSha256,
                expectedKind = release.kind,
            )
            checkNotCancelled(release.packageName)
            commitPackageInstallerSession(release.packageName, release.versionCode, target)
        } finally {
            temporary.delete()
            target.delete()
        }
    }

    private fun preflight(
        apk: File,
        expectedPackage: String,
        expectedService: String,
        expectedVersionCode: Long,
        expectedSignerSha256: String,
        expectedKind: ExtensionReleaseKind,
    ) {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
        val archive = getPackageArchiveInfo(apk, flags) ?: error("Downloaded file is not a readable Android package")
        require(archive.packageName == expectedPackage) { "APK package name does not match the signed registry" }
        require(archive.longVersionCode == expectedVersionCode) { "APK version does not match the signed registry" }
        require(archive.signerFingerprints().any { it.equals(expectedSignerSha256, ignoreCase = true) }) {
            "APK signing certificate does not match the signed registry"
        }
        val service = archive.services.orEmpty().singleOrNull { it.name == expectedService }
            ?: error("APK does not contain the signed extension service")
        require(service.exported) { "Extension service is not exported" }
        require(service.metaData?.getInt(PaperExtensionContract.META_API_VERSION, -1) == PaperExtensionContract.API_VERSION) {
            "Extension service API version is incompatible"
        }
        val expectedKindValue = when (expectedKind) {
            ExtensionReleaseKind.SOURCE -> PaperExtensionContract.EXTENSION_KIND_SOURCE
            ExtensionReleaseKind.THEME -> PaperExtensionContract.EXTENSION_KIND_THEME
        }
        require(service.metaData?.getString(PaperExtensionContract.META_EXTENSION_KIND) == expectedKindValue) {
            "APK extension kind does not match the signed registry"
        }
    }

    private fun commitPackageInstallerSession(packageName: String, versionCode: Long, apk: File) {
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = packageInstaller.createSession(parameters)
        try {
            synchronized(sessionCommitLock) {
                checkNotCancelled(packageName)
                rememberSessionLocked(packageName, sessionId, versionCode)
            }
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("extension.apk", 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(applicationContext, ExtensionInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra(EXTRA_INSTALL_SESSION_ID, sessionId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    applicationContext,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                synchronized(sessionCommitLock) {
                    checkNotCancelled(packageName)
                    setState(packageName, ExtensionInstallState.Installing)
                    session.commit(pendingIntent.intentSender)
                }
            }
        } catch (error: Exception) {
            synchronized(sessionCommitLock) {
                forgetSessionLocked(packageName, sessionId)
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
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
        val certificates = verifiedSigningInfo.apkContentsSigners
        return certificates.mapTo(linkedSetOf()) { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).toHex()
        }
    }

    private fun setState(packageName: String, state: ExtensionInstallState) {
        mutableStates.update { it + (packageName to state) }
    }

    private fun checkNotCancelled(packageName: String) {
        if (packageName in cancelledPackages) throw UserInstallCancellationException()
    }

    private fun abandonSessionLocked(packageName: String): Boolean {
        val activeSession = activeSessions[packageName] ?: return true
        val abandoned = runCatching { packageInstaller.abandonSession(activeSession.sessionId) }.isSuccess
        if (abandoned) forgetSessionLocked(packageName, activeSession.sessionId)
        return abandoned
    }

    private fun rememberSessionLocked(packageName: String, sessionId: Int, versionCode: Long) {
        val session = ActiveInstallSession(sessionId, versionCode)
        check(sessionPreferences.edit().putString(packageName, session.encode()).commit()) {
            "Install session could not be persisted"
        }
        activeSessions[packageName] = session
    }

    private fun forgetSessionLocked(packageName: String, sessionId: Int) {
        val activeSession = activeSessions[packageName]
        if (activeSession?.sessionId == sessionId) {
            activeSessions.remove(packageName)
            sessionPreferences.edit().remove(packageName).commit()
        }
    }

    private fun failPendingSessionLocked(packageName: String, message: String) {
        abandonSessionLocked(packageName)
        setState(packageName, ExtensionInstallState.Failed(message))
    }

    private fun readPersistedSessions(): Map<String, ActiveInstallSession> = sessionPreferences.all.mapNotNull {
        (packageName, encoded) ->
        val session = (encoded as? String)?.let(ActiveInstallSession::decode)
        if (packageName.matches(EXTENSION_PACKAGE_REGEX) && session != null) packageName to session else null
    }.toMap()

    private suspend fun reconcileRestoredSessions(sessions: Map<String, ActiveInstallSession>) {
        var packagesChanged = false
        sessions.forEach { (packageName, session) ->
            val sessionInfo = try {
                packageInstaller.getSessionInfo(session.sessionId)
            } catch (_: Exception) {
                return@forEach
            }
            if (sessionInfo != null) return@forEach
            val installedVersion = try {
                installedVersionCode(packageName)
            } catch (_: Exception) {
                return@forEach
            }
            synchronized(sessionCommitLock) {
                if (activeSessions[packageName] != session) return@synchronized
                forgetSessionLocked(packageName, session.sessionId)
                setState(packageName, recoveredInstallState(installedVersion, session.versionCode))
                packagesChanged = true
            }
        }
        if (packagesChanged) onPackagesChanged()
    }

    private fun installedVersionCode(packageName: String): Long? = try {
        applicationContext.packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

private class UserInstallCancellationException : CancellationException("Extension installation cancelled")

class ExtensionInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? PaperReaderApplication ?: return
        application.extensionInstaller.handleInstallerStatus(intent)
    }
}

class ExtensionPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? PaperReaderApplication ?: return
        application.extensionInstaller.handlePackageChanged(
            packageName = intent.data?.schemeSpecificPart,
            installed = intent.action != Intent.ACTION_PACKAGE_REMOVED,
        )
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

private data class InstallRequest(
    val kind: ExtensionReleaseKind,
    val packageName: String,
    val versionCode: Long,
    val serviceClassName: String,
    val signerSha256: String,
    val installUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
)

private data class ActiveInstallSession(
    val sessionId: Int,
    val versionCode: Long,
) {
    fun encode(): String = "$sessionId:$versionCode"

    companion object {
        fun decode(value: String): ActiveInstallSession? {
            val parts = value.split(':', limit = 2)
            val sessionId = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val versionCode = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return ActiveInstallSession(sessionId, versionCode).takeIf { sessionId >= 0 && versionCode > 0 }
        }
    }
}

private fun VerifiedExtensionRelease.installRequest(): InstallRequest {
    require(compatible) { "This extension is not compatible with PaperReader" }
    require(packageName.matches(EXTENSION_PACKAGE_REGEX)) { "The signed extension package name is invalid" }
    require(serviceClassName.startsWith("$packageName.")) { "The signed extension service is invalid" }
    require(versionCode > 0) { "The signed extension version is invalid" }
    require(signerSha256.matches(SHA256_REGEX)) { "The signed extension fingerprint is invalid" }
    require(apkSha256?.matches(SHA256_REGEX) == true) { "The signed release has no valid APK SHA-256" }
    require(apkSizeBytes != null && apkSizeBytes in 1..MAX_APK_BYTES) {
        "The signed release has no valid APK byte size"
    }
    when (kind) {
        ExtensionReleaseKind.SOURCE -> require(providerId != null) { "The source provider ID is missing" }
        ExtensionReleaseKind.THEME -> require(themeIds.isNotEmpty()) { "The theme IDs are missing" }
    }
    return InstallRequest(
        kind = kind,
        packageName = packageName,
        versionCode = versionCode,
        serviceClassName = serviceClassName,
        signerSha256 = signerSha256,
        installUrl = installUrl,
        apkSha256 = requireNotNull(apkSha256),
        apkSizeBytes = requireNotNull(apkSizeBytes),
    )
}

private const val ACTION_INSTALL_STATUS = "dev.paperreader.app.action.EXTENSION_INSTALL_STATUS"
private const val EXTRA_PACKAGE_NAME = "extension_package"
private const val EXTRA_INSTALL_SESSION_ID = "extension_install_session"
private const val INVALID_SESSION_ID = -1
private const val SESSION_PREFERENCES = "extension-install-sessions"
private const val MAX_APK_BYTES = 100L * 1024L * 1024L
private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
private val EXTENSION_PACKAGE_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")

internal fun isCurrentInstallSession(active: Int?, callback: Int, platform: Int): Boolean =
    active != null && active == callback && active == platform

internal fun recoveredInstallState(installedVersionCode: Long?, expectedVersionCode: Long): ExtensionInstallState =
    if (installedVersionCode != null && installedVersionCode >= expectedVersionCode) {
        ExtensionInstallState.Installed
    } else {
        ExtensionInstallState.Failed("The previous Android install session ended before completion")
    }
