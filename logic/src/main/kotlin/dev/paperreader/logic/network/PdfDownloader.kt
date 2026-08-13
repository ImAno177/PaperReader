package dev.paperreader.logic.network

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class DownloadedPdf(
    val path: Path,
    val sha256: String,
    val byteLength: Long,
)

sealed class PdfDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class RetryableHttp(val statusCode: Int, val retryAfterMillis: Long?) :
        PdfDownloadException("Retryable HTTP $statusCode")

    class Http(val statusCode: Int) : PdfDownloadException("HTTP $statusCode")
    class TooLarge(val maximumBytes: Long) : PdfDownloadException("PDF exceeds $maximumBytes bytes")
    class InvalidPdf : PdfDownloadException("Response is not a PDF file")
}

internal class PdfDownloader(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val contactEmail: String?,
    private val maximumBytes: Long,
) {
    init {
        require(userAgent.isNotBlank())
        require(maximumBytes > 0)
    }

    suspend fun download(
        url: String,
        destinationDirectory: Path,
        onProgress: suspend (Double) -> Unit = {},
    ): DownloadedPdf = withContext(Dispatchers.IO) {
        Files.createDirectories(destinationDirectory)
        val temporary = Files.createTempFile(destinationDirectory, ".paper-download-", ".part")
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/pdf")
                .header("User-Agent", userAgent)
                .apply { if (!contactEmail.isNullOrBlank()) header("From", contactEmail) }
                .build()
            client.newCall(request).await().use { response ->
                when {
                    response.code == 429 -> throw PdfDownloadException.RetryableHttp(
                        statusCode = response.code,
                        retryAfterMillis = response.header("Retry-After")?.let(ProviderHttpClient::parseRetryAfter),
                    )

                    response.code in setOf(408, 425) || response.code in 500..599 -> {
                        throw PdfDownloadException.RetryableHttp(response.code, null)
                    }

                    !response.isSuccessful -> throw PdfDownloadException.Http(response.code)
                }

                val body = response.body ?: throw PdfDownloadException.InvalidPdf()
                val expectedLength = body.contentLength()
                if (expectedLength > maximumBytes) throw PdfDownloadException.TooLarge(maximumBytes)

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastProgress = 0.0
                body.byteStream().use { input ->
                    Files.newOutputStream(temporary).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maximumBytes) throw PdfDownloadException.TooLarge(maximumBytes)
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (expectedLength > 0) {
                                val progress = (total.toDouble() / expectedLength).coerceIn(0.0, 0.99)
                                if (progress - lastProgress >= 0.01) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                if (total <= 0 || !temporary.hasPdfMagic()) throw PdfDownloadException.InvalidPdf()

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val destination = destinationDirectory.resolve("$sha256.pdf")
                moveAtomically(temporary, destination)
                DownloadedPdf(destination, sha256, total)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun Path.hasPdfMagic(): Boolean {
    val prefix = ByteArray(5)
    return Files.newInputStream(this).use { input ->
        input.read(prefix) == prefix.size && prefix.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
    }
}

private fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
