package dev.paperreader.logic.data.repository

import dev.paperreader.logic.backup.BackupAnnotationProto
import dev.paperreader.logic.backup.BackupIdentifierProto
import dev.paperreader.logic.backup.BackupManifestationProto
import dev.paperreader.logic.backup.BackupWorkProto
import dev.paperreader.logic.backup.MetadataBackupError
import dev.paperreader.logic.backup.MetadataBackupProto
import dev.paperreader.logic.data.CollectionEntity
import dev.paperreader.logic.data.IdentifierEntity
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.SavedSearchHitEntity
import dev.paperreader.logic.data.SavedSearchSourceEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.WorkId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMetadataBackupModelsTest {
    @Test
    fun `canonical identifiers and work rows preserve identity while normalizing subjects`() {
        val work = BackupWorkProto(
            sourceId = "source-work",
            title = "A paper",
            subjects = listOf("z", "", "a"),
            identifiers = listOf(
                BackupIdentifierProto("DOI", "https://doi.org/10.1000/Example"),
                BackupIdentifierProto("DOI", "10.1000/example"),
                BackupIdentifierProto("PROVIDER", "record", "arxiv"),
            ),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val canonical = canonicalIdentifiers(work)
        assertEquals(2, canonical.size)
        assertTrue(canonical.any { it.type == IdentifierType.DOI && it.value == "10.1000/example" })
        assertTrue(canonical.any { it.type == IdentifierType.PROVIDER && it.authority == "arxiv" })

        val entity = work.toEntity(WorkId("w-1"))
        assertEquals("a\u001fz", entity.subjects)
        assertEquals("A paper", entity.title)
        assertEquals("w-1", entity.id)
    }

    @Test
    fun `manifestation merge keeps local fields and newest timestamp`() {
        val source = manifestation(version = "v2", landing = "https://incoming", pdf = "https://incoming.pdf")
        val plan = ManifestationPlan(
            source = source,
            targetId = ManifestationId("m-1"),
            existing = ManifestationEntity(
                id = "m-1", workId = "w-1", type = "PREPRINT", sourceProvider = "arxiv",
                sourceRecordId = "1234", version = "v1", landingPageUrl = "https://local",
                pdfUrl = "file://local", license = "CC-BY", publishedDateEpochDay = 10,
                updatedAtEpochMillis = 100,
            ),
        )

        val merged = plan.toEntity(WorkId("w-1"))
        assertEquals("v1", merged.version)
        assertEquals("https://local", merged.landingPageUrl)
        assertEquals("file://local", merged.pdfUrl)
        assertEquals("CC-BY", merged.license)
        assertEquals(100L, merged.updatedAtEpochMillis)

        val fresh = plan.copy(existing = null).toEntity(WorkId("w-1"))
        assertEquals("v2", fresh.version)
        assertEquals("https://incoming", fresh.landingPageUrl)
    }

    @Test
    fun `anchors summaries and invalid-data errors are typed`() {
        val annotation = BackupAnnotationProto(
            id = "a-1", workSourceId = "w-1", documentSha256 = "A".repeat(64).lowercase(),
            blockId = "block", startOffset = 0, endOffset = 4,
            quotePrefix = "", quoteExact = "text", quoteSuffix = "", createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val target = annotation.toEntity("w-1")
        assertTrue(target.sameAnchorAs(annotation, "w-1"))
        assertFalse(target.sameAnchorAs(annotation.copy(quoteExact = "other"), "w-1"))
        assertEquals("w-1", target.workId)

        val proto = MetadataBackupProto(
            formatMarker = "paper-reader-metadata",
            schemaVersion = 2,
            databaseVersion = 4,
            createdAtEpochMillis = 1,
            works = listOf(work()),
            collections = emptyList(),
            readingStates = emptyList(),
            histories = emptyList(),
            bookmarks = emptyList(),
            annotations = listOf(annotation),
        )
        val summary = summary(proto)
        assertEquals(1, summary.works)
        assertEquals(1, summary.annotations)
        assertEquals(0, summary.savedSearchHits)
        assertEquals(MetadataBackupError.Rejected("invalid_local_data", "Local metadata contains values that cannot be backed up safely"), invalidLocalDataError())
    }

    @Test
    fun `manifestation keys local identities and merged subjects are stable`() {
        val localSha = "a".repeat(64)
        val local = manifestation(sourceProvider = "LOCAL-PDF", record = localSha.uppercase())
        val localKey = manifestationKey(local)
        assertEquals("local-pdf", localKey.provider)
        assertEquals(localSha, localKey.record)
        assertEquals("m-local-$localSha", stableRestoredManifestationId(WorkId("w-1"), localKey))

        val remote = manifestation(sourceProvider = "ArXiv", record = "1234")
        val remoteKey = manifestationKey(remote)
        assertEquals("arxiv", remoteKey.provider)
        assertEquals("1234", remoteKey.record)
        assertTrue(stableRestoredManifestationId(WorkId("w-1"), remoteKey).startsWith("m-"))
        assertNotEquals(stableManifestationId(WorkId("w-1"), remoteKey), stableManifestationId(WorkId("w-2"), remoteKey))

        assertEquals("a\u001fz", mergeSubjects("z", "a\u001f"))
        assertEquals(localSha, workWithLocal(localSha).localPdfSha256OrNull())
        assertNull(workWithLocal(localSha, duplicate = true).localPdfSha256OrNull())
        assertNull(workWithLocal("not-a-sha").localPdfSha256OrNull())
    }

    @Test
    fun `saved-search source and hit merges choose newest metadata without losing local links`() {
        val existingSource = SavedSearchSourceEntity("s", "arxiv", 10, 9, "UNAVAILABLE", null)
        val incomingSource = SavedSearchSourceEntity("other", "other", 20, 19, "RATE_LIMITED", 30)
        val source = mergeSavedSearchSource(existingSource, incomingSource)
        assertEquals("other", source.searchId)
        assertEquals(20L, source.lastCheckedAtEpochMillis)
        assertEquals("RATE_LIMITED", source.failureKind)
        assertEquals(incomingSource, mergeSavedSearchSource(null, incomingSource))
        assertEquals(10L, mergeSavedSearchSource(existingSource, incomingSource.copy(lastCheckedAtEpochMillis = 1)).lastCheckedAtEpochMillis)
        assertNull(maxNullable(null, null))
        assertEquals(4L, maxNullable(null, 4))
        assertEquals(5L, maxNullable(5, null))

        val existingHit = hit(id = "old", linkedWorkId = "w-local", updated = 10, first = 1, last = 2, unread = true)
        val incomingHit = hit(id = "new", linkedWorkId = null, updated = 20, first = 0, last = 5, unread = false)
        assertEquals(incomingHit, mergeSavedSearchHit(null, incomingHit))
        assertEquals(existingHit, mergeSavedSearchHit(existingHit, null))
        val merged = mergeSavedSearchHit(existingHit, incomingHit)
        assertEquals("new", merged.id)
        assertEquals("w-local", merged.linkedWorkId)
        assertEquals(0L, merged.firstSeenAtEpochMillis)
        assertEquals(5L, merged.lastSeenAtEpochMillis)
        assertTrue(merged.unread)
    }

    private fun workWithLocal(sha: String, duplicate: Boolean = false) = BackupWorkProto(
        sourceId = "local-work",
        title = "Local",
        manifestations = listOf(
            manifestation(sourceProvider = "local-pdf", record = sha),
            if (duplicate) manifestation(sourceProvider = "LOCAL-PDF", record = "b".repeat(64)) else manifestation(sourceProvider = "arxiv", record = "record"),
        ),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun work() = BackupWorkProto(
        sourceId = "w-1",
        title = "A paper",
        identifiers = listOf(BackupIdentifierProto("DOI", "10.1000/x")),
        manifestations = listOf(manifestation()),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun manifestation(
        sourceProvider: String = "arxiv",
        record: String = "1234",
        version: String? = "v1",
        landing: String? = "https://example.org",
        pdf: String? = "https://example.org/paper.pdf",
    ) = BackupManifestationProto(
        sourceId = "m-$record",
        type = ManifestationType.PREPRINT.name,
        sourceProvider = sourceProvider,
        sourceRecordId = record,
        version = version,
        landingPageUrl = landing,
        pdfUrl = pdf,
        updatedAtEpochMillis = 10,
    )

    private fun hit(
        id: String,
        linkedWorkId: String?,
        updated: Long,
        first: Long,
        last: Long,
        unread: Boolean,
    ) = SavedSearchHitEntity(
        id = id,
        searchId = "search",
        providerId = "arxiv",
        providerRecordId = "1234",
        fingerprint = "fingerprint",
        recordPayload = "payload",
        linkedWorkId = linkedWorkId,
        providerUpdatedAtEpochMillis = updated,
        firstSeenAtEpochMillis = first,
        lastSeenAtEpochMillis = last,
        unread = unread,
    )
}
