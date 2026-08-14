package dev.paperreader.logic.domain

import dev.paperreader.logic.domain.history.ReadingHistoryEntry
import dev.paperreader.logic.provider.RemotePaper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelsTest {
    private val now = Instant.parse("2026-08-14T00:00:00Z")
    private val workId = WorkId("work-1")
    private val manifestationId = ManifestationId("manifestation-1")
    private val sha = "a".repeat(64)

    @Test
    fun `paper and artifact models preserve every public field`() {
        val author = PaperAuthor("Ada Lovelace", "Ada", "Lovelace", "https://orcid.org/0000-0000-0000-0000")
        val identifier = PaperIdentifier(IdentifierType.DOI, "10.1000/example")
        val work = PaperWork(
            id = workId,
            title = "A paper",
            abstractText = "Abstract",
            authors = listOf(author),
            identifiers = setOf(identifier),
            subjects = setOf("computer science"),
            publishedDate = LocalDate.of(2026, 1, 2),
            createdAt = now,
            updatedAt = now,
        )
        val manifestation = PaperManifestation(
            id = manifestationId,
            workId = workId,
            type = ManifestationType.VERSION_OF_RECORD,
            sourceProvider = "crossref",
            sourceRecordId = "record-1",
            version = "v1",
            landingPageUrl = "https://example.org/paper",
            pdfUrl = "https://example.org/paper.pdf",
            license = "CC-BY",
            publishedDate = LocalDate.of(2026, 1, 2),
            updatedAt = now,
        )
        val artifact = LocalPaperArtifact(
            id = "artifact-1",
            manifestationId = manifestationId,
            storagePath = "/files/paper.pdf",
            sha256 = sha,
            byteLength = 42,
            mimeType = "application/pdf",
            updatedAt = now,
        )
        val reading = ReadingState(
            workId = workId,
            manifestationId = manifestationId,
            locator = ReadingLocator(sha, "prx-b00001", 4, 2, 0.5),
            status = ReadingStatus.READING,
            updatedAt = now,
        )
        val paper = LibraryPaper(
            work = work,
            manifestations = listOf(manifestation),
            readingState = reading,
            localArtifacts = mapOf(manifestationId to artifact),
            collectionIds = setOf(CollectionId(1)),
            annotationCount = 2,
        )

        assertEquals("A paper", paper.work.title)
        assertEquals(author, paper.work.authors.single())
        assertEquals(identifier, paper.work.identifiers.single())
        assertEquals(manifestation, paper.manifestations.single())
        assertEquals(artifact, paper.localArtifacts[manifestationId])
        assertEquals(ReadingStatus.READING, paper.readingState?.status)
        assertEquals(2, paper.annotationCount)
        assertEquals(0.5, paper.readingState?.locator?.progression ?: -1.0, 0.0)
    }

    @Test
    fun `paper model invariants reject malformed values`() {
        assertThrows(IllegalArgumentException::class.java) { WorkId(" ") }
        assertThrows(IllegalArgumentException::class.java) { ManifestationId("") }
        assertThrows(IllegalArgumentException::class.java) { PaperIdentifier(IdentifierType.DOI, "x", "authority") }
        assertThrows(IllegalArgumentException::class.java) { PaperIdentifier(IdentifierType.PROVIDER, "x") }
        assertThrows(IllegalArgumentException::class.java) { PaperAuthor(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            PaperWork(workId, " ", createdAt = now, updatedAt = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperManifestation(manifestationId, workId, ManifestationType.PREPRINT, "", "record", updatedAt = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperManifestation(manifestationId, workId, ManifestationType.PREPRINT, "arxiv", "", updatedAt = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPaperArtifact("id", manifestationId, "/paper", "bad", 1, "application/pdf", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPaperArtifact("id", manifestationId, "/paper", sha, 0, "application/pdf", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPaperArtifact("id", manifestationId, "/paper", sha, 1, "text/plain", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryPaper(PaperWork(workId, "Title", createdAt = now, updatedAt = now), emptyList(), annotationCount = -1)
        }
    }

    @Test
    fun `reading and annotation models cover boundary values and failures`() {
        val locator = ReadingLocator(sha, "block", 0, 0, 0.0)
        assertEquals(0, locator.characterOffset)
        assertEquals(0, locator.pageIndex)
        assertEquals(1.0, ReadingLocator(progression = 1.0).progression, 0.0)
        assertThrows(IllegalArgumentException::class.java) { ReadingLocator(characterOffset = -1) }
        assertThrows(IllegalArgumentException::class.java) { ReadingLocator(pageIndex = -1) }
        assertThrows(IllegalArgumentException::class.java) { ReadingLocator(progression = 1.1) }

        val selection = AnnotationSelection(sha, "block", 0, 4, "", "Text", "")
        val annotation = Annotation(
            id = "annotation-1",
            workId = workId,
            documentSha256 = sha,
            blockId = "block",
            startOffset = 0,
            endOffset = 4,
            quotePrefix = "",
            quoteExact = "Text",
            quoteSuffix = "",
            pageIndex = null,
            note = null,
            color = null,
            createdAt = now,
            updatedAt = now,
        )
        assertEquals(selection.quoteExact, annotation.quoteExact)
        assertEquals("annotation-1", annotation.id)
        assertThrows(IllegalArgumentException::class.java) { annotation.copy(id = "") }
        assertThrows(IllegalArgumentException::class.java) { annotation.copy(documentSha256 = "bad") }
        assertThrows(IllegalArgumentException::class.java) { annotation.copy(endOffset = -1) }
        assertThrows(IllegalArgumentException::class.java) { annotation.copy(quoteExact = "") }
        assertThrows(IllegalArgumentException::class.java) { annotation.copy(pageIndex = -1) }
    }

    @Test
    fun `local PDF and saved-search value models expose deterministic identity and counts`() {
        val candidate = LocalPdfCandidate("token", "source", "paper.pdf", "Paper", 99)
        assertEquals("token", candidate.importToken)
        assertEquals("source", candidate.sourceKey)
        assertEquals("Paper", candidate.suggestedTitle)
        assertTrue(localPdfSourceKey("content://paper").matches(Regex("[0-9a-f]{64}")))
        val remote = RemotePaper("arxiv", "1", "Paper")
        val searchId = SavedSearchId("search")
        val hit = SavedSearchHit(
            id = SavedSearchHitId("hit"),
            searchId = searchId,
            paper = remote,
            fingerprint = sha,
            firstSeenAt = now,
            lastSeenAt = now,
            unread = true,
        )
        val search = SavedSearch(
            id = searchId,
            queryText = "paper",
            sources = listOf(SavedSearchSource("arxiv", lastCheckedAt = now)),
            createdAt = now,
        )
        val feed = SavedSearchFeed(search, listOf(hit))
        assertEquals(now, search.lastCheckedAt)
        assertEquals(1, feed.unreadCount)
        assertTrue(hit.unread)
        assertEquals("paper", normalizeSavedSearchQuery("  paper  "))
        assertEquals(null, normalizeSavedSearchQuery(" "))
        assertFalse(normalizeSavedSearchQuery("x".repeat(MAX_SAVED_SEARCH_QUERY_LENGTH + 1)) != null)
        assertThrows(IllegalArgumentException::class.java) {
            SavedSearch(searchId, " paper ", listOf(SavedSearchSource("arxiv")), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedSearch(searchId, "paper", emptyList(), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedSearch(searchId, "paper", listOf(SavedSearchSource("arxiv"), SavedSearchSource("arxiv")), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedSearchHit(SavedSearchHitId("hit"), searchId, remote, "bad", firstSeenAt = now, lastSeenAt = now, unread = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedSearchHit(SavedSearchHitId("hit"), searchId, remote, sha, firstSeenAt = now, lastSeenAt = now.minusSeconds(1), unread = false)
        }
    }

    @Test
    fun `reading history value object exposes duration and progression`() {
        val entry = ReadingHistoryEntry(workId, "Paper", now, Duration.ofMinutes(3), 2, 0.75)
        assertEquals(workId, entry.workId)
        assertEquals("Paper", entry.title)
        assertEquals(Duration.ofMinutes(3), entry.totalReadDuration)
        assertEquals(2, entry.sessionCount)
        assertEquals(0.75, entry.progression, 0.0)
    }
}
