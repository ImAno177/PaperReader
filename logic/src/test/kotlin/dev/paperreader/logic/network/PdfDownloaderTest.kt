package dev.paperreader.logic.network

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun streamsVerifiedPdfWithIdentityAndProgress() = runTest {
        val server = MockWebServer()
        val bytes = "%PDF-1.7\nreal bytes".toByteArray()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/pdf")
                .setBody(okio.Buffer().write(bytes)),
        )
        server.start()
        try {
            val progress = mutableListOf<Double>()
            val output = PdfDownloader(
                client = OkHttpClient(),
                userAgent = "PaperReader/Test",
                contactEmail = "reader@example.test",
                maximumBytes = 1_024,
            ).download(
                url = server.url("/paper.pdf").toString(),
                destinationDirectory = temporaryFolder.root.toPath(),
                onProgress = progress::add,
            )

            val expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, output.sha256)
            assertEquals(bytes.size.toLong(), output.byteLength)
            assertEquals(bytes.toList(), Files.readAllBytes(output.path).toList())
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.zipWithNext().all { (first, second) -> second >= first })

            val request = server.takeRequest()
            assertEquals("PaperReader/Test", request.headers["User-Agent"])
            assertEquals("reader@example.test", request.headers["From"])
            assertEquals("application/pdf", request.headers["Accept"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsBodyWithoutPdfMagicAndLeavesNoPartialFile() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<html>not a PDF</html>"))
        server.start()
        val destination = temporaryFolder.newFolder("invalid").toPath()
        try {
            val error = runCatching {
                PdfDownloader(OkHttpClient(), "PaperReader/Test", null, 1_024)
                    .download(server.url("/paper.pdf").toString(), destination)
            }.exceptionOrNull()

            assertTrue(error is PdfDownloadException.InvalidPdf)
            Files.list(destination).use { files -> assertFalse(files.findAny().isPresent) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsDeclaredOversizedResponseBeforeWriting() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("%PDF-1.7\n1234567890"))
        server.start()
        try {
            val error = runCatching {
                PdfDownloader(OkHttpClient(), "PaperReader/Test", null, 5)
                    .download(server.url("/paper.pdf").toString(), temporaryFolder.root.toPath())
            }.exceptionOrNull()

            assertTrue(error is PdfDownloadException.TooLarge)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun preservesRetryAfterForRateLimitedResponse() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9"))
        server.start()
        try {
            val error = runCatching {
                PdfDownloader(OkHttpClient(), "PaperReader/Test", null, 1_024)
                    .download(server.url("/paper.pdf").toString(), temporaryFolder.root.toPath())
            }.exceptionOrNull()

            assertTrue(error is PdfDownloadException.RetryableHttp)
            assertEquals(9_000L, (error as PdfDownloadException.RetryableHttp).retryAfterMillis)
        } finally {
            server.shutdown()
        }
    }
}
