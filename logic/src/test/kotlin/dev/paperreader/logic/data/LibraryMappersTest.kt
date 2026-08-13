package dev.paperreader.logic.data

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperAuthor
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMappersTest {
    @Test
    fun remotePaperMappingAddsProviderIdentityAndPreservesSubjects() {
        val remote = RemotePaper(
            providerId = "arxiv",
            providerRecordId = "1234.5678",
            title = "A paper",
            authors = listOf(PaperAuthor("Ada Lovelace", familyName = "Lovelace")),
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "https://arxiv.org/abs/1234.5678v2")),
            subjects = setOf("cs.AI", "stat.ML"),
            publishedDate = LocalDate.of(2026, 1, 2),
            updatedAt = Instant.parse("2026-01-03T00:00:00Z"),
            manifestations = listOf(RemoteManifestation(ManifestationType.PREPRINT, pdfUrl = "https://arxiv.org/pdf/1234.5678")),
        )

        val rows = remote.toEntities(WorkId("w-test"), Instant.parse("2026-01-04T00:00:00Z"))

        assertEquals(setOf("cs.AI", "stat.ML"), rows.work.subjects.split("\u001f").toSet())
        assertTrue(rows.identifiers.any { it.type == "ARXIV" && it.value == "1234.5678" })
        assertTrue(rows.identifiers.any { it.type == "PROVIDER" && it.authority == "arxiv" })
        assertEquals(1, rows.manifestations.size)
        assertEquals("PREPRINT", rows.manifestations.single().type)
    }

    @Test
    fun stableWorkIdIsIndependentOfIdentifierOrder() {
        val identifiers = listOf(
            PaperIdentifier(IdentifierType.DOI, "10.1000/example"),
            PaperIdentifier(IdentifierType.ARXIV, "1234.5678v2"),
        )

        assertEquals(stableWorkId(identifiers), stableWorkId(identifiers.reversed()))
    }

    @Test
    fun refreshMergesIdentifiersAndManifestationsInsteadOfDroppingLocalRows() {
        val oldIdentifier = IdentifierEntity("w-1", "ARXIV", "1234.5678", "")
        val oldManifestation = ManifestationEntity(
            id = "m-local", workId = "w-1", type = "PREPRINT", sourceProvider = "arxiv",
            sourceRecordId = "1234.5678", version = null, landingPageUrl = null, pdfUrl = "file://local",
            license = null, publishedDateEpochDay = null, updatedAtEpochMillis = 1,
        )
        val incomingIdentifier = IdentifierEntity("w-1", "DOI", "10.1000/example", "")
        val incomingManifestation = oldManifestation.copy(id = "m-semanticscholar", sourceProvider = "semanticscholar", sourceRecordId = "s2-1000")

        assertEquals(setOf(oldIdentifier, incomingIdentifier), mergeIdentifiers(listOf(oldIdentifier), listOf(incomingIdentifier)).toSet())
        assertEquals(setOf(oldManifestation, incomingManifestation), mergeManifestations(listOf(oldManifestation), listOf(incomingManifestation)).toSet())
    }

    @Test
    fun refreshUpdatesTheSameManifestationInsteadOfKeepingStaleMetadata() {
        val stale = ManifestationEntity(
            id = "m-arxiv", workId = "w-1", type = "PREPRINT", sourceProvider = "arxiv",
            sourceRecordId = "1234.5678", version = "v1", landingPageUrl = null,
            pdfUrl = null, license = null, publishedDateEpochDay = null, updatedAtEpochMillis = 1,
        )
        val refreshed = stale.copy(
            version = "v2",
            pdfUrl = "https://arxiv.org/pdf/1234.5678v2",
            updatedAtEpochMillis = 2,
        )

        assertEquals(listOf(refreshed), mergeManifestations(listOf(stale), listOf(refreshed)))
    }

    @Test
    fun providerEnrichmentKeepsExistingAbstractDateAndUnionsSubjects() {
        val existing = WorkEntity(
            id = "w-1", title = "Preprint title", abstractText = "Existing abstract",
            subjects = "cs.AI", publishedDateEpochDay = 10, createdAtEpochMillis = 1,
            updatedAtEpochMillis = 5,
        )
        val enrichment = existing.copy(
            title = "Published title", abstractText = null, subjects = "stat.ML",
            publishedDateEpochDay = null, createdAtEpochMillis = 99, updatedAtEpochMillis = 4,
        )

        val merged = mergeWork(existing, enrichment)

        assertEquals("Published title", merged.title)
        assertEquals("Existing abstract", merged.abstractText)
        assertEquals(setOf("cs.AI", "stat.ML"), merged.subjects.split("\u001f").toSet())
        assertEquals(10L, merged.publishedDateEpochDay)
        assertEquals(1L, merged.createdAtEpochMillis)
        assertEquals(5L, merged.updatedAtEpochMillis)
    }
}
