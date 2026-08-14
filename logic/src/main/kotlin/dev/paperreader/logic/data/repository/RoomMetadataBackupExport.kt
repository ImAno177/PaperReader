package dev.paperreader.logic.data.repository

import dev.paperreader.logic.backup.BackupAnnotationProto
import dev.paperreader.logic.backup.BackupAuthorProto
import dev.paperreader.logic.backup.BackupBookmarkProto
import dev.paperreader.logic.backup.BackupCollectionProto
import dev.paperreader.logic.backup.BackupHistoryProto
import dev.paperreader.logic.backup.BackupIdentifierProto
import dev.paperreader.logic.backup.BackupManifestationProto
import dev.paperreader.logic.backup.BackupMembershipProto
import dev.paperreader.logic.backup.BackupReadingStateProto
import dev.paperreader.logic.backup.BackupSavedSearchHitProto
import dev.paperreader.logic.backup.BackupSavedSearchProto
import dev.paperreader.logic.backup.BackupSavedSearchSourceProto
import dev.paperreader.logic.backup.BackupWorkProto
import dev.paperreader.logic.backup.FORMAT_MARKER
import dev.paperreader.logic.backup.METADATA_BACKUP_DATABASE_VERSION
import dev.paperreader.logic.backup.METADATA_BACKUP_SCHEMA_VERSION
import dev.paperreader.logic.backup.MetadataBackupProto
import dev.paperreader.logic.data.AuthorEntity
import dev.paperreader.logic.data.CollectionEntity
import dev.paperreader.logic.data.IdentifierEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.SavedSearchHitEntity
import dev.paperreader.logic.data.SavedSearchRecordCodec
import dev.paperreader.logic.data.SavedSearchSourceEntity
import dev.paperreader.logic.data.WorkEntity
import java.time.Clock
import java.util.Locale

