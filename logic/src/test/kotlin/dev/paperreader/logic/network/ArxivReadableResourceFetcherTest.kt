package dev.paperreader.logic.network

import dev.paperreader.logic.reader.ReadableRemoteResult
import dev.paperreader.logic.reader.ReadableResourceRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun `maps not-found, transient, permanent, empty, and missing-media responses`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(410))
        server.enqueue(MockResponse().setResponseCode(408))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(""))
        server.start()
        try {
            val fetcher = testFetcher(server)
            assertEquals(ReadableRemoteResult.NotFound, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.NotFound, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.Unavailable, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.Unavailable, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.Invalid, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.Invalid, fetcher.fetch(request(server, 1_024)))
            assertEquals(ReadableRemoteResult.Invalid, fetcher.fetch(request(server, 1_024)))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects unsafe request URIs and follows only allowed final origins`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("ok"))
        server.start()
        try {
            val fetcher = testFetcher(server)
            assertEquals(
                ReadableRemoteResult.Invalid,
                fetcher.fetch(ReadableResourceRequest("not a uri", "text/html", 1_024)),
            )
            assertEquals(
                ReadableRemoteResult.Invalid,
                fetcher.fetch(ReadableResourceRequest("https://example.org/paper", "text/html", 1_024)),
            )
            assertEquals(
                ReadableRemoteResult.Invalid,
                fetcher.fetch(ReadableResourceRequest(server.url("/paper#fragment").toString(), "text/html", 1_024)),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `validates constructor limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArxivReadableResourceFetcher(OkHttpClient(), " ", null, 0, "arxiv.org", "https")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArxivReadableResourceFetcher(OkHttpClient(), "PaperReader/Test", null, 0, "", "https")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArxivReadableResourceFetcher(OkHttpClient(), "PaperReader/Test", null, 0, "arxiv.org", "ftp")
        }
    }

    private fun testFetcher(server: MockWebServer) = ArxivReadableResourceFetcher(
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
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
