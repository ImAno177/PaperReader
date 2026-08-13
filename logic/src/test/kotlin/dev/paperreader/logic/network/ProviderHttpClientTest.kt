package dev.paperreader.logic.network

import dev.paperreader.logic.provider.ProviderException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpClientTest {
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
}
