package dev.paperreader.logic.plugin

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
}
