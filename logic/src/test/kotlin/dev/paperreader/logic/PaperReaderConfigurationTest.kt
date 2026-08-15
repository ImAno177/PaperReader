package dev.paperreader.logic

import dev.paperreader.logic.plugin.TrustedSourceExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaperReaderConfigurationTest {
    @Test
    fun `defaults are stable and optional contact is accepted`() {
        val defaults = PaperReaderConfiguration()
        val configured = defaults.copy(contactEmail = "reader@example.org")

        assertEquals("paper-reader.db", defaults.databaseName)
        assertEquals("PaperReader/0.1 (Android; +https://github.com/ImAno177/PaperReader)", defaults.userAgent)
        assertEquals(null, defaults.contactEmail)
        assertEquals("reader@example.org", configured.contactEmail)
        assertEquals(emptyList<TrustedSourceExtension>(), defaults.trustedSourceExtensions)
    }

    @Test
    fun `configuration rejects blank identity and non-positive limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(databaseName = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(userAgent = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(contactEmail = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(maximumPdfBytes = 0)
        }
    }

    @Test
    fun `configuration rejects duplicate provider and package registrations`() {
        val first = extension(packageName = "com.example.arxiv", providerId = "arxiv")
        val sameProvider = extension(packageName = "com.example.other", providerId = "arxiv")
        val samePackage = extension(packageName = "com.example.arxiv", providerId = "other")

        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(trustedSourceExtensions = listOf(first, sameProvider))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperReaderConfiguration(trustedSourceExtensions = listOf(first, samePackage))
        }
    }

    private fun extension(packageName: String, providerId: String) = TrustedSourceExtension(
        packageName = packageName,
        serviceClassName = "$packageName.SourceService",
        versionCode = 1,
        signerSha256 = "a".repeat(64),
        providerId = providerId,
        displayName = providerId,
        minimumRequestIntervalMillis = 1_000,
    )
}
