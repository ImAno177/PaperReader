package dev.paperreader.app.extensions

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
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

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
