package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationTest {
    @Test
    fun `library layout defaults safely and round trips storage keys`() {
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromStorageKey(null))
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromStorageKey("unknown"))
        LibraryLayout.entries.forEach { layout ->
            assertEquals(layout, LibraryLayout.fromStorageKey(layout.storageKey))
        }
    }

    @Test
    fun `collection filter includes only explicitly assigned papers`() {
        val inCollection = paper(
            id = "in-collection",
            title = "Assigned paper",
            savedAt = Instant.parse("2025-01-01T00:00:00Z"),
        ).copy(collectionIds = setOf(7L, 9L))
        val outsideCollection = paper(
            id = "outside-collection",
            title = "Unassigned paper",
            savedAt = Instant.parse("2025-01-02T00:00:00Z"),
        )

        val filtered = listOf(inCollection, outsideCollection).filterAndSortLibrary(
            query = "",
            statusFilter = LibraryStatusFilter.ALL,
            sortOrder = LibrarySortOrder.RECENTLY_SAVED,
            collectionId = 7L,
        )

        assertEquals(listOf("in-collection"), filtered.map(PaperUi::id))
    }

    @Test
    fun `provider ids use product display names without changing unknown ids`() {
        assertEquals("arXiv", "arxiv".displayProviderName())
        assertEquals("Semantic Scholar", "semanticscholar".displayProviderName())
        assertEquals("Europe PMC", "europepmc".displayProviderName())
        assertEquals("community.example", "community.example".displayProviderName())
    }

    @Test
    fun `discipline badge uses supplied subjects before falling back to a neutral paper mark`() {
        assertEquals(
            PaperDiscipline.COMPUTER_SCIENCE,
            paper(id = "ai", title = "Attention Is All You Need", subjects = listOf("Computer Science")).discipline(),
        )
        assertEquals(
            PaperDiscipline.LIFE_SCIENCES,
            paper(id = "protein", title = "Protein structure prediction").discipline(),
        )
        assertEquals(PaperDiscipline.GENERAL, paper(id = "unknown", title = "Untitled study").discipline())
    }

    @Test
    fun `legacy provider urls are filtered before reaching an external intent`() {
        assertEquals("https://arxiv.org/pdf/1706.03762", "https://arxiv.org/pdf/1706.03762".safeWebUrlOrNull())
        assertEquals(null, "file:///private/paper.pdf".safeWebUrlOrNull())
        assertEquals(null, "intent://host/#Intent;end".safeWebUrlOrNull())
    }

    @Test
    fun `presentation dates stay English when the device locale is Vietnamese`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("vi-VN"))
            assertEquals("Jun 12, 2017", LocalDate.of(2017, 6, 12).toEnglishDisplayDate())
            assertEquals(
                "Jun 12, 2017, 3:30\u202FPM",
                Instant.parse("2017-06-12T15:30:00Z").toEnglishDisplayDateTime(ZoneOffset.UTC),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `filters across metadata and reading state`() {
        val unread = paper(
            id = "unread",
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani"),
            subjects = listOf("Machine Learning"),
            identifier = PaperIdentifier(IdentifierType.ARXIV, "1706.03762"),
            status = ReadingStatus.UNREAD,
        )
        val reading = paper(
            id = "reading",
            title = "A different paper",
            authors = listOf("Ada Lovelace"),
            subjects = listOf("Systems"),
            identifier = PaperIdentifier(IdentifierType.DOI, "10.1000/example"),
            status = ReadingStatus.READING,
        )

        val byAuthor = listOf(unread, reading).filterAndSortLibrary(
            query = "lovelace",
            statusFilter = LibraryStatusFilter.ALL,
            sortOrder = LibrarySortOrder.TITLE,
        )
        val byIdentifier = listOf(unread, reading).filterAndSortLibrary(
            query = "1706.03762",
            statusFilter = LibraryStatusFilter.ALL,
            sortOrder = LibrarySortOrder.TITLE,
        )
        val byStatus = listOf(unread, reading).filterAndSortLibrary(
            query = "",
            statusFilter = LibraryStatusFilter.READING,
            sortOrder = LibrarySortOrder.TITLE,
        )

        assertEquals(listOf("reading"), byAuthor.map(PaperUi::id))
        assertEquals(listOf("unread"), byIdentifier.map(PaperUi::id))
        assertEquals(listOf("reading"), byStatus.map(PaperUi::id))
    }

    @Test
    fun `annotated filter includes highlights with or without notes`() {
        val plain = paper(id = "plain", title = "Plain")
        val annotated = paper(id = "annotated", title = "Annotated").copy(annotationCount = 2)

        val filtered = listOf(plain, annotated).filterAndSortLibrary(
            query = "",
            statusFilter = LibraryStatusFilter.ANNOTATED,
            sortOrder = LibrarySortOrder.TITLE,
        )

        assertEquals(listOf("annotated"), filtered.map(PaperUi::id))
    }

    @Test
    fun `sort orders are deterministic and keep missing publication dates last`() {
        val olderSave = paper(
            id = "z",
            title = "Zulu",
            savedAt = Instant.parse("2026-01-01T00:00:00Z"),
            publishedDate = LocalDate.of(2025, 1, 1),
        )
        val newerSave = paper(
            id = "a",
            title = "alpha",
            savedAt = Instant.parse("2026-02-01T00:00:00Z"),
            publishedDate = null,
        )

        assertEquals(
            listOf("a", "z"),
            listOf(olderSave, newerSave).filterAndSortLibrary(
                query = "",
                statusFilter = LibraryStatusFilter.ALL,
                sortOrder = LibrarySortOrder.RECENTLY_SAVED,
            ).map(PaperUi::id),
        )
        assertEquals(
            listOf("a", "z"),
            listOf(olderSave, newerSave).filterAndSortLibrary(
                query = "",
                statusFilter = LibraryStatusFilter.ALL,
                sortOrder = LibrarySortOrder.TITLE,
            ).map(PaperUi::id),
        )
        assertEquals(
            listOf("z", "a"),
            listOf(olderSave, newerSave).filterAndSortLibrary(
                query = "",
                statusFilter = LibraryStatusFilter.ALL,
                sortOrder = LibrarySortOrder.NEWEST_PUBLICATION,
            ).map(PaperUi::id),
        )
    }

    private fun paper(
        id: String,
        title: String,
        authors: List<String> = emptyList(),
        subjects: List<String> = emptyList(),
        identifier: PaperIdentifier? = null,
        status: ReadingStatus = ReadingStatus.UNREAD,
        savedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        publishedDate: LocalDate? = LocalDate.of(2026, 1, 1),
    ) = PaperUi(
        id = id,
        title = title,
        authors = authors,
        savedAt = savedAt,
        updatedAt = savedAt,
        publishedDate = publishedDate,
        sources = emptyList(),
        primaryIdentifier = identifier,
        identifiers = listOfNotNull(identifier),
        abstractText = null,
        progress = 0f,
        status = status,
        subjects = subjects,
        manifestations = emptyList(),
    )
}
