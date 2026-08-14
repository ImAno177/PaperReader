package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.CancellationException

/** Repairs legacy identifier-only saves without changing papers that already have a manifestation. */
class RepairSavedPaper(
    private val repository: LibraryRepository,
    private val enricher: SavedPaperEnricher,
) {
    suspend fun await(workId: WorkId): LibraryPaper? {
        val current = repository.get(workId) ?: return null
        if (current.manifestations.isNotEmpty()) return current

        val identifiers = current.work.identifiers
        if (identifiers.isEmpty()) return current
        val providerIdentifier = identifiers.firstOrNull { it.type == IdentifierType.PROVIDER }
        val seed = RemotePaper(
            providerId = providerIdentifier?.authority ?: REPAIR_PROVIDER_ID,
            providerRecordId = providerIdentifier?.value ?: identifiers.first().value,
            title = current.work.title,
            abstractText = current.work.abstractText,
            authors = current.work.authors,
            identifiers = identifiers,
            subjects = current.work.subjects,
            publishedDate = current.work.publishedDate,
            updatedAt = current.work.updatedAt,
        )
        val enriched = try {
            enricher.enrich(seed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listOf(seed)
        }
        // The seed is intentionally not re-saved. A custom extension may return only resolved
        // records (or may reorder them), so select the durable readable candidates by capability
        // instead of relying on the default resolver's list position.
        enriched.filter { it.manifestations.isNotEmpty() }.forEach { repository.save(it) }
        return repository.get(workId) ?: current
    }

    private companion object {
        const val REPAIR_PROVIDER_ID = "paperreader"
    }
}
