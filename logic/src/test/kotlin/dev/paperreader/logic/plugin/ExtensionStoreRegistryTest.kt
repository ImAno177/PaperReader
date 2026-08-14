package dev.paperreader.logic.plugin

import java.security.KeyPairGenerator
import java.security.Signature
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.HttpUrl.Companion.toHttpUrl

class ExtensionStoreRegistryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val now = Instant.parse("2026-08-13T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = keyPair.public.encoded.takeLast(32).toByteArray().base64()
    private var envelope = signedEnvelope(payload(sequence = 7))

    @Test
    fun `verified preview persists and reloads as trusted source`() = runTest {
        val directory = temporaryFolder.newFolder("registry").toPath()
        val registry = registry(directory)

        val preview = registry.preview(INDEX_URL, publicKey)
        assertEquals("Community", preview.index.displayName)
        registry.addPreview(preview.token)

        assertEquals(1, registry.state.value.stores.size)
        assertEquals("semanticscholar", registry.trustedSourceExtensions().single().providerId)
        assertEquals(INDEX_URL, registry.state.value.stores.single().indexUrl)
        assertEquals(false, registry.state.value.stores.single().pinned)
        assertTrue(registry.trustedThemeReleases().isEmpty())
        val reopened = registry(directory)
        assertEquals(7, reopened.state.value.stores.single().index.sequence)
        assertEquals("semanticscholar", reopened.trustedSourceExtensions().single().providerId)
    }

    @Test
    fun `refresh rejects rollback and keeps last verified catalog`() = runTest {
        val registry = registry(temporaryFolder.newFolder("rollback").toPath())
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        envelope = signedEnvelope(payload(sequence = 6))

        assertFails { registry.refresh("paperreader.community") }

        assertEquals(7, registry.state.value.stores.single().index.sequence)
        assertTrue(registry.state.value.issues.single().message.contains("rollback", ignoreCase = true))
    }

    @Test
    fun `same sequence cannot be reused with different signed content`() = runTest {
        val registry = registry(temporaryFolder.newFolder("equivocation").toPath())
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        envelope = signedEnvelope(payload(sequence = 7).replace("Community", "Changed name"))

        assertFails { registry.refresh("paperreader.community") }

        assertEquals("Community", registry.state.value.stores.single().index.displayName)
    }

    @Test
    fun `same signed payload tolerates envelope reformatting`() = runTest {
        val registry = registry(temporaryFolder.newFolder("reformatted-envelope").toPath())
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        envelope = envelope.decodeToString()
            .replaceFirst("{\"payload\":", "{\n  \"payload\": ")
            .replaceFirst(",\"signature\":", ",\n  \"signature\": ")
            .replaceFirst("}", "\n}")
            .encodeToByteArray()

        val refreshed = registry.refresh("paperreader.community")

        assertEquals(7, refreshed.index.sequence)
        assertTrue(registry.state.value.issues.isEmpty())
    }

    @Test
    fun `refresh all isolates failures and retains the last verified index`() = runTest {
        val registry = registry(temporaryFolder.newFolder("refresh-all").toPath())
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        envelope = signedEnvelope(payload(sequence = 8))
        assertTrue(registry.refreshAll())
        assertEquals(8, registry.state.value.stores.single().index.sequence)

        envelope = signedEnvelope(payload(sequence = 7))
        assertTrue(!registry.refreshAll())
        assertEquals(8, registry.state.value.stores.single().index.sequence)
        assertTrue(registry.state.value.issues.single().message.contains("rollback", ignoreCase = true))
    }

    @Test
    fun `tampered cache fails closed after process recreation`() = runTest {
        val directory = temporaryFolder.newFolder("tamper").toPath()
        val registry = registry(directory)
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        val cache = directory.resolve("stores.json").toFile()
        val rawCache = cache.readText()
        val encoded = Regex("\\\"envelopeBase64\\\":\\\"([^\\\"]+)\\\"").find(rawCache)!!.groupValues[1]
        val tampered = (if (encoded.first() == 'A') 'B' else 'A') + encoded.drop(1)
        cache.writeText(rawCache.replace(encoded, tampered))

        val reopened = registry(directory)

        assertTrue(reopened.state.value.stores.isEmpty())
        assertTrue(reopened.state.value.issues.isNotEmpty())
        assertTrue(reopened.trustedSourceExtensions().isEmpty())
    }

    @Test
    fun `redirect validation requires the same HTTPS host and effective port`() {
        assertTrue(isSameHttpsOrigin("https://example.org/index.json", "https://example.org/next.json".toHttpUrl()))
        assertTrue(isSameHttpsOrigin("https://Example.org/index.json", "https://example.org/next.json".toHttpUrl()))
        assertTrue(isSameHttpsOrigin("https://example.org:8443/index.json", "https://example.org:8443/next".toHttpUrl()))
        assertTrue(!isSameHttpsOrigin("https://example.org/index.json", "https://cdn.example.org/index.json".toHttpUrl()))
        assertTrue(!isSameHttpsOrigin("https://example.org/index.json", "https://example.org:8443/index.json".toHttpUrl()))
        assertTrue(!isSameHttpsOrigin("https://example.org/index.json", "http://example.org/index.json".toHttpUrl()))
    }

    @Test
    fun `ensure pinned creates a pinned store and refreshes the existing record`() = runTest {
        val registry = registry(temporaryFolder.newFolder("pinned").toPath())

        val first = registry.ensurePinned(INDEX_URL, publicKey, "paperreader.community")
        assertTrue(first.pinned)
        assertEquals(7, first.index.sequence)

        envelope = signedEnvelope(payload(sequence = 8))
        val refreshed = registry.ensurePinned(INDEX_URL, publicKey, "paperreader.community")
        assertTrue(refreshed.pinned)
        assertEquals(8, refreshed.index.sequence)
        assertEquals(emptyList<ExtensionStoreIssue>(), registry.state.value.issues)
    }

    @Test
    fun `pinned store rejects changed identity and cannot be removed`() = runTest {
        val registry = registry(temporaryFolder.newFolder("pinned-identity").toPath())
        registry.ensurePinned(INDEX_URL, publicKey, "paperreader.community")

        assertFails { registry.ensurePinned("https://example.org/changed.json", publicKey, "paperreader.community") }
        assertFails { registry.remove("paperreader.community") }
        assertFails { registry.refresh("missing.store") }
    }

    @Test
    fun `preview validation is strict and older pending previews are evicted`() = runTest {
        val registry = registry(temporaryFolder.newFolder("previews").toPath())
        assertFails { registry.preview("http://example.org/index.json", publicKey) }
        assertFails { registry.preview(INDEX_URL, "bad") }

        val previews = (1..4).map { registry.preview(INDEX_URL, publicKey) }
        assertFails { registry.addPreview(previews.first().token) }
        val added = registry.addPreview(previews.last().token)
        assertEquals("paperreader.community", added.index.storeId)
        assertFails { registry.addPreview(previews.last().token) }
    }

    @Test
    fun `refresh all can exclude stores and removing a user store clears it`() = runTest {
        val registry = registry(temporaryFolder.newFolder("remove").toPath())
        registry.addPreview(registry.preview(INDEX_URL, publicKey).token)
        assertTrue(registry.refreshAll(excludedStoreIds = setOf("paperreader.community")))

        registry.remove("paperreader.community")
        assertTrue(registry.state.value.stores.isEmpty())
        assertFails { registry.remove("paperreader.community") }
    }

    @Test
    fun `malformed persisted registries fail closed with a state issue`() {
        val malformed = temporaryFolder.newFolder("malformed-registry").toPath()
        Files.createDirectories(malformed)
        Files.write(malformed.resolve("stores.json"), "not-json".toByteArray())

        val malformedState = registry(malformed).state.value
        assertTrue(malformedState.stores.isEmpty())
        assertTrue(malformedState.issues.isNotEmpty())

        val unsupported = temporaryFolder.newFolder("unsupported-registry").toPath()
        Files.write(unsupported.resolve("stores.json"), "{\"schemaVersion\":2,\"stores\":[]}".toByteArray())
        assertTrue(registry(unsupported).state.value.issues.single().message.contains("Unsupported"))

        val oversized = temporaryFolder.newFolder("oversized-registry").toPath()
        Files.write(oversized.resolve("stores.json"), ByteArray(24 * 1024 * 1024 + 1))
        assertTrue(registry(oversized).state.value.issues.single().message.contains("too large"))
    }

    private fun registry(directory: java.nio.file.Path) = ExtensionStoreRegistry(
        directory = directory,
        clock = clock,
        envelopeFetcher = { requestedUrl ->
            assertEquals(INDEX_URL, requestedUrl)
            envelope
        },
    )

    private fun payload(sequence: Long): String = """
        {
          "schemaVersion":1,
          "storeId":"paperreader.community",
          "displayName":"Community",
          "websiteUrl":"https://example.org/extensions",
          "sequence":$sequence,
          "generatedAt":"2026-08-13T05:59:00Z",
          "extensions":[{
            "kind":"source",
            "packageName":"dev.paperreader.extensions.semanticscholar",
            "serviceClassName":"dev.paperreader.extensions.semanticscholar.SemanticScholarService",
            "displayName":"Semantic Scholar",
            "versionCode":3,
            "minimumVersionCode":2,
            "versionName":"1.2.0",
            "signerSha256":"${"ab".repeat(32)}",
            "minimumHostApi":1,
            "maximumHostApi":1,
            "installUrl":"https://example.org/semanticscholar.apk",
            "apkSha256":"${"01".repeat(32)}",
            "apkSizeBytes":1048576,
            "license":"Apache-2.0",
            "providerId":"semanticscholar",
            "minimumRequestIntervalMillis":1000,
            "sourceCapabilities":["search","details"]
          }]
        }
    """.trimIndent()

    private fun signedEnvelope(payload: String): ByteArray {
        val payloadBytes = payload.encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payloadBytes)
            sign()
        }
        return """{"payload":"${payloadBytes.base64()}","signature":"${signature.base64()}"}""".encodeToByteArray()
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Expected extension store operation to fail", failed)
    }

    private companion object {
        const val INDEX_URL = "https://example.org/extensions/index.signed.json"
    }
}
