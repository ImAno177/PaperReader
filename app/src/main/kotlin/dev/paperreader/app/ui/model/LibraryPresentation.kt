package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.ReadingStatus

enum class LibraryStatusFilter {
    ALL,
    UNREAD,
    READING,
    FINISHED,
}

enum class LibrarySortOrder {
    RECENTLY_SAVED,
    TITLE,
    NEWEST_PUBLICATION,
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
            statusFilter == LibraryStatusFilter.ALL || paper.status == statusFilter.toReadingStatus()
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
}
