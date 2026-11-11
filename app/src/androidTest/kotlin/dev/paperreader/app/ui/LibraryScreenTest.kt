package dev.paperreader.app.ui

import dev.paperreader.app.ui.state.LoadState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.screen.LibraryUiState
import dev.paperreader.app.ui.screen.LibraryAction
import dev.paperreader.app.ui.screen.LibraryScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.ReadingStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedPaperSearchFiltersRealPresentationFields() {
        composeRule.enableAccessibilityChecks()
        var query by mutableStateOf("")
        val papers = listOf(
            paper("attention", "Attention Is All You Need", listOf("Ashish Vaswani")),
            paper("engine", "Analytical Engine", listOf("Ada Lovelace")),
        )
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(papers = LoadState.Ready(papers), query = query),
                    onAction = { if (it is LibraryAction.SetQuery) query = it.query },
                    onOpenPaper = {},
                    onDiscover = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search saved papers").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Lovelace")

        composeRule.onNodeWithText("Analytical Engine").assertExists()
        composeRule.onNodeWithText("Attention Is All You Need").assertDoesNotExist()
    }

    @Test
    fun collectionChipFiltersByPersistedAssignment() {
        composeRule.enableAccessibilityChecks()
        var selectedCollectionId by mutableStateOf<Long?>(null)
        val papers = listOf(
            paper("assigned", "Assigned paper", emptyList()).copy(collectionIds = setOf(5L)),
            paper("outside", "Outside paper", emptyList()),
        )
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(
                        papers = LoadState.Ready(papers),
                        collections = LoadState.Ready(listOf(PaperCollectionUi(5L, "Methods"))),
                        selectedCollectionId = selectedCollectionId,
                    ),
                    onAction = { if (it is LibraryAction.SetCollection) selectedCollectionId = it.id },
                    onOpenPaper = {},
                    onDiscover = {},
                )
            }
        }

        composeRule.onNodeWithText("Methods").performClick()

        composeRule.onNodeWithText("Assigned paper").assertExists()
        composeRule.onNodeWithText("Outside paper").assertDoesNotExist()
    }

    @Test
    fun layoutToggleRequestsTheOppositePersistedLayout() {
        var requested: LibraryLayout? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(papers = LoadState.Ready(listOf(paper("paper", "Paper", emptyList())))),
                    onAction = { if (it is LibraryAction.SetLayout) requested = it.layout },
                    onOpenPaper = {},
                    onDiscover = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show grid").performClick()
        composeRule.runOnIdle { assertEquals(LibraryLayout.GRID, requested) }
    }

    @Test
    fun gridCardKeepsTheCompleteColonSeparatedTitle() {
        val title = "CGP-Tuning: Structure-aware optimization"
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(
                        papers = LoadState.Ready(listOf(paper("paper", title, emptyList()))),
                        layout = LibraryLayout.GRID,
                    ),
                    onAction = {},
                    onOpenPaper = {},
                    onDiscover = {},
                )
            }
        }

        composeRule.onNodeWithText(title).assertExists()
    }

    @Test
    fun paperCardExposesAFullQuickReadAction() {
        var read = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(papers = LoadState.Ready(listOf(paper("paper", "Paper", emptyList())))),
                    onAction = {},
                    onOpenPaper = {},
                    onDiscover = {},
                    onReadPaper = { read = true },
                )
            }
        }

        composeRule.onNodeWithText("Read").performClick()
        composeRule.runOnIdle { assertTrue(read) }
    }

    @Test
    fun emptyLibraryKeepsTheMessageActionableWithoutAContentCard() {
        var discover = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LibraryUiState(papers = LoadState.Ready(emptyList())),
                    onAction = {},
                    onOpenPaper = {},
                    onDiscover = { discover = true },
                )
            }
        }

        composeRule.onNodeWithText("Your library is empty").assertExists()
        composeRule.onNodeWithText("Search for a paper or import a PDF.").assertExists()
        composeRule.onNodeWithText("Find a paper").performClick()
        composeRule.runOnIdle { assertTrue(discover) }
    }

    private fun paper(id: String, title: String, authors: List<String>) = PaperUi(
        id = id,
        title = title,
        authors = authors,
        savedAt = Instant.parse("2026-08-11T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-11T00:00:00Z"),
        publishedDate = LocalDate.of(2026, 8, 11),
        sources = listOf("arxiv"),
        primaryIdentifier = null,
        identifiers = emptyList(),
        abstractText = null,
        progress = 0f,
        status = ReadingStatus.UNREAD,
        subjects = emptyList(),
        manifestations = emptyList(),
    )
}
