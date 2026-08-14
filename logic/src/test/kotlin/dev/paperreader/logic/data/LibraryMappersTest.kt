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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun workMappingSortsAuthorsAndManifestationsAndDropsIncompleteFiles() {
        val work = WorkEntity(
            id = "w-1",
            title = "Mapped paper",
            abstractText = null,
            subjects = "z\u001fa\u001f",
            publishedDateEpochDay = 10,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val manifestations = listOf(
            ManifestationEntity(
                id = "m-2", workId = "w-1", type = "OTHER", sourceProvider = "fixture",
                sourceRecordId = "2", version = null, landingPageUrl = null, pdfUrl = null,
                license = null, publishedDateEpochDay = null, updatedAtEpochMillis = 2,
            ),
            ManifestationEntity(
                id = "m-1", workId = "w-1", type = "PREPRINT", sourceProvider = "fixture",
                sourceRecordId = "1", version = "v1", landingPageUrl = "https://example.org",
                pdfUrl = "https://example.org/paper.pdf", license = "CC-BY",
                publishedDateEpochDay = 9, updatedAtEpochMillis = 1,
            ),
        )
        val paper = work.toDomain(
            authors = listOf(
                AuthorEntity("w-1", 2, "Second", null, null, null),
                AuthorEntity("w-1", 1, "First", "Ada", "Lovelace", null),
            ),
            identifiers = listOf(IdentifierEntity("w-1", "DOI", "10.1000/x", "")),
            manifestations = manifestations,
            readingState = null,
            files = listOf(
                FileEntity("valid", "m-1", "/paper.pdf", "a".repeat(64), 12, "application/pdf", "READY", null, 3),
                FileEntity("missing-path", "m-1", null, "a".repeat(64), 12, "application/pdf", "READY", null, 3),
                FileEntity("missing-hash", "m-1", "/paper.pdf", null, 12, "application/pdf", "READY", null, 3),
                FileEntity("missing-length", "m-1", "/paper.pdf", "a".repeat(64), null, "application/pdf", "READY", null, 3),
            ),
            collections = listOf(CollectionEntity(4, "Reading", 0, 1, 2)),
            annotationCount = 2,
        )

        assertEquals(listOf("First", "Second"), paper.work.authors.map { it.displayName })
        assertEquals(listOf("m-1", "m-2"), paper.manifestations.map { it.id.value })
        assertEquals(setOf("a", "z"), paper.work.subjects)
        assertEquals(setOf(4L), paper.collectionIds.map { it.value }.toSet())
        assertEquals(setOf("valid"), paper.localArtifacts.values.map { it.id }.toSet())
        assertEquals(2, paper.annotationCount)
    }

    @Test
    fun readingStateRoundTripsAndRemoteMappingUsesCurrentTimeWhenProviderHasNoUpdate() {
        val now = Instant.parse("2026-01-04T00:00:00Z")
        val state = dev.paperreader.logic.domain.ReadingState(
            workId = WorkId("w-1"),
            manifestationId = dev.paperreader.logic.domain.ManifestationId("m-1"),
            locator = dev.paperreader.logic.domain.ReadingLocator("a".repeat(64), "block-1", 4, 2, 0.5),
            status = dev.paperreader.logic.domain.ReadingStatus.READING,
            updatedAt = now,
        )
        val mappedWork = WorkEntity("w-1", "Paper", null, "", null, 1, 2).toDomain(
            authors = emptyList(),
            identifiers = emptyList(),
            manifestations = emptyList(),
            readingState = state.toEntity(),
        )
        assertEquals(state, mappedWork.readingState)

        val remote = RemotePaper(
            providerId = "fixture",
            providerRecordId = "record-1",
            title = "Remote",
            identifiers = emptySet(),
            manifestations = emptyList(),
            updatedAt = null,
        )
        val rows = remote.toEntities(WorkId("w-2"), now)
        assertEquals(now.toEpochMilli(), rows.work.updatedAtEpochMillis)
        assertEquals(emptyList<ManifestationEntity>(), rows.manifestations)
        assertTrue(rows.identifiers.any { it.type == "PROVIDER" && it.value == "record-1" })
    }

    @Test
    fun mergeWorkUsesIncomingWhenNoExistingAndStableIdRejectsNoExactIdentity() {
        val incoming = WorkEntity("w-2", "incoming", null, "", null, 1, 2)
        assertEquals(incoming, mergeWork(null, incoming))
        assertThrows(IllegalStateException::class.java) { stableWorkId(emptyList()) }
        assertTrue(stableWorkId(listOf(PaperIdentifier(IdentifierType.DOI, "10.1000/x")).asIterable()).value.startsWith("w-"))
    }

    @Test
    fun aggregateMappingIncludesFilesCollectionsAndOptionalState() {
        val work = WorkEntity("w-aggregate", "Aggregate", "abstract", "", null, 1, 2)
        val manifestation = ManifestationEntity(
            id = "m-aggregate", workId = work.id, type = "PREPRINT", sourceProvider = "fixture",
            sourceRecordId = "record", version = null, landingPageUrl = null, pdfUrl = null,
            license = null, publishedDateEpochDay = null, updatedAtEpochMillis = 2,
        )
        val aggregate = LibraryPaperAggregate(
            work = work,
            annotationCount = 0,
            authors = emptyList(),
            identifiers = emptyList(),
            manifestations = listOf(
                ManifestationWithFiles(
                    manifestation,
                    listOf(FileEntity("file", manifestation.id, "/paper.pdf", "a".repeat(64), 1, "application/pdf", "READY", null, 2)),
                ),
            ),
            readingState = null,
            collections = emptyList(),
        )

        val paper = aggregate.toDomain()
        assertNull(paper.readingState)
        assertEquals("/paper.pdf", paper.localArtifacts.values.single().storagePath)
        assertEquals("m-aggregate", paper.manifestations.single().id.value)
    }
}
