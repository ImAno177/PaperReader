package dev.paperreader.logic.backup

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.domain.normalizeCollectionName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf

internal const val FORMAT_MARKER = "paper-reader-metadata"
private const val MAX_ITEMS = 50_000
private const val MAX_STRING_CHARS = 200_000
private const val MAX_EPOCH_MILLIS = 253402300799999L
private val SHA_256 = Regex("[0-9a-f]{64}")

internal sealed interface DecodedBackup {
    data class Valid(val value: MetadataBackupProto) : DecodedBackup
    data class Invalid(val error: MetadataBackupError.Rejected) : DecodedBackup
}

internal class MetadataBackupEncodingException(
    val rejection: MetadataBackupError.Rejected,
) : IllegalArgumentException(rejection.detail)

private data class ManifestationLogicalKey(
    val provider: String,
    val recordId: String,
    val type: String,
    val version: String?,
)

private data class MembershipKey(val workSourceId: String, val collectionName: String)
private data class BookmarkKey(
    val workSourceId: String,
    val manifestationSourceId: String,
    val documentSha256: String,
    val pageIndex: Int,
)

@OptIn(ExperimentalSerializationApi::class)
internal object MetadataBackupCodec {
    /** Encodes only archives that this build can restore. */
    fun encode(value: MetadataBackupProto): ByteArray = try {
        val validated = validate(value)
        if (validated is DecodedBackup.Invalid) throw MetadataBackupEncodingException(validated.error)

        val payload = ProtoBuf.encodeToByteArray(MetadataBackupProto.serializer(), value)
        if (payload.size > MAX_METADATA_BACKUP_PAYLOAD_BYTES) {
            throw MetadataBackupEncodingException(
                rejected("payload_too_large", "Backup payload exceeds $MAX_METADATA_BACKUP_PAYLOAD_BYTES bytes"),
            )
        }
        val archive = ByteArrayOutputStream(payload.size + 128).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(METADATA_BACKUP_ENTRY_NAME))
                zip.write(payload)
                zip.closeEntry()
            }
            output.toByteArray()
        }
        if (archive.size > MAX_METADATA_BACKUP_ARCHIVE_BYTES) {
            throw MetadataBackupEncodingException(
                rejected("archive_too_large", "Backup archive exceeds $MAX_METADATA_BACKUP_ARCHIVE_BYTES bytes"),
            )
        }
        archive
    } catch (failure: MetadataBackupEncodingException) {
        throw failure
    } catch (_: SerializationException) {
        throw invalidLocalData()
    } catch (_: IllegalArgumentException) {
        throw invalidLocalData()
    } catch (_: IllegalStateException) {
        throw invalidLocalData()
    }

    fun decode(bytes: ByteArray): DecodedBackup {
        if (bytes.size > MAX_METADATA_BACKUP_ARCHIVE_BYTES) {
            return invalid("archive_too_large", "Archive exceeds $MAX_METADATA_BACKUP_ARCHIVE_BYTES bytes")
        }
        return try {
            val payload = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val first = zip.nextEntry ?: return invalid("missing_entry", "Backup entry is missing")
                if (first.name != METADATA_BACKUP_ENTRY_NAME || first.isDirectory) {
                    return invalid("invalid_entry", "Backup must contain only $METADATA_BACKUP_ENTRY_NAME")
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (count == 0) return invalid("malformed_archive", "Archive returned an empty read")
                    total += count
                    if (total > MAX_METADATA_BACKUP_PAYLOAD_BYTES) {
                        return invalid("payload_too_large", "Uncompressed payload exceeds limit")
                    }
                    output.write(buffer, 0, count)
                }
                if (zip.nextEntry != null) return invalid("extra_entry", "Backup contains extra entries")
                output.toByteArray()
            }
            if (payload.isEmpty()) return invalid("empty_payload", "Payload is empty")
            validate(ProtoBuf.decodeFromByteArray(MetadataBackupProto.serializer(), payload))
        } catch (_: SerializationException) {
            invalid("malformed_payload", "Payload is not valid protobuf")
        } catch (_: IOException) {
            invalid("malformed_archive", "Archive is truncated or invalid")
        } catch (_: IllegalArgumentException) {
            invalid("invalid_payload", "Payload contains invalid values")
        }
    }

    private fun validate(value: MetadataBackupProto): DecodedBackup {
        if (value.formatMarker != FORMAT_MARKER) return invalid("format_mismatch", "Unsupported backup format")
        if (value.schemaVersion != METADATA_BACKUP_SCHEMA_VERSION) {
            return invalid("schema_unsupported", "Unsupported backup schema")
        }
        if (value.databaseVersion !in 1..METADATA_BACKUP_DATABASE_VERSION) {
            return invalid("database_unsupported", "Unsupported database version")
        }
        if (!validEpochMillis(value.createdAtEpochMillis)) return invalid("invalid_time", "Invalid creation time")
        if (
            listOf(
                value.works.size,
                value.collections.size,
                value.memberships.size,
                value.readingStates.size,
                value.histories.size,
                value.bookmarks.size,
                value.annotations.size,
            ).any { it > MAX_ITEMS }
        ) {
            return invalid("too_many_items", "Backup item limit exceeded")
        }
        if (
            value.works.sumOf { it.authors.size.toLong() } > MAX_ITEMS ||
            value.works.sumOf { it.identifiers.size.toLong() } > MAX_ITEMS ||
            value.works.sumOf { it.manifestations.size.toLong() } > MAX_ITEMS
        ) {
            return invalid("too_many_items", "Nested backup item limit exceeded")
        }

        val exactAliases = HashSet<String>()
        val identifierlessLocalDocuments = HashSet<String>()
        val workSourceIds = HashSet<String>()
        val workManifestations = HashMap<String, Set<String>>()
        value.works.forEach { work ->
            checkString(work.sourceId)
            checkText(work.title)
            checkText(work.abstractText)
            if (work.sourceId.isBlank() || work.title.isBlank()) {
                return invalid("invalid_work", "Work identity or title is blank")
            }
            if (!workSourceIds.add(work.sourceId)) return invalid("duplicate_work", "Duplicate work source ID")
            require(validEpochMillis(work.createdAtEpochMillis) && validEpochMillis(work.updatedAtEpochMillis))
            require(validEpochDay(work.publishedDateEpochDay))
            work.subjects.forEach(::checkString)
            require(work.subjects.size == work.subjects.toSet().size)
            val authorPositions = HashSet<Int>()
            work.authors.forEach { author ->
                require(author.position >= 0 && authorPositions.add(author.position))
                checkString(author.displayName)
                checkString(author.givenName)
                checkString(author.familyName)
                checkString(author.orcid)
                require(author.displayName.isNotBlank())
            }
            work.identifiers.forEach { identifier ->
                checkString(identifier.type)
                checkString(identifier.value)
                checkString(identifier.authority)
                val canonical = IdentifierNormalizer.canonical(
                    PaperIdentifier(
                        type = IdentifierType.valueOf(identifier.type),
                        value = identifier.value,
                        authority = identifier.authority.takeIf(String::isNotBlank),
                    ),
                )
                val key = IdentityResolver.exactKeys(listOf(canonical)).single()
                if (!exactAliases.add(key)) return invalid("duplicate_alias", "Exact identifier occurs more than once")
            }

            val sourceIds = HashSet<String>()
            val logicalKeys = HashSet<ManifestationLogicalKey>()
            work.manifestations.forEach { manifestation ->
                checkString(manifestation.sourceId)
                checkString(manifestation.type)
                checkString(manifestation.sourceProvider)
                checkString(manifestation.sourceRecordId)
                checkString(manifestation.version)
                checkString(manifestation.landingPageUrl)
                checkString(manifestation.pdfUrl)
                checkString(manifestation.license)
                ManifestationType.valueOf(manifestation.type)
                require(manifestation.landingPageUrl == null || manifestation.landingPageUrl.isSafeWebUrl())
                require(manifestation.pdfUrl == null || manifestation.pdfUrl.isSafeWebUrl())
                require(
                    manifestation.sourceId.isNotBlank() &&
                        manifestation.sourceProvider.isNotBlank() &&
                        manifestation.sourceRecordId.isNotBlank(),
                )
                if (!sourceIds.add(manifestation.sourceId)) {
                    return invalid("duplicate_manifestation", "Duplicate manifestation source ID")
                }
                val logicalKey = ManifestationLogicalKey(
                    provider = manifestation.sourceProvider.lowercase(Locale.ROOT),
                    recordId = manifestation.sourceRecordId,
                    type = manifestation.type,
                    version = manifestation.version,
                )
                if (!logicalKeys.add(logicalKey)) {
                    return invalid("duplicate_manifestation", "Duplicate logical manifestation")
                }
                require(validEpochMillis(manifestation.updatedAtEpochMillis))
                require(validEpochDay(manifestation.publishedDateEpochDay))
            }
            if (work.identifiers.isEmpty()) {
                val localPdfSha256 = work.manifestations
                    .asSequence()
                    .filter { it.sourceProvider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true) }
                    .map { it.sourceRecordId.lowercase(Locale.ROOT) }
                    .filter { it.matches(SHA_256) }
                    .distinct()
                    .toList()
                    .singleOrNull()
                    ?: return invalid(
                        "identifierless_work",
                        "Identifierless work has no unambiguous local PDF identity",
                    )
                if (!identifierlessLocalDocuments.add(localPdfSha256)) {
                    return invalid("duplicate_alias", "Local PDF identity occurs more than once")
                }
            }
            workManifestations[work.sourceId] = sourceIds
        }

        val collectionNames = HashSet<String>()
        value.collections.forEach { collection ->
            checkString(collection.name)
            val normalized = normalizeCollectionName(collection.name)
            require(
                normalized == collection.name &&
                    collection.sortOrder >= 0 &&
                    validEpochMillis(collection.createdAtEpochMillis) &&
                    validEpochMillis(collection.updatedAtEpochMillis) &&
                    collection.createdAtEpochMillis <= collection.updatedAtEpochMillis,
            )
            if (!collectionNames.add(collection.name.lowercase(Locale.ROOT))) {
                return invalid("duplicate_collection", "Duplicate collection name")
            }
        }

        val membershipKeys = HashSet<MembershipKey>()
        value.memberships.forEach { membership ->
            checkString(membership.workSourceId)
            checkString(membership.collectionName)
            require(membership.workSourceId in workSourceIds)
            val collectionKey = membership.collectionName.lowercase(Locale.ROOT)
            require(collectionKey in collectionNames)
            if (!membershipKeys.add(MembershipKey(membership.workSourceId, collectionKey))) {
                return invalid("duplicate_membership", "Duplicate collection membership")
            }
        }

        val readingStateWorks = HashSet<String>()
        value.readingStates.forEach { state ->
            checkString(state.workSourceId)
            checkString(state.manifestationSourceId)
            checkString(state.documentSha256)
            checkString(state.blockId)
            checkString(state.status)
            ReadingStatus.valueOf(state.status)
            require(state.workSourceId in workSourceIds)
            require(readingStateWorks.add(state.workSourceId))
            require(
                state.manifestationSourceId == null ||
                    state.manifestationSourceId in workManifestations[state.workSourceId].orEmpty(),
            )
            state.documentSha256?.let(::checkSha)
            require(
                state.characterOffset >= 0 &&
                    (state.pageIndex == null || state.pageIndex >= 0) &&
                    state.progression.isFinite() &&
                    state.progression in 0.0..1.0 &&
                    validEpochMillis(state.updatedAtEpochMillis),
            )
        }

        val historyWorks = HashSet<String>()
        value.histories.forEach { history ->
            checkString(history.workSourceId)
            require(history.workSourceId in workSourceIds && historyWorks.add(history.workSourceId))
            require(
                validEpochMillis(history.lastReadAtEpochMillis) &&
                    history.totalReadDurationMillis >= 0 &&
                    history.sessionCount >= 0,
            )
        }

        val bookmarkKeys = HashSet<BookmarkKey>()
        value.bookmarks.forEach { bookmark ->
            checkString(bookmark.workSourceId)
            checkString(bookmark.manifestationSourceId)
            checkSha(bookmark.documentSha256)
            require(bookmark.workSourceId in workSourceIds)
            require(bookmark.manifestationSourceId in workManifestations[bookmark.workSourceId].orEmpty())
            require(bookmark.pageIndex >= 0 && validEpochMillis(bookmark.createdAtEpochMillis))
            if (
                !bookmarkKeys.add(
                    BookmarkKey(
                        bookmark.workSourceId,
                        bookmark.manifestationSourceId,
                        bookmark.documentSha256,
                        bookmark.pageIndex,
                    ),
                )
            ) {
                return invalid("duplicate_bookmark", "Duplicate exact-document bookmark")
            }
        }

        val annotationIds = HashSet<String>()
        value.annotations.forEach { annotation ->
            checkString(annotation.id)
            checkString(annotation.workSourceId)
            checkSha(annotation.documentSha256)
            checkString(annotation.blockId)
            checkText(annotation.quotePrefix)
            checkText(annotation.quoteExact)
            checkText(annotation.quoteSuffix)
            checkText(annotation.note)
            checkString(annotation.color)
            require(annotation.id.isNotBlank() && annotation.workSourceId in workSourceIds)
            if (!annotationIds.add(annotation.id)) return invalid("duplicate_annotation", "Duplicate annotation ID")
            require(
                annotation.blockId.isNotBlank() &&
                    annotation.startOffset >= 0 &&
                    annotation.endOffset >= annotation.startOffset &&
                    annotation.quoteExact.isNotEmpty() &&
                    (annotation.pageIndex == null || annotation.pageIndex >= 0) &&
                    validEpochMillis(annotation.createdAtEpochMillis) &&
                    validEpochMillis(annotation.updatedAtEpochMillis) &&
                    annotation.createdAtEpochMillis <= annotation.updatedAtEpochMillis,
            )
        }
        return DecodedBackup.Valid(value)
    }

    private fun checkString(value: String?) {
        require(value == null || (value.length <= MAX_STRING_CHARS && value.none(Char::isISOControl)))
    }

    private fun checkText(value: String?) {
        require(value == null || (value.length <= MAX_STRING_CHARS && '\u0000' !in value))
    }

    private fun checkSha(value: String) {
        require(SHA_256.matches(value)) { "SHA-256 must be 64 canonical lowercase hexadecimal characters" }
    }

    private fun validEpochMillis(value: Long): Boolean = value in 0..MAX_EPOCH_MILLIS

    private fun validEpochDay(value: Long?): Boolean = value == null || value in
        LocalDate.MIN.toEpochDay()..LocalDate.MAX.toEpochDay()

    private fun invalid(code: String, detail: String) = DecodedBackup.Invalid(rejected(code, detail))

    private fun rejected(code: String, detail: String) = MetadataBackupError.Rejected(code, detail)

    private fun invalidLocalData() = MetadataBackupEncodingException(
        rejected("invalid_local_data", "Local metadata contains values that cannot be backed up safely"),
    )
}

private fun String.isSafeWebUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)
