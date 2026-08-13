package dev.paperreader.logic.provider

import dev.paperreader.logic.domain.ManifestationType
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
}
