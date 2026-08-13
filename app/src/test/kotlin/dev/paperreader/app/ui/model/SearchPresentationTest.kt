package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.usecase.SearchResultCluster
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationTest {
    @Test
    fun `incremental exact alias keeps the first result key`() {
        val doi = PaperIdentifier(IdentifierType.DOI, "10.1000/stable")
        val first = RemotePaper("arxiv", "2401.00001", "Stable result", identifiers = setOf(doi))
        val alias = RemotePaper("crossref", "10.1000/stable", "Stable result", identifiers = setOf(doi))

        val initial = SearchResultCluster(listOf(first)).toSearchPaperUi()
        val enriched = SearchResultCluster(listOf(first, alias)).toSearchPaperUi()

        assertEquals("arxiv:2401.00001", initial.key)
        assertEquals(initial.key, enriched.key)
    }
}
