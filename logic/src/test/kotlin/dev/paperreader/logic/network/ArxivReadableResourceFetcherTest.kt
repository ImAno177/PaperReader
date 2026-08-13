package dev.paperreader.logic.network

import dev.paperreader.logic.reader.ReadableRemoteResult
import dev.paperreader.logic.reader.ReadableResourceRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArxivReadableResourceFetcherTest {
    @Test
    fun `returns bounded html with the configured identity headers`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<article>readable</article>"),
        )
        server.start()
        try {
            val fetcher = testFetcher(server)
            val result = fetcher.fetch(request(server, maximumBytes = 1_024))

            assertTrue(result is ReadableRemoteResult.Success)
            assertEquals("PaperReader/Test", server.takeRequest().getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `maps rate limit and preserves retry after`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9"))
        server.start()
        try {
            val result = testFetcher(server).fetch(request(server, maximumBytes = 1_024))

            assertEquals(ReadableRemoteResult.RateLimited(9_000), result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects an unannounced body that exceeds its byte budget`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setChunkedBody("0123456789abcdef", 2),
        )
        server.start()
        try {
            val result = testFetcher(server).fetch(request(server, maximumBytes = 8))

            assertEquals(ReadableRemoteResult.TooLarge, result)
        } finally {
            server.shutdown()
        }
    }

    private fun testFetcher(server: MockWebServer) = ArxivReadableResourceFetcher(
        client = OkHttpClient(),
        userAgent = "PaperReader/Test",
        contactEmail = null,
        minimumRequestIntervalMillis = 0,
        allowedHost = server.hostName,
        allowedScheme = "http",
    )

    private fun request(server: MockWebServer, maximumBytes: Long) = ReadableResourceRequest(
        url = server.url("/paper").toString(),
        accept = "text/html",
        maximumBytes = maximumBytes,
    )
}
