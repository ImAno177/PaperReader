package dev.paperreader.logic.network

import dev.paperreader.logic.provider.ProviderException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Small shared HTTP boundary used by built-in providers. */
class ProviderHttpClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = "PaperReader/0.1 (Android; contact=maintainers@example.invalid)",
    private val mailto: String? = null,
    private val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
    init {
        require(maximumResponseBytes in 1 until Long.MAX_VALUE)
    }

    suspend fun get(url: String, accept: String): ProviderHttpResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", userAgent)
        if (!mailto.isNullOrBlank()) requestBuilder.header("From", mailto)
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val retryAfter = response.header("Retry-After")?.let(::parseRetryAfter)
                when {
                    response.code == 429 -> throw ProviderException.RateLimited(retryAfter)
                    !response.isSuccessful -> throw ProviderException.Unavailable(
                        IllegalStateException("HTTP ${response.code} from $url"),
                    )
                    else -> ProviderHttpResult(
                        body = response.body?.readBoundedString().orEmpty(),
                        headers = response.headers.toMap(),
                    )
                }
            }
        } catch (error: ProviderException) {
            throw error
        } catch (error: Exception) {
            throw ProviderException.Unavailable(error)
        }
    }

    private fun ResponseBody.readBoundedString(): String {
        val announcedLength = contentLength()
        if (announcedLength > maximumResponseBytes) {
            throw ProviderException.InvalidResponse(
                IllegalArgumentException("Provider response exceeds the metadata byte limit"),
            )
        }
        val source = source()
        if (source.request(maximumResponseBytes + 1L)) {
            throw ProviderException.InvalidResponse(
                IllegalArgumentException("Provider response exceeds the metadata byte limit"),
            )
        }
        return string()
    }

    companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Long = 8L * 1024L * 1024L

        /** Retry-After is either a delta-seconds value or an HTTP date. */
        fun parseRetryAfter(raw: String, nowMillis: Long = System.currentTimeMillis()): Long? {
            raw.trim().toLongOrNull()?.let { return (it * 1_000L).coerceAtLeast(0L) }
            return runCatching {
                val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
                Duration.ofMillis((target - nowMillis).coerceAtLeast(0L)).toMillis()
            }.getOrNull()
        }
    }
}

data class ProviderHttpResult(val body: String, val headers: Map<String, String> = emptyMap())

/** Enforces provider start-rate and single-connection policies with monotonic time. */
class ProviderRequestGate(
    private val minimumIntervalMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextRequestAtMillis = 0L

    init {
        require(minimumIntervalMillis >= 0)
    }

    suspend fun <T> execute(request: suspend () -> T): T {
        mutex.lock()
        try {
            val delayMillis = (nextRequestAtMillis - nowMillis()).coerceAtLeast(0L)
            if (delayMillis > 0) wait(delayMillis)
            nextRequestAtMillis = nowMillis() + minimumIntervalMillis
            return request()
        } finally {
            mutex.unlock()
        }
    }
}
