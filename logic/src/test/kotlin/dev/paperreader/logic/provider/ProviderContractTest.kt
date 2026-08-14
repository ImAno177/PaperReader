package dev.paperreader.logic.provider

import dev.paperreader.logic.domain.ManifestationType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderContractTest {
    @Test
    fun `manifestation only accepts external http urls`() {
        val valid = RemoteManifestation(
            type = ManifestationType.PREPRINT,
            landingPageUrl = "https://arxiv.org/abs/1706.03762",
            pdfUrl = "http://arxiv.org/pdf/1706.03762",
        )

        assertEquals("https://arxiv.org/abs/1706.03762", valid.landingPageUrl)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteManifestation(ManifestationType.PREPRINT, pdfUrl = "file:///private/paper.pdf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteManifestation(ManifestationType.PREPRINT, landingPageUrl = "intent://host/#Intent;end")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteManifestation(ManifestationType.PREPRINT, pdfUrl = "https:///missing-host.pdf")
        }
    }

    @Test
    fun `provider descriptors and query contracts reject malformed input`() {
        assertThrows(IllegalArgumentException::class.java) { ProviderDescriptor("Bad ID", "Display", 0) }
        assertThrows(IllegalArgumentException::class.java) { ProviderDescriptor("id", "", 0) }
        assertThrows(IllegalArgumentException::class.java) { ProviderDescriptor("id", "Display", -1) }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderDescriptor("id", "Display", 0, roles = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) { PaperSearchQuery(" ") }
        assertThrows(IllegalArgumentException::class.java) { PaperSearchQuery("query", limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { PaperSearchQuery("query", limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { CitationMetrics(-1, "source", Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) { CitationMetrics(0, "Bad Source", Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) { RemotePaper("", "record", "title") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaper("source", "", "title") }
        assertThrows(IllegalArgumentException::class.java) { RemotePaper("source", "record", "") }
    }
}
