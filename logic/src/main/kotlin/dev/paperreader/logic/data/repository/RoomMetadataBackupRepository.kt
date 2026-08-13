package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
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
import dev.paperreader.logic.backup.DecodedBackup
import dev.paperreader.logic.backup.FORMAT_MARKER
import dev.paperreader.logic.backup.METADATA_BACKUP_DATABASE_VERSION
import dev.paperreader.logic.backup.METADATA_BACKUP_SCHEMA_VERSION
import dev.paperreader.logic.backup.MetadataBackupCodec
import dev.paperreader.logic.backup.MetadataBackupEncodingException
import dev.paperreader.logic.backup.MetadataBackupError
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataBackupProto
import dev.paperreader.logic.backup.MetadataBackupSummary
import dev.paperreader.logic.backup.MetadataRestoreIssue
import dev.paperreader.logic.backup.MetadataRestorePreview
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import dev.paperreader.logic.data.AnnotationEntity
import dev.paperreader.logic.data.AuthorEntity
import dev.paperreader.logic.data.CollectionEntity
import dev.paperreader.logic.data.IdentifierEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.LocalDocumentAnchorRow
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.ReadingBookmarkEntity
import dev.paperreader.logic.data.ReadingHistoryEntity
import dev.paperreader.logic.data.ReadingStateEntity
import dev.paperreader.logic.data.SavedSearchEntity
import dev.paperreader.logic.data.SavedSearchHitEntity
import dev.paperreader.logic.data.SavedSearchRecordCodec
import dev.paperreader.logic.data.SavedSearchSourceEntity
import dev.paperreader.logic.data.WorkCollectionEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.data.stableWorkId
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.readingBookmarkId
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import dev.paperreader.logic.domain.identity.IdentityResolver
import dev.paperreader.logic.domain.normalizeCollectionName
import dev.paperreader.logic.domain.repository.MetadataBackupRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException

