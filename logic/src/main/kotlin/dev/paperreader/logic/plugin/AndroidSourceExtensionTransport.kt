package dev.paperreader.logic.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.IPaperSourceCallback
import dev.paperreader.extensions.api.IPaperSourceService
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourcePaperResponse
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class TrustedSourceExtension(
    val packageName: String,
    val serviceClassName: String,
    val versionCode: Long,
    val signerSha256: String,
    val providerId: String,
    val displayName: String,
    val minimumRequestIntervalMillis: Long,
    val capabilities: Set<SourceCapability> = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS),
    val roles: Set<SourceRole> = setOf(SourceRole.CONTENT_SOURCE),
    val identifierLookupTypes: Set<SourceIdentifierType> = SourceIdentifierType.entries.toSet(),
    val supportedSorts: Set<SourceSearchSort> = SourceSearchSort.entries.toSet(),
    val versionName: String? = null,
    val installUrl: String? = null,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
    val minimumVersionCode: Long = versionCode,
) {
    init {
        require(packageName.contains('.'))
        require(serviceClassName.startsWith("$packageName."))
        require(versionCode > 0)
        require(normalizeFingerprint(signerSha256).length == 64)
        SourceExtensionDescriptor(
            packageName = packageName,
            providerId = providerId,
            displayName = displayName,
            minimumRequestIntervalMillis = minimumRequestIntervalMillis,
            capabilities = capabilities,
            roles = roles,
            identifierLookupTypes = identifierLookupTypes,
            supportedSorts = supportedSorts,
        )
        require(versionName == null || versionName.isNotBlank())
        installUrl?.let { rawUrl ->
            val uri = URI(rawUrl)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
        }
        require((apkSha256 == null) == (apkSizeBytes == null))
        apkSha256?.let { require(it.matches(Regex("[0-9a-fA-F]{64}"))) }
        apkSizeBytes?.let { require(it in 1..100L * 1024L * 1024L) }
        require(minimumVersionCode in 1..versionCode)
    }

    internal fun descriptor() = SourceExtensionDescriptor(
        packageName = packageName,
        providerId = providerId,
        displayName = displayName,
        minimumRequestIntervalMillis = minimumRequestIntervalMillis,
        capabilities = capabilities,
        roles = roles,
        identifierLookupTypes = identifierLookupTypes,
        supportedSorts = supportedSorts,
    )
}

