package dev.paperreader.logic.domain

import java.time.Instant
import java.time.LocalDate

@JvmInline
value class WorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "Work ID cannot be blank" }
    }
}

@JvmInline
value class ManifestationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Manifestation ID cannot be blank" }
    }
}

enum class IdentifierType {
    DOI,
    ARXIV,
    PMID,
    PMCID,
    PROVIDER,
}

data class PaperIdentifier(
    val type: IdentifierType,
    val value: String,
    val authority: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Identifier cannot be blank" }
        require(type == IdentifierType.PROVIDER || authority == null) {
            "Only provider identifiers have an authority"
        }
        require(type != IdentifierType.PROVIDER || !authority.isNullOrBlank()) {
            "Provider identifiers require an authority"
        }
    }
}

data class PaperAuthor(
    val displayName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val orcid: String? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Author display name cannot be blank" }
    }
}

data class PaperWork(
    val id: WorkId,
    val title: String,
    val abstractText: String? = null,
    val authors: List<PaperAuthor> = emptyList(),
    val identifiers: Set<PaperIdentifier> = emptySet(),
    val subjects: Set<String> = emptySet(),
    val publishedDate: LocalDate? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(title.isNotBlank()) { "Paper title cannot be blank" }
    }
}

enum class ManifestationType {
    PREPRINT,
    ACCEPTED_MANUSCRIPT,
    VERSION_OF_RECORD,
    OTHER,
}

data class PaperManifestation(
    val id: ManifestationId,
    val workId: WorkId,
    val type: ManifestationType,
    val sourceProvider: String,
    val sourceRecordId: String,
    val version: String? = null,
    val landingPageUrl: String? = null,
    val pdfUrl: String? = null,
    val license: String? = null,
    val publishedDate: LocalDate? = null,
    val updatedAt: Instant,
) {
    init {
        require(sourceProvider.isNotBlank()) { "Source provider cannot be blank" }
        require(sourceRecordId.isNotBlank()) { "Source record ID cannot be blank" }
    }
}

data class LibraryPaper(
    val work: PaperWork,
    val manifestations: List<PaperManifestation>,
    val readingState: ReadingState? = null,
    val localArtifacts: Map<ManifestationId, LocalPaperArtifact> = emptyMap(),
    val collectionIds: Set<CollectionId> = emptySet(),
)
