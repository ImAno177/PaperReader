package dev.paperreader.logic.network

import dev.paperreader.logic.reader.ReadableRemoteResource
import dev.paperreader.logic.reader.ReadableRemoteResult
import dev.paperreader.logic.reader.ReadableResourceFetcher
import dev.paperreader.logic.reader.ReadableResourceKind
import dev.paperreader.logic.reader.ReadableResourceRequest
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded host-side asset transport. Document bytes stay on the extension/Binder lane. */
internal class OkHttpReadableAssetFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val contactEmail: String?,
    private val allowedHost: String? = null,
    private val allowedScheme: String = "https",
    private val allowNonDefaultPort: Boolean = false,
    maximumConcurrentAssets: Int = DEFAULT_MAXIMUM_CONCURRENT_ASSETS,
) : ReadableResourceFetcher {
    private val permits = Semaphore(maximumConcurrentAssets)

    init {
        require(userAgent.isNotBlank())
        require(contactEmail == null || contactEmail.isNotBlank())
        require(allowedHost == null || allowedHost.isNotBlank())
        require(allowedScheme in setOf("http", "https"))
        require(maximumConcurrentAssets > 0)
    }

    override suspend fun fetch(request: ReadableResourceRequest): ReadableRemoteResult {
        if (request.kind != ReadableResourceKind.ASSET) return ReadableRemoteResult.Invalid
        return permits.withPermit { fetchAsset(request) }
    }

    private suspend fun fetchAsset(request: ReadableResourceRequest): ReadableRemoteResult {
        val requestedUri = runCatching { URI(request.url) }.getOrNull()
            ?: return ReadableRemoteResult.Invalid
        if (!requestedUri.isAllowed()) return ReadableRemoteResult.Invalid
        val httpRequest = runCatching {
            Request.Builder()
                .url(request.url)
                .header("Accept", request.accept)
                .header("User-Agent", userAgent)
                .apply { if (!contactEmail.isNullOrBlank()) header("From", contactEmail) }
                .build()
        }.getOrNull() ?: return ReadableRemoteResult.Invalid
        return try {
            client.newCall(httpRequest).awaitReadable().use { response ->
                val finalUri = response.request.url.toUri()
                if (!finalUri.isAllowed() || !finalUri.host.equals(requestedUri.host, ignoreCase = true)) {
                    return ReadableRemoteResult.Invalid
                }
                when {
                    response.code == 404 || response.code == 410 -> ReadableRemoteResult.NotFound
                    response.code == 429 -> ReadableRemoteResult.RateLimited(
                        response.header("Retry-After")?.let(ProviderHttpClient::parseRetryAfter),
                    )
                    response.code in setOf(408, 425) || response.code in 500..599 -> {
                        ReadableRemoteResult.Unavailable
                    }
                    !response.isSuccessful -> ReadableRemoteResult.Invalid
                    else -> response.readBounded(request.maximumBytes)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            ReadableRemoteResult.Unavailable
        } catch (_: IllegalArgumentException) {
            ReadableRemoteResult.Invalid
        }
    }

    private fun Response.readBounded(maximumBytes: Long): ReadableRemoteResult {
        if (maximumBytes <= 0) return ReadableRemoteResult.Invalid
        val responseBody = body ?: return ReadableRemoteResult.Invalid
        if (responseBody.contentLength() > maximumBytes) return ReadableRemoteResult.TooLarge
        val mediaType = responseBody.contentType()?.toString()?.takeIf(String::isNotBlank)
            ?: return ReadableRemoteResult.Invalid
        val source = responseBody.source()
        if (source.request(maximumBytes + 1L)) return ReadableRemoteResult.TooLarge
        val bytes = source.readByteArray()
        if (bytes.isEmpty()) return ReadableRemoteResult.Invalid
        return ReadableRemoteResult.Success(ReadableRemoteResource(bytes, mediaType))
    }

    private fun URI.isAllowed(): Boolean =
        scheme == allowedScheme &&
            host != null &&
            (allowedHost == null || host.equals(allowedHost, ignoreCase = true)) &&
            userInfo == null &&
            fragment == null &&
            (allowNonDefaultPort || port == -1)

    companion object {
        private const val DEFAULT_MAXIMUM_CONCURRENT_ASSETS = 4
    }
}

private suspend fun Call.awaitReadable(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                } else {
                    response.close()
                }
            }
        },
    )
}