internal suspend fun buildMetadataBackupProto(
    database: LibraryDatabase,
    clock: Clock,
): MetadataBackupProto {
    val dao = database.libraryDao()
    val bookmarkDao = database.readingBookmarkDao()
    val savedSearchDao = database.savedSearchDao()
    val works = dao.getAllWorks()
    val authors = dao.getAllAuthors().groupBy(AuthorEntity::workId)
    val identifiers = dao.getAllIdentifiers().groupBy(IdentifierEntity::workId)
    val manifestations = dao.getAllManifestations().groupBy(ManifestationEntity::workId)
    val collections = dao.getAllCollections()
    val collectionNames = collections.associateBy(CollectionEntity::id, CollectionEntity::name)
    val workIds = works.mapTo(HashSet(), WorkEntity::id)
    val savedSearchSources = savedSearchDao.getAllSources().groupBy(SavedSearchSourceEntity::searchId)
    val savedSearchHits = savedSearchDao.getAllHits().groupBy(SavedSearchHitEntity::searchId)
    return MetadataBackupProto(
        formatMarker = FORMAT_MARKER,
        schemaVersion = METADATA_BACKUP_SCHEMA_VERSION,
        databaseVersion = METADATA_BACKUP_DATABASE_VERSION,
        createdAtEpochMillis = clock.millis(),
        works = works.map { work ->
            BackupWorkProto(
                sourceId = work.id,
                title = work.title,
                abstractText = work.abstractText,
                subjects = work.subjects.split(SUBJECT_SEPARATOR).filter(String::isNotBlank),
                publishedDateEpochDay = work.publishedDateEpochDay,
                createdAtEpochMillis = work.createdAtEpochMillis,
                updatedAtEpochMillis = work.updatedAtEpochMillis,
                authors = authors[work.id].orEmpty().map { author ->
                    BackupAuthorProto(
                        position = author.position,
                        displayName = author.displayName,
                        givenName = author.givenName,
                        familyName = author.familyName,
                        orcid = author.orcid,
                    )
                },
                identifiers = identifiers[work.id].orEmpty().map { identifier ->
                    val canonical = canonicalIdentifier(identifier)
                    BackupIdentifierProto(
                        type = canonical.type.name,
                        value = canonical.value,
                        authority = canonical.authority.orEmpty(),
                    )
                },
                manifestations = manifestations[work.id].orEmpty().map { manifestation ->
                    BackupManifestationProto(
                        sourceId = manifestation.id,
                        type = manifestation.type,
                        sourceProvider = manifestation.sourceProvider,
                        sourceRecordId = manifestation.sourceRecordId,
                        version = manifestation.version,
                        landingPageUrl = manifestation.landingPageUrl,
                        pdfUrl = manifestation.pdfUrl,
                        license = manifestation.license,
                        publishedDateEpochDay = manifestation.publishedDateEpochDay,
                        updatedAtEpochMillis = manifestation.updatedAtEpochMillis,
                    )
                },
            )
        },
        collections = collections.map { collection ->
            BackupCollectionProto(
                name = collection.name,
                sortOrder = collection.sortOrder,
                createdAtEpochMillis = collection.createdAtEpochMillis,
                updatedAtEpochMillis = collection.updatedAtEpochMillis,
            )
        },
        memberships = dao.getAllWorkCollections().map { membership ->
            val name = checkNotNull(collectionNames[membership.collectionId]) {
                "Collection membership ${membership.collectionId} has no collection"
            }
            BackupMembershipProto(membership.workId, name)
        },
        readingStates = dao.getAllReadingStates().map { state ->
            BackupReadingStateProto(
                workSourceId = state.workId,
                manifestationSourceId = state.manifestationId,
                documentSha256 = state.documentSha256?.lowercase(Locale.ROOT),
                blockId = state.blockId,
                characterOffset = state.characterOffset,
                pageIndex = state.pageIndex,
                progression = state.progression,
                status = state.status,
                updatedAtEpochMillis = state.updatedAtEpochMillis,
            )
        },
        histories = dao.getAllReadingHistory().map { history ->
            BackupHistoryProto(
                workSourceId = history.workId,
                lastReadAtEpochMillis = history.lastReadAtEpochMillis,
                totalReadDurationMillis = history.totalReadDurationMillis,
                sessionCount = history.sessionCount,
            )
        },
        bookmarks = bookmarkDao.getAll().map { bookmark ->
            BackupBookmarkProto(
                workSourceId = bookmark.workId,
                manifestationSourceId = bookmark.manifestationId,
                documentSha256 = bookmark.documentSha256,
                pageIndex = bookmark.pageIndex,
                createdAtEpochMillis = bookmark.createdAtEpochMillis,
            )
        },
        annotations = dao.getAllAnnotations().map { annotation ->
            BackupAnnotationProto(
                id = annotation.id,
                workSourceId = annotation.workId,
                documentSha256 = annotation.documentSha256.lowercase(Locale.ROOT),
                blockId = annotation.blockId,
                startOffset = annotation.startOffset,
                endOffset = annotation.endOffset,
                quotePrefix = annotation.quotePrefix,
                quoteExact = annotation.quoteExact,
                quoteSuffix = annotation.quoteSuffix,
                pageIndex = annotation.pageIndex,
                note = annotation.note,
                color = annotation.color,
                createdAtEpochMillis = annotation.createdAtEpochMillis,
                updatedAtEpochMillis = annotation.updatedAtEpochMillis,
            )
        },
        savedSearches = savedSearchDao.getAllSearches().map { search ->
            val sources = savedSearchSources[search.id].orEmpty().sortedBy(SavedSearchSourceEntity::providerId)
            check(sources.isNotEmpty()) { "Saved search ${search.id} has no sources" }
            BackupSavedSearchProto(
                queryText = search.queryText,
                createdAtEpochMillis = search.createdAtEpochMillis,
                sources = sources.map { source ->
                    BackupSavedSearchSourceProto(
                        providerId = source.providerId,
                        lastCheckedAtEpochMillis = source.lastCheckedAtEpochMillis,
                        lastSuccessAtEpochMillis = source.lastSuccessAtEpochMillis,
                        failureKind = source.failureKind,
                        retryAfterEpochMillis = source.retryAfterEpochMillis,
                    )
                },
                hits = savedSearchHits[search.id].orEmpty()
                    .sortedWith(compareBy(SavedSearchHitEntity::providerId, SavedSearchHitEntity::providerRecordId))
                    .map { hit ->
                        check(hit.linkedWorkId == null || hit.linkedWorkId in workIds) {
                            "Saved-search hit ${hit.id} links to a missing work"
                        }
                        val record = SavedSearchRecordCodec.decode(hit.recordPayload)
                        check(
                            record.providerId.lowercase(Locale.ROOT) == hit.providerId &&
                                record.providerRecordId == hit.providerRecordId &&
                                SavedSearchRecordCodec.fingerprint(hit.recordPayload) == hit.fingerprint,
                        ) { "Saved-search hit ${hit.id} has invalid snapshot metadata" }
                        BackupSavedSearchHitProto(
                            providerId = hit.providerId,
                            providerRecordId = hit.providerRecordId,
                            fingerprint = hit.fingerprint,
                            recordPayload = hit.recordPayload,
                            linkedWorkSourceId = hit.linkedWorkId,
                            providerUpdatedAtEpochMillis = hit.providerUpdatedAtEpochMillis,
                            firstSeenAtEpochMillis = hit.firstSeenAtEpochMillis,
                            lastSeenAtEpochMillis = hit.lastSeenAtEpochMillis,
                            unread = hit.unread,
                        )
                    },
            )
        },
    )
}

private const val SUBJECT_SEPARATOR = "\u001f"
