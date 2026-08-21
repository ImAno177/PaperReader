package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.usecase.SearchResultCluster
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationTest {
    @Test
    fun `incremental exact alias keeps the first result key`() {
        val doi = PaperIdentifier(IdentifierType.DOI, "10.1000/stable")
        val first = RemotePaper("arxiv", "2401.00001", "Stable result", identifiers = setOf(doi))
        val alias = RemotePaper("semanticscholar", "s2-1000", "Stable result", identifiers = setOf(doi))

        val initial = SearchResultCluster(listOf(first)).toSearchPaperUi()
        val enriched = SearchResultCluster(listOf(first, alias)).toSearchPaperUi()

        assertEquals("arxiv:2401.00001", initial.key)
        assertEquals(initial.key, enriched.key)
    }

    @Test
    fun `persisted arxiv alias marks a fresh versioned result as saved`() {
        val savedIdentifier = PaperIdentifier(IdentifierType.ARXIV, "1506.02640")
        val savedPaper = PaperUi(
            id = "work-yolo",
            title = "You Only Look Once: Unified, Real-Time Object Detection",
            authors = emptyList(),
            savedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            publishedDate = null,
            sources = listOf("arxiv"),
            primaryIdentifier = savedIdentifier,
            identifiers = listOf(savedIdentifier),
            abstractText = null,
            progress = 0f,
            status = ReadingStatus.UNREAD,
            subjects = emptyList(),
            manifestations = emptyList(),
        )
        val versioned = SearchResultCluster(
            listOf(
                RemotePaper(
                    "arxiv",
                    "1506.02640v5",
                    savedPaper.title,
                    identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1506.02640v5")),
                ),
            ),
        ).toSearchPaperUi()
        val sameTitleWithoutAlias = SearchResultCluster(
            listOf(RemotePaper("arxiv", "unrelated", savedPaper.title)),
        ).toSearchPaperUi()

        assertEquals(
            mapOf(versioned.key to savedPaper.id),
            persistedSavedWorkIds(listOf(versioned, sameTitleWithoutAlias), listOf(savedPaper)),
        )
    }
}
