package dev.paperreader.logic.plugin

import dev.paperreader.extensions.api.SourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginTrustPolicyTest {
    private val fingerprint = "ab".repeat(32)
    private val release = TrustedPluginRelease(
        packageName = "org.example.paperprovider",
        versionCode = 12,
        signerSha256 = fingerprint,
        minimumApiVersion = 1,
        maximumApiVersion = 2,
    )

    @Test
    fun `trust requires package signer and compatible API`() {
        assertEquals(
            PluginTrustDecision.Trusted,
            PluginTrustPolicy.evaluate(release.packageName, fingerprint.uppercase(), 1, release),
        )
        assertEquals(
            PluginTrustDecision.PackageMismatch,
            PluginTrustPolicy.evaluate("org.attacker", fingerprint, 1, release),
        )
        assertEquals(
            PluginTrustDecision.SignerMismatch,
            PluginTrustPolicy.evaluate(release.packageName, "cd".repeat(32), 1, release),
        )
        assertEquals(
            PluginTrustDecision.IncompatibleApi,
            PluginTrustPolicy.evaluate(release.packageName, fingerprint, 3, release),
        )
    }

    @Test
    fun `descriptor and fingerprint invariants fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            CommunityPluginDescriptor("invalid", "service", "Display", 1, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityPluginDescriptor("org.example", "service", "Display", 0, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommunityPluginDescriptor("org.example", "", "Display", 1, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginTrustPolicy.evaluate(
                installedPackageName = "org.example.paperprovider",
                installedSignerSha256 = "invalid",
                hostApiVersion = 1,
                release = release,
            )
        }
    }

    @Test
    fun `community descriptor exposes every capability and trusted source metadata`() {
        val descriptor = CommunityPluginDescriptor(
            packageName = "org.example.paperprovider",
            serviceClassName = "org.example.paperprovider.SourceService",
            displayName = "Paper provider",
            apiVersion = 1,
            capabilities = PluginCapability.entries.toSet(),
        )
        val trusted = TrustedSourceExtension(
            packageName = "org.example.paperprovider",
            serviceClassName = "org.example.paperprovider.SourceService",
            versionCode = 3,
            signerSha256 = fingerprint,
            providerId = "sample",
            displayName = "Sample",
            minimumRequestIntervalMillis = 500,
            versionName = "1.2.0",
            installUrl = "https://example.org/sample.apk",
            apkSha256 = "cd".repeat(32),
            apkSizeBytes = 512,
            minimumVersionCode = 2,
        )

        assertEquals(PluginCapability.entries.toSet(), descriptor.capabilities)
        assertEquals("org.example.paperprovider.SourceService", trusted.serviceClassName)
        assertEquals(3L, trusted.versionCode)
        assertEquals(fingerprint, trusted.signerSha256)
        assertEquals("Sample", trusted.displayName)
        assertEquals(500L, trusted.minimumRequestIntervalMillis)
        assertEquals(setOf(SourceRole.CONTENT_SOURCE), trusted.roles)
        assertEquals("sample", trusted.descriptor().providerId)
        assertEquals("1.2.0", trusted.versionName)
        assertEquals("https://example.org/sample.apk", trusted.installUrl)
        assertEquals(512L, trusted.apkSizeBytes)
        assertEquals(2L, trusted.minimumVersionCode)
    }
}
