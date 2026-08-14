package dev.paperreader.app.extensions

import java.net.InetAddress
import java.net.Inet6Address
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceExtensionInstallerPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `download policy accepts signed public hosts and rejects authority confusion`() {
        val publicResolver: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("93.184.216.34")) }
        requireAllowedDownloadUri(
            URI("https://github.com/ImAno177/PaperReader-sources/releases/download/v0.1.0/source-arxiv.apk"),
            publicResolver,
        )
        requireAllowedDownloadUri(URI("https://extensions.example.org/source.apk"), publicResolver)

        listOf(
            "http://github.com/source.apk",
            "https://user@github.com/source.apk",
            "https://github.com:444/source.apk",
            "https://github.com/source.apk#fragment",
        ).forEach { hostile ->
            assertThrows(IllegalArgumentException::class.java) {
                requireAllowedDownloadUri(URI(hostile), publicResolver)
            }
        }
    }

    @Test
    fun `download policy rejects local private and documentation addresses`() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "fc00::1",
            "2001:db8::1",
        ).forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                requireAllowedDownloadUri(URI("https://extensions.example.org/source.apk")) {
                    listOf(InetAddress.getByName(address))
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowedDownloadUri(URI("https://localhost/source.apk")) { emptyList() }
        }
        val mappedCarrierGradeNat = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            this[12] = 100
            this[13] = 64
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireAllowedDownloadUri(URI("https://extensions.example.org/source.apk")) {
                listOf(Inet6Address.getByAddress(null, mappedCarrierGradeNat, -1))
            }
        }
    }

    @Test
    fun `artifact verification binds both bytes and exact size`() {
        val apk = temporaryFolder.newFile("source.apk").apply { writeBytes("verified-apk".encodeToByteArray()) }
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).toHex()

        verifyDownloadedApk(apk, digest.uppercase(), apk.length())
        assertThrows(IllegalArgumentException::class.java) {
            verifyDownloadedApk(apk, "0".repeat(64), apk.length())
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyDownloadedApk(apk, digest, apk.length() + 1)
        }
    }

    @Test
    fun `validated DNS answers are returned unchanged for the connection`() {
        val expected = listOf(InetAddress.getByName("93.184.216.34"))
        var lookups = 0
        val dns = PublicAddressDns {
            lookups += 1
            expected
        }

        assertEquals(expected, dns.lookup("extensions.example.org"))
        assertEquals(1, lookups)
    }

    @Test
    fun `stale installer callbacks cannot update a newer session`() {
        assertEquals(true, isCurrentInstallSession(active = 11, callback = 11, platform = 11))
        assertEquals(false, isCurrentInstallSession(active = 12, callback = 11, platform = 11))
        assertEquals(false, isCurrentInstallSession(active = 11, callback = 11, platform = 12))
        assertEquals(false, isCurrentInstallSession(active = null, callback = 11, platform = 11))
    }

    @Test
    fun `missing installer sessions recover to an honest terminal state`() {
        assertEquals(ExtensionInstallState.Installed, recoveredInstallState(installedVersionCode = 4, expectedVersionCode = 4))
        assertEquals(ExtensionInstallState.Installed, recoveredInstallState(installedVersionCode = 5, expectedVersionCode = 4))
        val failed = ExtensionInstallState.Failed("The previous Android install session ended before completion")
        assertEquals(failed, recoveredInstallState(installedVersionCode = 3, expectedVersionCode = 4))
        assertEquals(failed, recoveredInstallState(installedVersionCode = null, expectedVersionCode = 4))
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
