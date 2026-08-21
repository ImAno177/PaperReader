package dev.paperreader.app.ui

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
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LoadState.Ready(
                        listOf(
                            paper("attention", "Attention Is All You Need", listOf("Ashish Vaswani")),
                            paper("engine", "Analytical Engine", listOf("Ada Lovelace")),
                        ),
                    ),
                    onLayoutChange = {},
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
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LoadState.Ready(
                        listOf(
                            paper("assigned", "Assigned paper", emptyList()).copy(collectionIds = setOf(5L)),
                            paper("outside", "Outside paper", emptyList()),
                        ),
                    ),
                    collections = LoadState.Ready(listOf(PaperCollectionUi(5L, "Methods"))),
                    onLayoutChange = {},
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
                    state = LoadState.Ready(listOf(paper("paper", "Paper", emptyList()))),
                    layout = LibraryLayout.LIST,
                    onLayoutChange = { requested = it },
                    onOpenPaper = {},
                    onDiscover = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show grid").performClick()
        composeRule.runOnIdle { assertEquals(LibraryLayout.GRID, requested) }
    }

    @Test
    fun paperCardExposesAFullQuickReadAction() {
        var read = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                LibraryScreen(
                    state = LoadState.Ready(listOf(paper("paper", "Paper", emptyList()))),
                    onLayoutChange = {},
                    onOpenPaper = {},
                    onDiscover = {},
                    onReadPaper = { read = true },
                )
            }
        }

        composeRule.onNodeWithText("Read").performClick()
        composeRule.runOnIdle { assertTrue(read) }
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