internal class AndroidSourceExtensionTransport(
    context: Context,
    override val descriptor: SourceExtensionDescriptor,
    private val trustedRelease: TrustedSourceExtension,
    private val timeoutMillis: Long = 15_000,
) : SourceExtensionTransport {
    private val applicationContext = context.applicationContext
    private val component = ComponentName(trustedRelease.packageName, trustedRelease.serviceClassName)

    init {
        require(descriptor == trustedRelease.descriptor())
        require(timeoutMillis in 1_000..60_000)
    }

    override suspend fun search(request: SourceSearchRequest): SourceSearchPage =
        execute(request.requestId, SourceSearchPage::fromBundle) { service, callback ->
            service.search(request.toBundle(), callback)
        }.also { response ->
            if (response.requestId != request.requestId) {
                throw SourceExtensionProtocolException("Mismatched source response request ID")
            }
        }

    override suspend fun getPaper(request: SourceGetPaperRequest): SourcePaperRecord? =
        execute(request.requestId, SourcePaperResponse::fromBundle) { service, callback ->
            service.getPaper(request.toBundle(), callback)
        }.also { response ->
            if (response.requestId != request.requestId) {
                throw SourceExtensionProtocolException("Mismatched source response request ID")
            }
        }.record

    private suspend fun <T> execute(
        requestId: String,
        decode: (Bundle) -> T,
        invoke: (IPaperSourceService, IPaperSourceCallback) -> Unit,
    ): T = withTimeout(timeoutMillis) {
        withContext(Dispatchers.IO) { verifyInstalledPackage() }
        val bound = bind()
        try {
            withContext(Dispatchers.IO) {
                val remoteDescriptor = runCatching {
                    SourceExtensionDescriptor.fromBundle(bound.service.descriptor)
                }.getOrElse { throw SourceExtensionProtocolException("Invalid source descriptor", it) }
                if (remoteDescriptor != descriptor) {
                    throw SourceExtensionProtocolException("Source descriptor does not match the trusted index")
                }
                awaitResponse(requestId, bound.service, decode, invoke)
            }
        } finally {
            bound.close()
        }
    }

    private suspend fun <T> awaitResponse(
        requestId: String,
        service: IPaperSourceService,
        decode: (Bundle) -> T,
        invoke: (IPaperSourceService, IPaperSourceCallback) -> Unit,
    ): T = suspendCancellableCoroutine { continuation ->
        val callback = object : IPaperSourceCallback.Stub() {
            override fun onSuccess(response: Bundle) {
                if (!continuation.isActive) return
                runCatching { decode(response) }
                    .onSuccess(continuation::resume)
                    .onFailure { continuation.resumeWithException(SourceExtensionProtocolException("Invalid source response", it)) }
            }

            override fun onFailure(failure: Bundle) {
                if (!continuation.isActive) return
                runCatching { ExtensionFailure.fromBundle(failure) }
                    .onSuccess { decoded ->
                        if (decoded.requestId == requestId) {
                            continuation.resumeWithException(SourceExtensionRequestException(decoded))
                        } else {
                            continuation.resumeWithException(SourceExtensionProtocolException("Mismatched failure request ID"))
                        }
                    }
                    .onFailure { continuation.resumeWithException(SourceExtensionProtocolException("Invalid source failure", it)) }
            }
        }
        continuation.invokeOnCancellation {
            runCatching { service.cancel(requestId) }
        }
        try {
            invoke(service, callback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private suspend fun bind(): BoundSourceService = suspendCancellableCoroutine { continuation ->
        val closed = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun closeConnection() {
            if (closed.compareAndSet(false, true)) {
                runCatching { applicationContext.unbindService(connection) }
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IPaperSourceService.Stub.asInterface(binder)
                if (service == null) {
                    closeConnection()
                    if (continuation.isActive) {
                        continuation.resumeWithException(SourceExtensionProtocolException("Invalid source binder"))
                    }
                    return
                }
                if (continuation.isActive) {
                    continuation.resume(BoundSourceService(service, ::closeConnection))
                } else {
                    closeConnection()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onBindingDied(name: ComponentName) {
                closeConnection()
                if (continuation.isActive) {
                    continuation.resumeWithException(SourceExtensionProtocolException("Source binding died"))
                }
            }

            override fun onNullBinding(name: ComponentName) {
                closeConnection()
                if (continuation.isActive) {
                    continuation.resumeWithException(SourceExtensionProtocolException("Source returned a null binding"))
                }
            }
        }
        continuation.invokeOnCancellation { closeConnection() }
        val intent = Intent(PaperExtensionContract.SOURCE_SERVICE_ACTION).setComponent(component)
        val bindFlags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Context.BIND_INCLUDE_CAPABILITIES
        } else {
            0
        }
        val didBind = runCatching {
            applicationContext.bindService(intent, connection, bindFlags)
        }.getOrDefault(false)
        if (!didBind) {
            closeConnection()
            if (continuation.isActive) {
                continuation.resumeWithException(SourceExtensionProtocolException("Unable to bind source extension"))
            }
        }
    }

    internal fun verifyInstalledPackage(): Long {
        val packageManager = applicationContext.packageManager
        val packageInfo = packageManager.getPackageInfo(
            trustedRelease.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val fingerprint = normalizeFingerprint(trustedRelease.signerSha256)
        require(
            packageManager.hasSigningCertificate(
                trustedRelease.packageName,
                fingerprint.hexToByteArray(),
                PackageManager.CERT_INPUT_SHA256,
            ),
        ) { "Installed source signer does not match the trusted index" }
        val serviceInfo = packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        require(serviceInfo.enabled && serviceInfo.exported) { "Source service is not bindable" }
        require(serviceInfo.applicationInfo?.uid != applicationContext.applicationInfo.uid) {
            "Source extension must run under a separate UID"
        }
        require(
            serviceInfo.metaData?.getInt(PaperExtensionContract.META_API_VERSION, -1) ==
                PaperExtensionContract.API_VERSION,
        ) { "Source service API version is incompatible" }
        require(
            serviceInfo.metaData?.getString(PaperExtensionContract.META_EXTENSION_KIND) ==
                PaperExtensionContract.EXTENSION_KIND_SOURCE,
        ) { "Source service kind is invalid" }
        requireTrustedSourceVersion(
            installedVersionCode = packageInfo.longVersionCode,
            minimumVersionCode = trustedRelease.minimumVersionCode,
            maximumVersionCode = trustedRelease.versionCode,
        )
        return packageInfo.longVersionCode
    }

    private class BoundSourceService(
        val service: IPaperSourceService,
        private val closeAction: () -> Unit,
    ) : AutoCloseable {
        override fun close() = closeAction()
    }
}

internal class InstalledSourceVersionOutOfRangeException(
    val installedVersionCode: Long,
    val minimumVersionCode: Long,
    val maximumVersionCode: Long,
) : IllegalArgumentException("Installed source version is outside the trusted release range") {
    val updateCanRemediate: Boolean = installedVersionCode < minimumVersionCode
}

internal fun requireTrustedSourceVersion(
    installedVersionCode: Long,
    minimumVersionCode: Long,
    maximumVersionCode: Long,
) {
    if (installedVersionCode !in minimumVersionCode..maximumVersionCode) {
        throw InstalledSourceVersionOutOfRangeException(installedVersionCode, minimumVersionCode, maximumVersionCode)
    }
}

internal class SourceExtensionProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun normalizeFingerprint(value: String): String {
    val normalized = value.filter(Char::isLetterOrDigit).lowercase()
    require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 fingerprint" }
    return normalized
}

private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
