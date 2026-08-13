package dev.paperreader.logic.plugin

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStoreIndexVerifierTest {
    private val now = Instant.parse("2026-08-13T06:00:00Z")
    private val verifier = ExtensionStoreIndexVerifier(Clock.fixed(now, ZoneOffset.UTC))
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = keyPair.public.encoded.takeLast(32).toByteArray()

    @Test
    fun `valid signature creates bounded source and theme releases`() {
        val verified = verifier.verify(
            envelopeBytes = signedEnvelope(validPayload()),
            publicKeyBase64 = publicKey.base64(),
        )

        assertEquals("paperreader.community", verified.storeId)
        assertEquals(7, verified.sequence)
        assertEquals(2, verified.releases.size)
        assertEquals(64, verified.publicKeySha256.length)
        val source = verified.releases.single { it.kind == ExtensionReleaseKind.SOURCE }
        assertTrue(source.compatible)
        assertEquals(2L, source.minimumVersionCode)
        assertEquals("semanticscholar-sample", source.providerId)
        assertEquals(source.packageName, source.toTrustedSourceExtension()?.packageName)
        val theme = verified.releases.single { it.kind == ExtensionReleaseKind.THEME }
        assertEquals(setOf("blueprint"), theme.themeIds)
        assertEquals(null, theme.toTrustedSourceExtension())
    }

    @Test
    fun `tampered payload and wrong key fail before catalog is trusted`() {
        val envelope = signedEnvelope(validPayload()).decodeToString()
            .replace("cGFwZXJyZWFkZXI", "YXR0YWNrZXJyZWFkZXI")
            .encodeToByteArray()
        val wrongKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            .public.encoded.takeLast(32).toByteArray()

        assertFails { verifier.verify(envelope, publicKey.base64()) }
        assertFails { verifier.verify(signedEnvelope(validPayload()), wrongKey.base64()) }
    }

    @Test
    fun `store identity and monotonic sequence cannot roll back`() {
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload()),
                publicKey.base64(),
                expectedStoreId = "different.store",
            )
        }
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload()),
                publicKey.base64(),
                expectedStoreId = "paperreader.community",
                minimumSequence = 8,
            )
        }
    }

    @Test
    fun `unknown capability and cross-kind metadata are rejected`() {
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload().replace("pdf_link", "raw_http")),
                publicKey.base64(),
            )
        }
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload().replace("\"themeIds\":[\"blueprint\"]", "\"themeIds\":[\"blueprint\"],\"providerId\":\"bad\"")),
                publicKey.base64(),
            )
        }
    }

    @Test
    fun `publisher cannot trust versions outside a bounded release range`() {
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload().replaceFirst("\"minimumVersionCode\":2", "\"minimumVersionCode\":4")),
                publicKey.base64(),
            )
        }
        assertFails {
            verifier.verify(
                signedEnvelope(validPayload().replaceFirst("\"minimumVersionCode\":2", "\"minimumVersionCode\":0")),
                publicKey.base64(),
            )
        }
    }

    @Test
    fun `incompatible release remains visible but cannot become trusted transport`() {
        val payload = validPayload().replaceFirst("\"maximumHostApi\":1", "\"maximumHostApi\":0")
            .replaceFirst("\"minimumHostApi\":1", "\"minimumHostApi\":2")
            .replaceFirst("\"maximumHostApi\":0", "\"maximumHostApi\":2")
        val source = verifier.verify(signedEnvelope(payload), publicKey.base64()).releases
            .single { it.kind == ExtensionReleaseKind.SOURCE }

        assertFalse(source.compatible)
        assertEquals(null, source.toTrustedSourceExtension())
    }

    private fun validPayload(): String = """
        {
          "schemaVersion":1,
          "storeId":"paperreader.community",
          "displayName":"PaperReader community",
          "websiteUrl":"https://example.org/extensions",
          "sequence":7,
          "generatedAt":"2026-08-13T05:59:00Z",
          "extensions":[
            {
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
              "installUrl":"https://example.org/extensions/semanticscholar.apk",
              "license":"Apache-2.0",
              "privacyUrl":"https://example.org/privacy/semanticscholar",
              "providerId":"semanticscholar-sample",
              "minimumRequestIntervalMillis":1000,
              "sourceCapabilities":["search","details","pdf_link"]
            },
            {
              "kind":"theme",
              "packageName":"dev.paperreader.extensions.blueprint",
              "serviceClassName":"dev.paperreader.extensions.blueprint.BlueprintService",
              "displayName":"Blueprint",
              "versionCode":2,
              "minimumVersionCode":1,
              "versionName":"1.1.0",
              "signerSha256":"${"cd".repeat(32)}",
              "minimumHostApi":1,
              "maximumHostApi":1,
              "installUrl":"https://example.org/extensions/blueprint.apk",
              "license":"Apache-2.0",
              "themeIds":["blueprint"]
            }
          ]
        }
    """.trimIndent()

    private fun signedEnvelope(payload: String, signingKeyPair: KeyPair = keyPair): ByteArray {
        val payloadBytes = payload.encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signingKeyPair.private)
            update(payloadBytes)
            sign()
        }
        return """{"payload":"${payloadBytes.base64()}","signature":"${signature.base64()}"}""".encodeToByteArray()
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: ExtensionStoreIndexException) {
            failed = true
        }
        assertTrue("Expected extension store verification to fail", failed)
    }
}
