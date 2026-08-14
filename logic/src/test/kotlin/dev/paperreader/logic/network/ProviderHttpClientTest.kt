package dev.paperreader.logic.network

import dev.paperreader.logic.provider.ProviderException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpClientTest {
    @Test fun `returns successful body and optional contact header`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("X-Trace", "trace-1").setBody("ok"))
        server.start()
        try {
            val result = ProviderHttpClient(userAgent = "PaperReader/Test", mailto = "reader@example.test")
                .get(server.url("/ok").toString(), "application/json")

            assertEquals("ok", result.body)
            assertEquals("trace-1", result.headers["X-Trace"])
            val request = server.takeRequest()
            assertEquals("PaperReader/Test", request.headers["User-Agent"])
            assertEquals("reader@example.test", request.headers["From"])
            assertEquals("application/json", request.headers["Accept"])
        } finally {
            server.shutdown()
        }
    }

    @Test fun `maps non-success responses and invalid retry values without leaking implementation errors`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        server.start()
        try {
            val error = runCatching { ProviderHttpClient().get(server.url("/missing").toString(), "*/*") }.exceptionOrNull()
            assertTrue(error is ProviderException.Unavailable)
            assertEquals(null, ProviderHttpClient.parseRetryAfter("not-a-date", 0))
            assertEquals(0L, ProviderHttpClient.parseRetryAfter("-3", 0))
            assertNotNull(ProviderHttpClient.parseRetryAfter("Tue, 14 Nov 2023 22:13:25 GMT", 1_699_999_000_000L))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `rejects announced response larger than the configured limit`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("123456789").setHeader("Content-Length", "9"))
        server.start()
        try {
            val error = runCatching {
                ProviderHttpClient(maximumResponseBytes = 8).get(server.url("/large").toString(), "text/plain")
            }.exceptionOrNull()
            assertTrue(error is ProviderException.InvalidResponse)
        } finally {
            server.shutdown()
        }
    }

    @Test fun maps429RetryAfterSeconds() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.start()
        try {
            val error = runCatching { ProviderHttpClient().get(server.url("/query").toString(), "application/json") }.exceptionOrNull()
            assertTrue(error is ProviderException.RateLimited)
            assertEquals(7_000L, (error as ProviderException.RateLimited).retryAfterMillis)
        } finally {
            server.shutdown()
        }
    }

    @Test fun parsesHttpDateRetryAfterWithoutNegativeDelay() {
        val now = 1_700_000_000_000L
        val raw = "Tue, 14 Nov 2023 22:13:25 GMT"
        assertTrue(ProviderHttpClient.parseRetryAfter(raw, now)!! >= 0L)
    }

    @Test fun maps429WithoutInventingRetryDelay() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate exceeded."))
        server.start()
        try {
            val error = runCatching {
                ProviderHttpClient().get(server.url("/query").toString(), "application/atom+xml")
            }.exceptionOrNull()

            assertTrue(error is ProviderException.RateLimited)
            assertNull((error as ProviderException.RateLimited).retryAfterMillis)
        } finally {
            server.shutdown()
        }
    }

    @Test fun rejectsChunkedMetadataBeyondTheConfiguredByteLimit() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setChunkedBody("0123456789abcdefg", 4))
        server.start()
        try {
            val error = runCatching {
                ProviderHttpClient(maximumResponseBytes = 16)
                    .get(server.url("/oversized").toString(), "application/json")
            }.exceptionOrNull()

            assertTrue(error is ProviderException.InvalidResponse)
        } finally {
            server.shutdown()
        }
    }

    @Test fun requestGateSerializesStartsAtTheProviderInterval() = runTest {
        var now = 10_000L
        val waits = mutableListOf<Long>()
        val gate = ProviderRequestGate(
            minimumIntervalMillis = 3_000,
            nowMillis = { now },
            wait = { duration ->
                waits += duration
                now += duration
            },
        )

        gate.execute { "first" }
        gate.execute { "second" }

        assertEquals(listOf(3_000L), waits)
    }

    @Test fun `request gate releases the lock when a request fails`() = runTest {
        var now = 0L
        val gate = ProviderRequestGate(minimumIntervalMillis = 0, nowMillis = { now })
        val failure = runCatching { gate.execute<String> { error("boom") } }.exceptionOrNull()

        assertEquals("boom", failure?.message)
        assertEquals("second", gate.execute { "second" })
    }
}
