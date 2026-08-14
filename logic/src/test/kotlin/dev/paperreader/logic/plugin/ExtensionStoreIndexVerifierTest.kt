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
    fun `legacy v1 release without artifact identity remains catalog only`() {
        val withoutHash = validPayload()
            .replaceFirst("\"apkSha256\":\"${"01".repeat(32)}\",", "")
            .replaceFirst("\"apkSizeBytes\":1048576,", "")

        val release = verifier.verify(signedEnvelope(withoutHash), publicKey.base64()).releases
            .single { it.kind == ExtensionReleaseKind.SOURCE }

        assertEquals(null, release.apkSha256)
        assertEquals(null, release.apkSizeBytes)
    }

    @Test
    fun `partial artifact identity is rejected`() {
        val withoutSize = validPayload().replaceFirst("\"apkSizeBytes\":1048576,", "")

        assertFails { verifier.verify(signedEnvelope(withoutSize), publicKey.base64()) }
    }

    @Test
    fun `non-standard APK download port is rejected before catalog display`() {
        val nonStandardPort = validPayload().replaceFirst(
            "https://example.org/extensions/semanticscholar.apk",
            "https://example.org:8443/extensions/semanticscholar.apk",
        )

        assertFails { verifier.verify(signedEnvelope(nonStandardPort), publicKey.base64()) }
    }

    @Test
    fun `signed index validation rejects every malformed boundary before trust`() {
        val mutations = listOf(
            { validPayload().replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":2") },
            { validPayload().replaceFirst("\"storeId\":\"paperreader.community\"", "\"storeId\":\"X\"") },
            { validPayload().replaceFirst("\"displayName\":\"PaperReader community\"", "\"displayName\":\" \"") },
            { validPayload().replaceFirst("https://example.org/extensions", "http://example.org/extensions") },
            { validPayload().replaceFirst("\"sequence\":7", "\"sequence\":0") },
            { validPayload().replaceFirst("2026-08-13T05:59:00Z", "2099-08-13T05:59:00Z") },
            { validPayload().replaceFirst("\"kind\":\"source\"", "\"kind\":\"unknown\"") },
            { validPayload().replaceFirst("\"packageName\":\"dev.paperreader.extensions.semanticscholar\"", "\"packageName\":\"bad\"") },
            { validPayload().replaceFirst("dev.paperreader.extensions.semanticscholar.SemanticScholarService", "dev.attacker.Service") },
            { validPayload().replaceFirst("\"displayName\":\"Semantic Scholar\"", "\"displayName\":\" \"") },
            { validPayload().replaceFirst("\"versionCode\":3", "\"versionCode\":0") },
            { validPayload().replaceFirst("\"versionName\":\"1.2.0\"", "\"versionName\":\" \"") },
            { validPayload().replaceFirst("${"ab".repeat(32)}", "zz") },
            { validPayload().replaceFirst("\"minimumHostApi\":1", "\"minimumHostApi\":0") },
            { validPayload().replaceFirst("\"maximumHostApi\":1", "\"maximumHostApi\":0") },
            { validPayload().replaceFirst("https://example.org/extensions/semanticscholar.apk", "http://example.org/extensions/semanticscholar.apk") },
            { validPayload().replaceFirst("${"01".repeat(32)}", "bad") },
            { validPayload().replaceFirst("\"apkSizeBytes\":1048576", "\"apkSizeBytes\":0") },
            { validPayload().replaceFirst("\"license\":\"Apache-2.0\"", "\"license\":\" \"") },
            { validPayload().replaceFirst("https://example.org/privacy/semanticscholar", "http://example.org/privacy/semanticscholar") },
            { validPayload().replaceFirst("\"content_source\"", "\"unknown_role\"") },
            { validPayload().replaceFirst("\"doi\"", "\"unknown_identifier\"") },
            { validPayload().replaceFirst("\"relevance\"", "\"unknown_sort\"") },
            { validPayload().replaceFirst("\"themeIds\":[\"blueprint\"]", "\"themeIds\":[\"?\"]") },
            { validPayload().replaceFirst("\"packageName\":\"dev.paperreader.extensions.blueprint\"", "\"packageName\":\"dev.paperreader.extensions.semanticscholar\"") },
        )
        mutations.forEachIndexed { index, mutation ->
            assertFails("mutation $index") {
                verifier.verify(signedEnvelope(mutation()), publicKey.base64())
            }
        }

        assertFails { verifier.verify(ByteArray(0), publicKey.base64()) }
        assertFails { verifier.verify("not-json".toByteArray(), publicKey.base64()) }
        assertFails { verifier.verify(signedEnvelope(validPayload()), "bad") }
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
              "apkSha256":"${"01".repeat(32)}",
              "apkSizeBytes":1048576,
              "license":"Apache-2.0",
              "privacyUrl":"https://example.org/privacy/semanticscholar",
              "providerId":"semanticscholar-sample",
              "minimumRequestIntervalMillis":1000,
              "sourceCapabilities":["search","details","pdf_link"],
              "sourceRoles":["content_source"],
              "sourceIdentifierTypes":["doi"],
              "sourceSupportedSorts":["relevance"]
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
              "apkSha256":"${"02".repeat(32)}",
              "apkSizeBytes":524288,
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

    private fun assertFails(block: () -> Unit) = assertFails("Expected extension store verification to fail", block)

    private fun assertFails(message: String, block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: ExtensionStoreIndexException) {
            failed = true
        }
        assertTrue(message, failed)
    }
}
