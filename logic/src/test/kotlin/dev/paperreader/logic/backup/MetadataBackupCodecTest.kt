@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.paperreader.logic.backup

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBackupCodecTest {
    @Test
    fun `protobuf payload round trips in one entry zip`() {
        val value = fixture()

        val decoded = MetadataBackupCodec.decode(MetadataBackupCodec.encode(value))

        assertTrue(decoded is DecodedBackup.Valid)
        assertEquals(value, (decoded as DecodedBackup.Valid).value)
    }

    @Test
    fun `multiline scientific text remains valid backup content`() {
        val work = fixture().works.single().copy(abstractText = "Line one\nLine two\twith a table")
        val annotation = annotation().copy(note = "First thought\nSecond thought", quoteExact = "A\nB")
        val value = fixture().copy(works = listOf(work), annotations = listOf(annotation))

        val decoded = MetadataBackupCodec.decode(MetadataBackupCodec.encode(value)) as DecodedBackup.Valid

        assertEquals(value, decoded.value)
    }

    @Test
    fun `older database version with current wire schema remains readable`() {
        val result = MetadataBackupCodec.decode(MetadataBackupCodec.encode(fixture().copy(databaseVersion = 1)))
        assertTrue(result is DecodedBackup.Valid)
    }

    @Test
    fun `future schema and database versions are rejected before restore`() {
        assertRejected(fixture().copy(schemaVersion = 99), "schema_unsupported")
        assertRejected(fixture().copy(databaseVersion = 99), "database_unsupported")
    }

    @Test
    fun `truncated and oversized archives are rejected`() {
        val bytes = MetadataBackupCodec.encode(fixture())
        assertTrue(MetadataBackupCodec.decode(bytes.copyOf(bytes.size / 2)) is DecodedBackup.Invalid)
        assertEquals(
            "archive_too_large",
            (MetadataBackupCodec.decode(ByteArray(MAX_METADATA_BACKUP_ARCHIVE_BYTES + 1)) as DecodedBackup.Invalid)
                .error.code,
        )
    }

    @Test
    fun `identifierless work and dangling references are rejected`() {
        assertRejected(
            fixture().copy(works = listOf(fixtureWork().copy(identifiers = emptyList()))),
            "identifierless_work",
        )
        assertRejected(
            fixture().copy(
                collections = listOf(collection()),
                memberships = listOf(BackupMembershipProto("missing", "Reading list")),
            ),
            "invalid_payload",
        )
        assertRejected(
            fixture().copy(bookmarks = listOf(bookmark().copy(manifestationSourceId = "missing"))),
            "invalid_payload",
        )
        assertRejected(
            fixture().copy(annotations = listOf(annotation().copy(workSourceId = "missing"))),
            "invalid_payload",
        )
    }

    @Test
    fun `identifierless local PDF uses one exact SHA identity`() {
        val local = fixtureWork().copy(
            identifiers = emptyList(),
            manifestations = listOf(
                BackupManifestationProto(
                    sourceId = "m-local-${"a".repeat(64)}",
                    type = "OTHER",
                    sourceProvider = "local-pdf",
                    sourceRecordId = "a".repeat(64),
                    updatedAtEpochMillis = 1,
                ),
            ),
        )

        assertTrue(
            MetadataBackupCodec.decode(MetadataBackupCodec.encode(fixture().copy(works = listOf(local))))
                is DecodedBackup.Valid,
        )
        assertRejected(
            fixture().copy(
                works = listOf(
                    local,
                    local.copy(
                        sourceId = "w-second",
                        manifestations = local.manifestations.map { it.copy(sourceId = "m-second") },
                    ),
                ),
            ),
            "duplicate_alias",
        )
    }

    @Test
    fun `all document hashes must be canonical lowercase SHA-256`() {
        assertRejected(
            fixture().copy(readingStates = listOf(readingState().copy(documentSha256 = "bad"))),
            "invalid_payload",
        )
        assertRejected(
            fixture().copy(bookmarks = listOf(bookmark().copy(documentSha256 = "A".repeat(64)))),
            "invalid_payload",
        )
        assertRejected(
            fixture().copy(annotations = listOf(annotation().copy(documentSha256 = "A".repeat(64)))),
            "invalid_payload",
        )
    }

    @Test
    fun `duplicate relational records are rejected instead of collapsed by Room`() {
        val collection = collection()
        val membership = BackupMembershipProto("w-old", collection.name)
        assertRejected(
            fixture().copy(collections = listOf(collection), memberships = listOf(membership, membership)),
            "duplicate_membership",
        )
        val state = readingState()
        assertRejected(fixture().copy(readingStates = listOf(state, state)), "invalid_payload")
        val history = BackupHistoryProto("w-old", 1, 1, 1)
        assertRejected(fixture().copy(histories = listOf(history, history)), "invalid_payload")
        val bookmark = bookmark()
        assertRejected(fixture().copy(bookmarks = listOf(bookmark, bookmark)), "duplicate_bookmark")
        val annotation = annotation()
        assertRejected(fixture().copy(annotations = listOf(annotation, annotation)), "duplicate_annotation")
    }

    @Test
    fun `duplicate author positions and logical manifestations are rejected`() {
        val author = BackupAuthorProto(0, "Ada Lovelace")
        assertRejected(
            fixture().copy(works = listOf(fixtureWork().copy(authors = listOf(author, author)))),
            "invalid_payload",
        )
        val manifestation = fixtureWork().manifestations.single()
        assertRejected(
            fixture().copy(
                works = listOf(
                    fixtureWork().copy(
                        manifestations = listOf(manifestation, manifestation.copy(sourceId = "m-copy")),
                    ),
                ),
            ),
            "duplicate_manifestation",
        )
    }

    @Test
    fun `control characters cannot enter identity keys`() {
        assertRejected(
            fixture().copy(works = listOf(fixtureWork().copy(sourceId = "work\u001fother"))),
            "invalid_payload",
        )
        assertRejected(
            fixture().copy(
                works = listOf(
                    fixtureWork().copy(
                        manifestations = listOf(
                            fixtureWork().manifestations.single().copy(sourceRecordId = "record\u001fother"),
                        ),
                    ),
                ),
            ),
            "invalid_payload",
        )
    }

    @Test
    fun `timestamps outside supported Instant range are rejected`() {
        assertRejected(fixture().copy(createdAtEpochMillis = Long.MAX_VALUE), "invalid_time")
        assertRejected(
            fixture().copy(collections = listOf(collection().copy(updatedAtEpochMillis = Long.MAX_VALUE))),
            "invalid_payload",
        )
    }

    @Test
    fun `local save time may be newer than provider metadata update time`() {
        val work = fixtureWork().copy(
            createdAtEpochMillis = 2_000,
            updatedAtEpochMillis = 1_000,
        )

        val decoded = MetadataBackupCodec.decode(
            MetadataBackupCodec.encode(fixture().copy(works = listOf(work))),
        )

        assertTrue(decoded is DecodedBackup.Valid)
    }

    @Test
    fun `encoder reports a typed payload size failure`() {
        val largeText = "x".repeat(200_000)
        val works = (0 until 45).map { index ->
            fixtureWork().copy(
                sourceId = "work-$index",
                abstractText = largeText,
                identifiers = listOf(BackupIdentifierProto("DOI", "10.1000/large-$index")),
                manifestations = emptyList(),
            )
        }

        val failure = assertThrows(MetadataBackupEncodingException::class.java) {
            MetadataBackupCodec.encode(fixture().copy(works = works))
        }

        assertEquals("payload_too_large", failure.rejection.code)
    }

    @Test
    fun `encoder maps malformed local enums identifiers and timestamps to typed rejection`() {
        val invalidValues = listOf(
            fixture().copy(
                works = listOf(
                    fixtureWork().copy(
                        identifiers = listOf(BackupIdentifierProto("NOT_AN_IDENTIFIER_TYPE", "value")),
                    ),
                ),
            ),
            fixture().copy(
                works = listOf(
                    fixtureWork().copy(
                        identifiers = listOf(BackupIdentifierProto("DOI", "not-a-doi")),
                    ),
                ),
            ),
            fixture().copy(
                works = listOf(fixtureWork().copy(updatedAtEpochMillis = Long.MAX_VALUE)),
            ),
        )

        invalidValues.forEach { value ->
            val failure = assertThrows(MetadataBackupEncodingException::class.java) {
                MetadataBackupCodec.encode(value)
            }
            assertEquals("invalid_local_data", failure.rejection.code)
        }
    }

    @Test
    fun `unsafe manifestation URLs are rejected on export and import`() {
        val manifestation = fixtureWork().manifestations.single().copy(
            landingPageUrl = "javascript:alert(1)",
            pdfUrl = "file:///private/paper.pdf",
        )
        val value = fixture().copy(
            works = listOf(fixtureWork().copy(manifestations = listOf(manifestation))),
        )

        val exportFailure = assertThrows(MetadataBackupEncodingException::class.java) {
            MetadataBackupCodec.encode(value)
        }
        assertEquals("invalid_local_data", exportFailure.rejection.code)
        assertRejected(value, "invalid_payload")
    }

    private fun assertRejected(value: MetadataBackupProto, expectedCode: String) {
        val result = MetadataBackupCodec.decode(rawArchive(value))
        assertTrue("Expected invalid backup, got $result", result is DecodedBackup.Invalid)
        assertEquals(expectedCode, (result as DecodedBackup.Invalid).error.code)
    }

    private fun rawArchive(value: MetadataBackupProto): ByteArray {
        val payload = ProtoBuf.encodeToByteArray(MetadataBackupProto.serializer(), value)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(METADATA_BACKUP_ENTRY_NAME))
                zip.write(payload)
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }

    private fun fixture() = MetadataBackupProto(
        formatMarker = FORMAT_MARKER,
        schemaVersion = METADATA_BACKUP_SCHEMA_VERSION,
        databaseVersion = METADATA_BACKUP_DATABASE_VERSION,
        createdAtEpochMillis = 1,
        works = listOf(fixtureWork()),
    )

    private fun fixtureWork() = BackupWorkProto(
        sourceId = "w-old",
        title = "Paper",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        identifiers = listOf(BackupIdentifierProto("DOI", "10.1000/example")),
        manifestations = listOf(
            BackupManifestationProto(
                sourceId = "m-old",
                type = "PREPRINT",
                sourceProvider = "arxiv",
                sourceRecordId = "1234.5678",
                updatedAtEpochMillis = 1,
            ),
        ),
    )

    private fun collection() = BackupCollectionProto("Reading list", 0, 1, 1)

    private fun readingState() = BackupReadingStateProto(
        workSourceId = "w-old",
        manifestationSourceId = "m-old",
        documentSha256 = "a".repeat(64),
        characterOffset = 0,
        pageIndex = 0,
        progression = 0.25,
        status = "READING",
        updatedAtEpochMillis = 1,
    )

    private fun bookmark() = BackupBookmarkProto(
        workSourceId = "w-old",
        manifestationSourceId = "m-old",
        documentSha256 = "a".repeat(64),
        pageIndex = 0,
        createdAtEpochMillis = 1,
    )

    private fun annotation() = BackupAnnotationProto(
        id = "annotation-1",
        workSourceId = "w-old",
        documentSha256 = "a".repeat(64),
        blockId = "page-0",
        startOffset = 0,
        endOffset = 4,
        quotePrefix = "",
        quoteExact = "Text",
        quoteSuffix = "",
        pageIndex = 0,
        note = "Note",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )
}
