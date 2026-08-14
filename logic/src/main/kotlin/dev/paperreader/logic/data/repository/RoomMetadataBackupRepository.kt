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
        val proto = database.withTransaction { buildMetadataBackupProto(database, clock) }
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

    private companion object {
        const val MAX_SAVED_SEARCH_HITS_PER_SOURCE = 200
    }

}
