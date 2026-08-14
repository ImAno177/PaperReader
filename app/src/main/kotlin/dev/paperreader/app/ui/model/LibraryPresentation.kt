package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.ReadingStatus

enum class LibraryLayout(
    val storageKey: String,
) {
    LIST("list"),
    GRID("grid"),
    ;

    companion object {
        fun fromStorageKey(value: String?): LibraryLayout =
            entries.firstOrNull { it.storageKey == value } ?: LIST
    }
}

enum class LibraryStatusFilter {
    ALL,
    UNREAD,
    READING,
    FINISHED,
    ANNOTATED,
}

enum class LibrarySortOrder {
    RECENTLY_SAVED,
    TITLE,
    NEWEST_PUBLICATION,
}

enum class PaperDiscipline(val label: String) {
    COMPUTER_SCIENCE("CS"),
    MATHEMATICS("MATH"),
    PHYSICS("PHYS"),
    LIFE_SCIENCES("BIO"),
    MEDICINE("MED"),
    SOCIAL_SCIENCES("SOC"),
    ECONOMICS("ECON"),
    ENGINEERING("ENG"),
    GENERAL("PAPER"),
}

/** A deterministic visual identity derived only from metadata the provider actually supplied. */
fun PaperUi.discipline(): PaperDiscipline {
    val haystack = (subjects + title).joinToString(" ").lowercase()
    return when {
        listOf("computer", "machine learning", "artificial intelligence", "neural", "software").any(haystack::contains) ->
            PaperDiscipline.COMPUTER_SCIENCE
        listOf("mathemat", "statistics", "algebra", "geometry").any(haystack::contains) -> PaperDiscipline.MATHEMATICS
        listOf("physics", "quantum", "astronomy", "astrophysics").any(haystack::contains) -> PaperDiscipline.PHYSICS
        listOf("medicine", "medical", "clinical", "health", "disease").any(haystack::contains) -> PaperDiscipline.MEDICINE
        listOf("biology", "biological", "genomic", "protein", "ecology").any(haystack::contains) -> PaperDiscipline.LIFE_SCIENCES
        listOf("economics", "econometric", "finance").any(haystack::contains) -> PaperDiscipline.ECONOMICS
        listOf("sociology", "psychology", "political", "social science").any(haystack::contains) -> PaperDiscipline.SOCIAL_SCIENCES
        listOf("engineering", "robotics", "electrical", "mechanical").any(haystack::contains) -> PaperDiscipline.ENGINEERING
        else -> PaperDiscipline.GENERAL
    }
}

fun List<PaperUi>.filterAndSortLibrary(
    query: String,
    statusFilter: LibraryStatusFilter,
    sortOrder: LibrarySortOrder,
    collectionId: Long? = null,
): List<PaperUi> {
    val needle = query.trim()
    val filtered = asSequence()
        .filter { paper ->
            when (statusFilter) {
                LibraryStatusFilter.ALL -> true
                LibraryStatusFilter.ANNOTATED -> paper.annotationCount > 0
                else -> paper.status == statusFilter.toReadingStatus()
            }
        }
        .filter { paper -> collectionId == null || collectionId in paper.collectionIds }
        .filter { paper ->
            needle.isEmpty() || paper.matchesLibraryQuery(needle)
        }
        .toList()

    return when (sortOrder) {
        LibrarySortOrder.RECENTLY_SAVED -> filtered.sortedWith(
            compareByDescending<PaperUi> { it.savedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

        LibrarySortOrder.TITLE -> filtered.sortedWith(
            compareBy<PaperUi, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenByDescending { it.savedAt },
        )

        LibrarySortOrder.NEWEST_PUBLICATION -> filtered.sortedWith(
            compareByDescending<PaperUi> { it.publishedDate }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
    }
}

private fun PaperUi.matchesLibraryQuery(query: String): Boolean = sequenceOf(
    title,
    authors.joinToString(" "),
    subjects.joinToString(" "),
    identifiers.joinToString(" ") { it.displayValue() },
).any { value -> value.contains(query, ignoreCase = true) }

private fun LibraryStatusFilter.toReadingStatus(): ReadingStatus? = when (this) {
    LibraryStatusFilter.ALL -> null
    LibraryStatusFilter.UNREAD -> ReadingStatus.UNREAD
    LibraryStatusFilter.READING -> ReadingStatus.READING
    LibraryStatusFilter.FINISHED -> ReadingStatus.FINISHED
    LibraryStatusFilter.ANNOTATED -> null
}
