package dev.paperreader.app.extensions

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
    fun `download policy accepts release assets and rejects authority confusion`() {
        requireAllowedDownloadUri(
            URI("https://github.com/ImAno177/PaperReader-sources/releases/download/v0.1.0/source-arxiv.apk"),
        )
        requireAllowedDownloadUri(URI("https://release-assets.githubusercontent.com/asset.apk"))

        listOf(
            "http://github.com/source.apk",
            "https://attacker.example/source.apk",
            "https://user@github.com/source.apk",
            "https://github.com:444/source.apk",
        ).forEach { hostile ->
            assertThrows(IllegalArgumentException::class.java) {
                requireAllowedDownloadUri(URI(hostile))
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

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
