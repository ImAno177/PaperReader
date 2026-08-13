package dev.paperreader.logic.data

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.CitationMetrics
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchRecordCodecTest {
    @Test
    fun `snapshot round trip preserves a complete provider record`() {
        val record = fixture()

        val payload = SavedSearchRecordCodec.encode(record)

        assertEquals(record, SavedSearchRecordCodec.decode(payload))
        assertEquals(64, SavedSearchRecordCodec.fingerprint(payload).length)
    }

    @Test
    fun `fingerprint is stable for set order and changes with provider metadata`() {
        val first = fixture()
        val reordered = first.copy(
            identifiers = first.identifiers.reversed().toSet(),
            subjects = first.subjects.reversed().toSet(),
        )
        val changed = first.copy(updatedAt = first.updatedAt?.plusSeconds(60))

        val firstFingerprint = SavedSearchRecordCodec.fingerprint(SavedSearchRecordCodec.encode(first))

        assertEquals(
            firstFingerprint,
            SavedSearchRecordCodec.fingerprint(SavedSearchRecordCodec.encode(reordered)),
        )
        assertNotEquals(
            firstFingerprint,
            SavedSearchRecordCodec.fingerprint(SavedSearchRecordCodec.encode(changed)),
        )
    }

    @Test
    fun `records without citation metrics keep the legacy snapshot shape`() {
        val payload = SavedSearchRecordCodec.encode(fixture().copy(citationMetrics = null))

        assertTrue(!payload.contains("citationMetrics"))
        assertEquals(null, SavedSearchRecordCodec.decode(payload).citationMetrics)
    }

    @Test
    fun `legacy schema one payload decodes without citation metrics`() {
        val legacy = """{"schemaVersion":1,"providerId":"arxiv","providerRecordId":"1","title":"Legacy"}"""

        assertEquals(null, SavedSearchRecordCodec.decode(legacy).citationMetrics)
    }

    @Test
    fun `hostile provider record is rejected before snapshot serialization`() {
        val oversized = fixture().copy(abstractText = "x".repeat(256 * 1024 + 1))

        val error = runCatching { SavedSearchRecordCodec.encode(oversized) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("input limit") == true)
    }

    private fun fixture() = RemotePaper(
        providerId = "arxiv",
        providerRecordId = "2401.00001v2",
        title = "A durable saved-search result",
        abstractText = "Complete metadata survives process death.",
        authors = listOf(PaperAuthor("Ada Lovelace", "Ada", "Lovelace")),
        identifiers = setOf(
            PaperIdentifier(IdentifierType.ARXIV, "2401.00001"),
            PaperIdentifier(IdentifierType.DOI, "10.1000/example"),
        ),
        subjects = setOf("cs.DL", "cs.IR"),
        publishedDate = LocalDate.of(2024, 1, 1),
        updatedAt = Instant.parse("2026-08-12T00:00:00Z"),
        manifestations = listOf(
            RemoteManifestation(
                type = ManifestationType.PREPRINT,
                version = "v2",
                landingPageUrl = "https://arxiv.org/abs/2401.00001v2",
                pdfUrl = "https://arxiv.org/pdf/2401.00001v2.pdf",
                publishedDate = LocalDate.of(2024, 1, 1),
            ),
        ),
        citationMetrics = CitationMetrics(
            count = 42,
            sourceId = "crossref",
            observedAt = Instant.parse("2026-08-13T00:00:00Z"),
        ),
    )
}
