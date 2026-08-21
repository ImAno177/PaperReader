package dev.paperreader.app.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.ReadingHistoryUi
import dev.paperreader.app.ui.screen.HistoryScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openAndRemoveAreSeparateActionsAndRemovalRequiresConfirmation() {
        composeRule.enableAccessibilityChecks()
        var openedId: String? = null
        var removedId: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                HistoryScreen(
                    state = LoadState.Ready(listOf(historyEntry())),
                    onOpenPaper = { openedId = it },
                    onRemove = { removedId = it },
                )
            }
        }

        composeRule.onNodeWithText(PAPER_TITLE).performClick()
        composeRule.runOnIdle { assertEquals(WORK_ID, openedId) }

        composeRule.onNodeWithContentDescription("Remove from history").performClick()
        composeRule.onNodeWithText("Remove from reading history?").assertExists()
        composeRule.onNodeWithText(
            "Remove “$PAPER_TITLE” from History? The saved paper remains available in Library.",
        ).assertExists()
        composeRule.runOnIdle { assertNull(removedId) }

        composeRule.onNodeWithText("Remove").performClick()
        composeRule.runOnIdle { assertEquals(WORK_ID, removedId) }
    }

    private fun historyEntry() = ReadingHistoryUi(
        workId = WORK_ID,
        title = PAPER_TITLE,
        lastReadAt = Instant.parse("2026-08-21T08:00:00Z"),
        totalReadDuration = Duration.ofMinutes(18),
        sessionCount = 2,
        progression = 0.42f,
    )

    private companion object {
        const val WORK_ID = "work-attention"
        const val PAPER_TITLE = "Attention Is All You Need"
    }
}