internal class RoomMetadataBackupRepository(
    private val database: LibraryDatabase,
    private val installedProviderIds: () -> Set<String> = { emptySet() },
    private val clock: Clock = Clock.systemUTC(),
) : MetadataBackupRepository {
    private val dao = database.libraryDao()
    private val bookmarkDao = database.readingBookmarkDao()
    private val savedSearchDao = database.savedSearchDao()

    override suspend fun export(): MetadataBackupExportResult = try {
        val proto = database.withTransaction { buildExportProto() }
        MetadataBackupExportResult.Success(
            MetadataBackupExport(
                archiveBytes = MetadataBackupCodec.encode(proto),
                summary = summary(proto),
                createdAt = Instant.ofEpochMilli(proto.createdAtEpochMillis),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MetadataBackupEncodingException) {
        MetadataBackupExportResult.Rejected(error.rejection)
    } catch (_: IllegalArgumentException) {
        MetadataBackupExportResult.Rejected(invalidLocalDataError())
    } catch (_: IllegalStateException) {
        MetadataBackupExportResult.Rejected(invalidLocalDataError())
    } catch (_: Exception) {
        MetadataBackupExportResult.Rejected(
            MetadataBackupError.Rejected(
                code = "export_unavailable",
                detail = "Local metadata could not be read for backup",
            ),
        )
    }

    override suspend fun preview(bytes: ByteArray): MetadataRestorePreviewResult =
        when (val decoded = MetadataBackupCodec.decode(bytes)) {
            is DecodedBackup.Invalid -> MetadataRestorePreviewResult.Rejected(decoded.error)
            is DecodedBackup.Valid -> try {
                database.withTransaction {
                    MetadataRestorePreviewResult.Ready(analyze(decoded.value).preview)
                }
            } catch (error: IllegalArgumentException) {
                MetadataRestorePreviewResult.Rejected(
                    MetadataBackupError.Rejected(
                        code = "invalid_payload",
                        detail = error.message ?: "Backup cannot be planned safely",
                    ),
                )
            }
        }

    override suspend fun restore(bytes: ByteArray): MetadataRestoreResult {
        val proto = when (val decoded = MetadataBackupCodec.decode(bytes)) {
            is DecodedBackup.Invalid -> return MetadataRestoreResult.Rejected(decoded.error)
            is DecodedBackup.Valid -> decoded.value
        }
        return try {
            database.withTransaction { apply(analyze(proto)) }
        } catch (error: IllegalArgumentException) {
            MetadataRestoreResult.Rejected(
                MetadataBackupError.Rejected(
                    code = "restore_rejected",
                    detail = error.message ?: "Backup restore was rejected",
                ),
            )
        }
    }

    private suspend fun buildExportProto(): MetadataBackupProto {
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

    private data class AliasKey(val type: String, val value: String, val authority: String)
    private data class ManifestationKey(
        val provider: String,
        val record: String,
        val type: String,
        val version: String?,
    )
    private data class ManifestationRef(val workSourceId: String, val manifestationSourceId: String)
    private data class LocalAnchor(val manifestationId: String, val documentSha256: String)
    private data class WorkDocumentHash(val workId: String, val documentSha256: String)
    private data class BookmarkKey(
        val workId: String,
        val manifestationId: String,
        val documentSha256: String,
        val pageIndex: Int,
    )
    private data class SavedSearchHitKey(val providerId: String, val providerRecordId: String)

    private data class ManifestationPlan(
        val source: BackupManifestationProto,
        val targetId: ManifestationId,
        val existing: ManifestationEntity?,
    )

    private data class WorkPlan(
        val source: BackupWorkProto,
        val identifiers: List<PaperIdentifier>,
        val targetId: WorkId?,
        val existing: WorkEntity?,
        val hasExistingAuthors: Boolean,
        val manifestations: List<ManifestationPlan>,
        val conflict: MetadataRestoreIssue?,
    ) {
        val eligible: Boolean get() = conflict == null && targetId != null
    }

    private data class SavedSearchPlan(
        val search: SavedSearchEntity,
        val sources: List<SavedSearchSourceEntity>,
        val hits: List<SavedSearchHitEntity>,
    )

    private data class RestorePlan(
        val proto: MetadataBackupProto,
        val preview: MetadataRestorePreview,
        val works: List<WorkPlan>,
        val memberships: List<BackupMembershipProto>,
        val readingStates: List<ReadingStateEntity>,
        val histories: List<ReadingHistoryEntity>,
        val bookmarks: List<ReadingBookmarkEntity>,
        val annotations: List<AnnotationEntity>,
        val savedSearches: List<SavedSearchPlan>,
    )

    private suspend fun analyze(proto: MetadataBackupProto): RestorePlan {
        val localWorks = dao.getAllWorks()
        val localWorksById = localWorks.associateBy(WorkEntity::id)
        val localAliases = dao.getAllIdentifiers().groupBy { identifier -> canonicalIdentifier(identifier).toAliasKey() }
        val localAuthorsByWork = dao.getAllAuthors().groupBy(AuthorEntity::workId)
        val allLocalManifestations = dao.getAllManifestations()
        val localManifestationsByWork = allLocalManifestations.groupBy(ManifestationEntity::workId)
        val localManifestationsById = allLocalManifestations.associateBy(ManifestationEntity::id)
        val issues = mutableListOf<MetadataRestoreIssue>()

        val workPlans = proto.works.map { source ->
            val identifiers = canonicalIdentifiers(source)
            val exactMatches = identifiers
                .flatMap { identifier ->
                    localAliases[
                        AliasKey(identifier.type.name, identifier.value, identifier.authority.orEmpty()),
                    ].orEmpty().map(IdentifierEntity::workId)
                }
                .distinct()
            val localPdfSha256 = source.localPdfSha256OrNull()
            val generatedId = if (exactMatches.isEmpty()) {
                when {
                    identifiers.isNotEmpty() -> stableWorkId(identifiers)
                    localPdfSha256 != null -> WorkId("w-local-$localPdfSha256")
                    else -> null
                }
            } else {
                null
            }
            val targetId = exactMatches.singleOrNull()?.let(::WorkId) ?: generatedId
            val generatedExisting = generatedId?.let { localWorksById[it.value] }
            val compatibleExistingLocalPdf = localPdfSha256 != null && generatedId != null &&
                localManifestationsByWork[generatedId.value].orEmpty().any { manifestation ->
                    manifestation.sourceProvider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true) &&
                        manifestation.sourceRecordId.equals(localPdfSha256, ignoreCase = true)
                }
            val generatedCollision = generatedExisting != null && !compatibleExistingLocalPdf
            var conflict = when {
                exactMatches.size > 1 -> MetadataRestoreIssue("alias_conflict", source.sourceId)
                identifiers.isEmpty() && localPdfSha256 == null -> {
                    MetadataRestoreIssue("missing_exact_identity", source.sourceId)
                }
                generatedCollision -> MetadataRestoreIssue("work_id_conflict", source.sourceId)
                else -> null
            }
            val existing = targetId?.let { localWorksById[it.value] }
            val existingManifestations = targetId?.let { localManifestationsByWork[it.value] }.orEmpty()
            val manifestationPlans = if (conflict == null && targetId != null) {
                source.manifestations.map { manifestation ->
                    val key = manifestationKey(manifestation)
                    val current = existingManifestations.firstOrNull { manifestationKey(it) == key }
                    val id = current?.id ?: stableRestoredManifestationId(targetId, key)
                    val globalCollision = localManifestationsById[id]
                    if (
                        current == null &&
                        globalCollision != null &&
                        (globalCollision.workId != targetId.value || manifestationKey(globalCollision) != key)
                    ) {
                        conflict = MetadataRestoreIssue("manifestation_id_conflict", source.sourceId)
                    }
                    ManifestationPlan(manifestation, ManifestationId(id), current)
                }
            } else {
                emptyList()
            }
            conflict?.let(issues::add)
            WorkPlan(
                source = source,
                identifiers = identifiers,
                targetId = targetId.takeIf { conflict == null },
                existing = existing.takeIf { conflict == null },
                hasExistingAuthors = targetId != null && localAuthorsByWork[targetId.value].orEmpty().isNotEmpty(),
                manifestations = manifestationPlans.takeIf { conflict == null }.orEmpty(),
                conflict = conflict,
            )
        }

        val eligibleWorks = workPlans.filter(WorkPlan::eligible)
        val workBySource = eligibleWorks.associateBy { it.source.sourceId }
        val manifestationByRef = eligibleWorks.flatMap { work ->
            work.manifestations.map { manifestation ->
                ManifestationRef(work.source.sourceId, manifestation.source.sourceId) to manifestation
            }
        }.toMap()

        val memberships = proto.memberships.filter { it.workSourceId in workBySource }
        val existingStates = dao.getAllReadingStates().associateBy(ReadingStateEntity::workId)
        val readingStates = proto.readingStates.mapNotNull { state ->
            val work = workBySource[state.workSourceId] ?: return@mapNotNull null
            val targetId = checkNotNull(work.targetId)
            val existing = existingStates[targetId.value]
            if (existing != null && existing.updatedAtEpochMillis >= state.updatedAtEpochMillis) {
                existing
            } else {
                ReadingStateEntity(
                    workId = targetId.value,
                    manifestationId = state.manifestationSourceId?.let { sourceId ->
                        checkNotNull(manifestationByRef[ManifestationRef(state.workSourceId, sourceId)]).targetId.value
                    },
                    documentSha256 = state.documentSha256,
                    blockId = state.blockId,
                    characterOffset = state.characterOffset,
                    pageIndex = state.pageIndex,
                    progression = state.progression,
                    status = state.status,
                    updatedAtEpochMillis = state.updatedAtEpochMillis,
                )
            }
        }

        val existingHistory = dao.getAllReadingHistory().associateBy(ReadingHistoryEntity::workId)
        val histories = proto.histories.mapNotNull { history ->
            val work = workBySource[history.workSourceId] ?: return@mapNotNull null
            val workId = checkNotNull(work.targetId).value
            val existing = existingHistory[workId]
            ReadingHistoryEntity(
                workId = workId,
                lastReadAtEpochMillis = maxOf(existing?.lastReadAtEpochMillis ?: 0, history.lastReadAtEpochMillis),
                totalReadDurationMillis = maxOf(
                    existing?.totalReadDurationMillis ?: 0,
                    history.totalReadDurationMillis,
                ),
                sessionCount = maxOf(existing?.sessionCount ?: 0, history.sessionCount),
            )
        }

        val existingBookmarks = bookmarkDao.getAll().associateBy { bookmark ->
            BookmarkKey(
                bookmark.workId,
                bookmark.manifestationId,
                bookmark.documentSha256.lowercase(Locale.ROOT),
                bookmark.pageIndex,
            )
        }
        val bookmarks = proto.bookmarks.mapNotNull { bookmark ->
            val work = workBySource[bookmark.workSourceId] ?: return@mapNotNull null
            val workId = checkNotNull(work.targetId)
            val manifestation = checkNotNull(
                manifestationByRef[ManifestationRef(bookmark.workSourceId, bookmark.manifestationSourceId)],
            ).targetId
            val key = BookmarkKey(workId.value, manifestation.value, bookmark.documentSha256, bookmark.pageIndex)
            val existing = existingBookmarks[key]
            ReadingBookmarkEntity(
                workId = workId.value,
                manifestationId = manifestation.value,
                documentSha256 = bookmark.documentSha256,
                pageIndex = bookmark.pageIndex,
                id = readingBookmarkId(workId, manifestation, bookmark.documentSha256, bookmark.pageIndex).value,
                createdAtEpochMillis = minOf(
                    existing?.createdAtEpochMillis ?: bookmark.createdAtEpochMillis,
                    bookmark.createdAtEpochMillis,
                ),
            )
        }

        val existingAnnotations = dao.getAllAnnotations().associateBy(AnnotationEntity::id)
        val annotations = mutableListOf<AnnotationEntity>()
        var annotationConflicts = 0
        proto.annotations.forEach { annotation ->
            val work = workBySource[annotation.workSourceId]
            if (work == null) return@forEach
            val workId = checkNotNull(work.targetId).value
            val existing = existingAnnotations[annotation.id]
            if (existing != null && !existing.sameAnchorAs(annotation, workId)) {
                issues += MetadataRestoreIssue("annotation_conflict", annotation.id)
                annotationConflicts++
                return@forEach
            }
            val incoming = annotation.toEntity(workId)
            annotations += when {
                existing == null -> incoming
                existing.updatedAtEpochMillis > incoming.updatedAtEpochMillis -> existing
                else -> incoming.copy(createdAtEpochMillis = minOf(existing.createdAtEpochMillis, incoming.createdAtEpochMillis))
            }
        }

        val localSavedSearches = savedSearchDao.getAllSearches().associateBy(SavedSearchEntity::id)
        val localSavedSearchSources = savedSearchDao.getAllSources().groupBy(SavedSearchSourceEntity::searchId)
        val localSavedSearchHits = savedSearchDao.getAllHits().groupBy(SavedSearchHitEntity::searchId)
        var skippedSavedSearchRecords = 0
        val savedSearchPlans = proto.savedSearches.mapNotNull { source ->
            val providerIds = source.sources.map(BackupSavedSearchSourceProto::providerId)
            val searchId = stableSavedSearchId(source.queryText, providerIds)
            val existing = localSavedSearches[searchId.value]
            val existingSources = localSavedSearchSources[searchId.value].orEmpty()
            if (
                existing != null &&
                (existing.queryText != source.queryText ||
                    existingSources.map(SavedSearchSourceEntity::providerId).sorted() != providerIds)
            ) {
                issues += MetadataRestoreIssue("saved_search_id_conflict", source.queryText)
                skippedSavedSearchRecords += 1 + source.sources.size + source.hits.size
                return@mapNotNull null
            }
            val incomingSources = source.sources.associate { incoming ->
                incoming.providerId to SavedSearchSourceEntity(
                    searchId = searchId.value,
                    providerId = incoming.providerId,
                    lastCheckedAtEpochMillis = incoming.lastCheckedAtEpochMillis,
                    lastSuccessAtEpochMillis = incoming.lastSuccessAtEpochMillis,
                    failureKind = incoming.failureKind,
                    retryAfterEpochMillis = incoming.retryAfterEpochMillis,
                )
            }
            val mergedSources = providerIds.map { providerId ->
                mergeSavedSearchSource(
                    existing = existingSources.firstOrNull { it.providerId == providerId },
                    incoming = checkNotNull(incomingSources[providerId]),
                )
            }

            val existingHits = localSavedSearchHits[searchId.value].orEmpty().associateBy { hit ->
                SavedSearchHitKey(hit.providerId, hit.providerRecordId)
            }
            val incomingHits = source.hits.associate { hit ->
                val linkedWorkId = hit.linkedWorkSourceId?.let { workSourceId ->
                    val mapped = workBySource[workSourceId]?.targetId?.value
                    if (mapped == null) skippedSavedSearchRecords++
                    mapped
                }
                val entity = SavedSearchHitEntity(
                    id = stableHitId(searchId, hit.providerId, hit.providerRecordId),
                    searchId = searchId.value,
                    providerId = hit.providerId,
                    providerRecordId = hit.providerRecordId,
                    fingerprint = hit.fingerprint,
                    recordPayload = hit.recordPayload,
                    linkedWorkId = linkedWorkId,
                    providerUpdatedAtEpochMillis = hit.providerUpdatedAtEpochMillis,
                    firstSeenAtEpochMillis = hit.firstSeenAtEpochMillis,
                    lastSeenAtEpochMillis = hit.lastSeenAtEpochMillis,
                    unread = hit.unread,
                )
                SavedSearchHitKey(hit.providerId, hit.providerRecordId) to entity
            }
            val retainedHits = (existingHits.keys + incomingHits.keys)
                .map { key -> mergeSavedSearchHit(existingHits[key], incomingHits[key]) }
                .groupBy(SavedSearchHitEntity::providerId)
                .values
                .flatMap { hits ->
                    hits.sortedWith(
                        compareByDescending<SavedSearchHitEntity> {
                            it.providerUpdatedAtEpochMillis ?: it.firstSeenAtEpochMillis
                        }.thenByDescending(SavedSearchHitEntity::firstSeenAtEpochMillis)
                            .thenBy(SavedSearchHitEntity::id),
                    ).take(MAX_SAVED_SEARCH_HITS_PER_SOURCE)
                }
            SavedSearchPlan(
                search = existing ?: SavedSearchEntity(
                    id = searchId.value,
                    queryText = source.queryText,
                    createdAtEpochMillis = source.createdAtEpochMillis,
                ),
                sources = mergedSources,
                hits = retainedHits,
            )
        }

        val localAnchors = dao.getLocalDocumentAnchors().mapTo(HashSet()) { it.toAnchor() }
        val localWorkHashes = localAnchors.mapNotNullTo(HashSet()) { anchor ->
            localManifestationsById[anchor.manifestationId]?.let { manifestation ->
                WorkDocumentHash(manifestation.workId, anchor.documentSha256)
            }
        }
        val dormantReadingStates = readingStates.count { state ->
            val sha = state.documentSha256?.lowercase(Locale.ROOT) ?: return@count false
            if (state.manifestationId != null) {
                LocalAnchor(state.manifestationId, sha) !in localAnchors
            } else {
                WorkDocumentHash(state.workId, sha) !in localWorkHashes
            }
        }
        val dormantBookmarks = bookmarks.count { bookmark ->
            LocalAnchor(bookmark.manifestationId, bookmark.documentSha256) !in localAnchors
        }
        val dormantAnnotations = annotations.count { annotation ->
            WorkDocumentHash(annotation.workId, annotation.documentSha256.lowercase(Locale.ROOT)) !in localWorkHashes
        }

        val skippedWorkSources = workPlans.filterNot(WorkPlan::eligible).mapTo(HashSet()) { it.source.sourceId }
        val skippedRecords = workPlans.filterNot(WorkPlan::eligible).sumOf { it.source.manifestations.size } +
            proto.memberships.count { it.workSourceId in skippedWorkSources } +
            proto.readingStates.count { it.workSourceId in skippedWorkSources } +
            proto.histories.count { it.workSourceId in skippedWorkSources } +
            proto.bookmarks.count { it.workSourceId in skippedWorkSources } +
            proto.annotations.count { it.workSourceId in skippedWorkSources } +
            annotationConflicts +
            skippedSavedSearchRecords
        val preview = MetadataRestorePreview(
            summary = summary(proto),
            createdAt = Instant.ofEpochMilli(proto.createdAtEpochMillis),
            newWorks = eligibleWorks.count { it.existing == null },
            mergedWorks = eligibleWorks.count { it.existing != null },
            skippedWorks = workPlans.count { !it.eligible },
            conflicts = issues,
            missingProviders = (
                eligibleWorks.flatMap { it.source.manifestations }.map(BackupManifestationProto::sourceProvider) +
                    savedSearchPlans.flatMap { it.sources }.map(SavedSearchSourceEntity::providerId)
                )
                .filterNot(installedProviderIds()::contains)
                .toSet(),
            dormantReadingStates = dormantReadingStates,
            dormantBookmarks = dormantBookmarks,
            dormantAnnotations = dormantAnnotations,
            skippedRecords = skippedRecords,
        )
        return RestorePlan(
            proto = proto,
            preview = preview,
            works = workPlans,
            memberships = memberships,
            readingStates = readingStates,
            histories = histories,
            bookmarks = bookmarks,
            annotations = annotations,
            savedSearches = savedSearchPlans,
        )
    }

    private suspend fun apply(plan: RestorePlan): MetadataRestoreResult.Applied {
        val eligibleWorks = plan.works.filter(WorkPlan::eligible)
        eligibleWorks.forEach { workPlan ->
            val workId = checkNotNull(workPlan.targetId)
            val incoming = workPlan.source.toEntity(workId)
            val merged = workPlan.existing?.let { existing ->
                incoming.copy(
                    title = existing.title,
                    abstractText = existing.abstractText ?: incoming.abstractText,
                    subjects = mergeSubjects(existing.subjects, incoming.subjects),
                    publishedDateEpochDay = existing.publishedDateEpochDay ?: incoming.publishedDateEpochDay,
                    createdAtEpochMillis = minOf(existing.createdAtEpochMillis, incoming.createdAtEpochMillis),
                    updatedAtEpochMillis = maxOf(existing.updatedAtEpochMillis, incoming.updatedAtEpochMillis),
                )
            } ?: incoming
            dao.upsertWork(merged)
            if (!workPlan.hasExistingAuthors) {
                dao.upsertAuthors(workPlan.source.authors.map { author -> author.toEntity(workId) })
            }
            dao.upsertIdentifiers(workPlan.identifiers.map { identifier -> identifier.toEntity(workId) })
            dao.upsertManifestations(workPlan.manifestations.map { manifestation -> manifestation.toEntity(workId) })
        }

        val collectionMap = dao.getAllCollections().associateByTo(
            destination = mutableMapOf(),
            keySelector = { it.name.lowercase(Locale.ROOT) },
            valueTransform = CollectionEntity::id,
        )
        var nextSortOrder = (dao.getMaximumCollectionSortOrder() + 1).coerceAtLeast(0)
        plan.proto.collections.sortedWith(compareBy(BackupCollectionProto::sortOrder, BackupCollectionProto::name))
            .forEach { collection ->
                val key = collection.name.lowercase(Locale.ROOT)
                if (key !in collectionMap) {
                    collectionMap[key] = dao.insertCollection(
                        CollectionEntity(
                            name = collection.name,
                            sortOrder = nextSortOrder++,
                            createdAtEpochMillis = collection.createdAtEpochMillis,
                            updatedAtEpochMillis = collection.updatedAtEpochMillis,
                        ),
                    )
                }
            }
        val eligibleWorksBySource = eligibleWorks.associateBy { it.source.sourceId }
        dao.upsertWorkCollections(
            plan.memberships.map { membership ->
                val workId = checkNotNull(eligibleWorksBySource[membership.workSourceId]?.targetId)
                val collectionId = checkNotNull(collectionMap[membership.collectionName.lowercase(Locale.ROOT)])
                WorkCollectionEntity(workId.value, collectionId)
            },
        )
        dao.upsertReadingStates(plan.readingStates)
        dao.upsertReadingHistory(plan.histories)
        bookmarkDao.upsertAll(plan.bookmarks)
        dao.upsertAnnotations(plan.annotations)
        plan.savedSearches.forEach { savedSearch ->
            savedSearchDao.insertSearch(savedSearch.search)
            savedSearchDao.upsertSources(savedSearch.sources)
            savedSearchDao.upsertHits(savedSearch.hits)
            savedSearch.sources.forEach { source ->
                savedSearchDao.pruneHits(
                    searchId = savedSearch.search.id,
                    providerId = source.providerId,
                    keep = MAX_SAVED_SEARCH_HITS_PER_SOURCE,
                )
            }
        }

        return MetadataRestoreResult.Applied(
            preview = plan.preview,
            appliedWorks = eligibleWorks.size,
            skippedWorks = plan.preview.skippedWorks,
            appliedBookmarks = plan.bookmarks.size,
            appliedAnnotations = plan.annotations.size,
            appliedSavedSearches = plan.savedSearches.size,
            appliedSavedSearchHits = plan.savedSearches.sumOf { it.hits.size },
            skippedRecords = plan.preview.skippedRecords,
        )
    }

    private fun mergeSavedSearchSource(
        existing: SavedSearchSourceEntity?,
        incoming: SavedSearchSourceEntity,
    ): SavedSearchSourceEntity {
        if (existing == null) return incoming
        val incomingIsNewer = (incoming.lastCheckedAtEpochMillis ?: Long.MIN_VALUE) >
            (existing.lastCheckedAtEpochMillis ?: Long.MIN_VALUE)
        val status = if (incomingIsNewer) incoming else existing
        return status.copy(
            searchId = incoming.searchId,
            providerId = incoming.providerId,
            lastCheckedAtEpochMillis = maxNullable(
                existing.lastCheckedAtEpochMillis,
                incoming.lastCheckedAtEpochMillis,
            ),
            lastSuccessAtEpochMillis = maxNullable(
                existing.lastSuccessAtEpochMillis,
                incoming.lastSuccessAtEpochMillis,
            ),
        )
    }

    private fun mergeSavedSearchHit(
        existing: SavedSearchHitEntity?,
        incoming: SavedSearchHitEntity?,
    ): SavedSearchHitEntity = when {
        existing == null -> checkNotNull(incoming)
        incoming == null -> existing
        else -> {
            val incomingFreshness = incoming.providerUpdatedAtEpochMillis ?: incoming.lastSeenAtEpochMillis
            val existingFreshness = existing.providerUpdatedAtEpochMillis ?: existing.lastSeenAtEpochMillis
            val metadata = if (incomingFreshness > existingFreshness) incoming else existing
            metadata.copy(
                id = incoming.id,
                searchId = incoming.searchId,
                providerId = incoming.providerId,
                providerRecordId = incoming.providerRecordId,
                linkedWorkId = existing.linkedWorkId ?: incoming.linkedWorkId,
                firstSeenAtEpochMillis = minOf(existing.firstSeenAtEpochMillis, incoming.firstSeenAtEpochMillis),
                lastSeenAtEpochMillis = maxOf(existing.lastSeenAtEpochMillis, incoming.lastSeenAtEpochMillis),
                unread = existing.unread,
            )
        }
    }

    private fun maxNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private fun canonicalIdentifiers(work: BackupWorkProto): List<PaperIdentifier> = work.identifiers
        .map { identifier ->
            IdentifierNormalizer.canonical(
                PaperIdentifier(
                    type = dev.paperreader.logic.domain.IdentifierType.valueOf(identifier.type),
                    value = identifier.value,
                    authority = identifier.authority.takeIf(String::isNotBlank),
                ),
            )
        }
        .distinctBy { IdentityResolver.exactKeys(listOf(it)).single() }

    private fun BackupWorkProto.toEntity(workId: WorkId) = WorkEntity(
        id = workId.value,
        title = title,
        abstractText = abstractText,
        subjects = subjects.filter(String::isNotBlank).toSortedSet().joinToString(SUBJECT_SEPARATOR),
        publishedDateEpochDay = publishedDateEpochDay,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun BackupAuthorProto.toEntity(workId: WorkId) = AuthorEntity(
        workId = workId.value,
        position = position,
        displayName = displayName,
        givenName = givenName,
        familyName = familyName,
        orcid = orcid,
    )

    private fun PaperIdentifier.toEntity(workId: WorkId) = IdentifierEntity(
        workId = workId.value,
        type = type.name,
        value = value,
        authority = authority.orEmpty(),
    )

    private fun canonicalIdentifier(identifier: IdentifierEntity): PaperIdentifier =
        IdentifierNormalizer.canonical(
            PaperIdentifier(
                type = dev.paperreader.logic.domain.IdentifierType.valueOf(identifier.type),
                value = identifier.value,
                authority = identifier.authority.takeIf(String::isNotBlank),
            ),
        )

    private fun PaperIdentifier.toAliasKey() = AliasKey(type.name, value, authority.orEmpty())

    private fun ManifestationPlan.toEntity(workId: WorkId): ManifestationEntity {
        val incoming = ManifestationEntity(
            id = targetId.value,
            workId = workId.value,
            type = source.type,
            sourceProvider = source.sourceProvider,
            sourceRecordId = source.sourceRecordId,
            version = source.version,
            landingPageUrl = source.landingPageUrl,
            pdfUrl = source.pdfUrl,
            license = source.license,
            publishedDateEpochDay = source.publishedDateEpochDay,
            updatedAtEpochMillis = source.updatedAtEpochMillis,
        )
        return existing?.let { current ->
            incoming.copy(
                version = current.version ?: incoming.version,
                landingPageUrl = current.landingPageUrl ?: incoming.landingPageUrl,
                pdfUrl = current.pdfUrl ?: incoming.pdfUrl,
                license = current.license ?: incoming.license,
                publishedDateEpochDay = current.publishedDateEpochDay ?: incoming.publishedDateEpochDay,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, incoming.updatedAtEpochMillis),
            )
        } ?: incoming
    }

    private fun AnnotationEntity.sameAnchorAs(source: BackupAnnotationProto, targetWorkId: String): Boolean =
        workId == targetWorkId &&
            documentSha256.lowercase(Locale.ROOT) == source.documentSha256 &&
            blockId == source.blockId &&
            startOffset == source.startOffset &&
            endOffset == source.endOffset &&
            quoteExact == source.quoteExact &&
            pageIndex == source.pageIndex

    private fun BackupAnnotationProto.toEntity(targetWorkId: String) = AnnotationEntity(
        id = id,
        workId = targetWorkId,
        documentSha256 = documentSha256,
        blockId = blockId,
        startOffset = startOffset,
        endOffset = endOffset,
        quotePrefix = quotePrefix,
        quoteExact = quoteExact,
        quoteSuffix = quoteSuffix,
        pageIndex = pageIndex,
        note = note,
        color = color,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun LocalDocumentAnchorRow.toAnchor() = LocalAnchor(manifestationId, documentSha256)

    private fun summary(proto: MetadataBackupProto) = MetadataBackupSummary(
        works = proto.works.size,
        collections = proto.collections.size,
        manifestations = proto.works.sumOf { it.manifestations.size },
        readingStates = proto.readingStates.size,
        bookmarks = proto.bookmarks.size,
        annotations = proto.annotations.size,
        history = proto.histories.size,
        savedSearches = proto.savedSearches.size,
        savedSearchHits = proto.savedSearches.sumOf { it.hits.size },
    )

    private fun invalidLocalDataError() = MetadataBackupError.Rejected(
        code = "invalid_local_data",
        detail = "Local metadata contains values that cannot be backed up safely",
    )

    private fun manifestationKey(value: BackupManifestationProto) = ManifestationKey(
        provider = value.sourceProvider.lowercase(Locale.ROOT),
        record = value.sourceRecordId.normalizedManifestationRecord(value.sourceProvider),
        type = value.type,
        version = value.version,
    )

    private fun manifestationKey(value: ManifestationEntity) = ManifestationKey(
        provider = value.sourceProvider.lowercase(Locale.ROOT),
        record = value.sourceRecordId.normalizedManifestationRecord(value.sourceProvider),
        type = value.type,
        version = value.version,
    )

    private fun BackupWorkProto.localPdfSha256OrNull(): String? = manifestations
        .asSequence()
        .filter { it.sourceProvider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true) }
        .map { it.sourceRecordId.lowercase(Locale.ROOT) }
        .filter { it.matches(SHA256_REGEX) }
        .distinct()
        .toList()
        .singleOrNull()

    private fun String.normalizedManifestationRecord(provider: String): String =
        if (provider.equals(LOCAL_PDF_SOURCE_ID, ignoreCase = true)) lowercase(Locale.ROOT) else this

    private fun stableRestoredManifestationId(workId: WorkId, key: ManifestationKey): String =
        if (key.provider == LOCAL_PDF_SOURCE_ID && key.record.matches(SHA256_REGEX)) {
            "m-local-${key.record}"
        } else {
            stableManifestationId(workId, key)
        }

    private fun stableManifestationId(workId: WorkId, key: ManifestationKey): String = "m-" + sha256(
        listOf(workId.value, key.provider, key.record, key.type, key.version.orEmpty()).joinToString(HASH_SEPARATOR),
    )

    private fun mergeSubjects(existing: String, incoming: String): String =
        (existing.split(SUBJECT_SEPARATOR) + incoming.split(SUBJECT_SEPARATOR))
            .filter(String::isNotBlank)
            .toSortedSet()
            .joinToString(SUBJECT_SEPARATOR)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SUBJECT_SEPARATOR = "\u001f"
        const val HASH_SEPARATOR = "\u001e"
        const val MAX_SAVED_SEARCH_HITS_PER_SOURCE = 200
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}
