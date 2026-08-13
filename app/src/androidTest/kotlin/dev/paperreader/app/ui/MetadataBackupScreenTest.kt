package dev.paperreader.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.MetadataBackupSummaryUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.MetadataRestorePreviewUi
import dev.paperreader.app.ui.model.MetadataRestoreIssueUi
import dev.paperreader.app.ui.screen.DataBackupScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataBackupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun metadataBackupActionsAreExplicitAndDoNotClaimPdfCoverage() {
        composeRule.enableAccessibilityChecks()
        var exportRequested = false
        var importRequested = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DataBackupScreen(
                    state = MetadataBackupUiState.Idle,
                    onRequestExport = { exportRequested = true },
                    onRequestImport = { importRequested = true },
                    onConfirmRestore = {},
                    onDismissState = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Create metadata backup"))
        composeRule.onNodeWithText("Create metadata backup").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Restore from backup").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(
            "PDFs, active downloads, caches, plugins, and credentials are never included.",
            substring = true,
        ).assertExists()

        composeRule.runOnIdle {
            assertTrue(exportRequested)
            assertTrue(importRequested)
        }
    }

    @Test
    fun restoreRequiresReviewAndExplicitConfirmation() {
        var restoreConfirmed = false
        val preview = MetadataRestorePreviewUi(
            createdAt = Instant.parse("2026-08-12T00:00:00Z"),
            summary = summary(),
            newWorks = 2,
            mergedWorks = 1,
            skippedWorks = 1,
            conflicts = listOf(MetadataRestoreIssueUi("alias_conflict", "w-conflict")),
            missingProviders = listOf("community.example"),
            dormantReadingStates = 1,
            dormantBookmarks = 1,
            dormantAnnotations = 0,
            skippedRecords = 2,
        )
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DataBackupScreen(
                    state = MetadataBackupUiState.Preview(preview),
                    onRequestExport = {},
                    onRequestImport = {},
                    onConfirmRestore = { restoreConfirmed = true },
                    onDismissState = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Restore this metadata backup?").assertIsDisplayed()
        composeRule.onNodeWithText("Existing local PDFs and active downloads are never replaced.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Restore").performClick()

        composeRule.runOnIdle { assertTrue(restoreConfirmed) }
    }

    @Test
    fun backupActionsAreLockedWhileAnArchiveIsBeingWritten() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DataBackupScreen(
                    state = MetadataBackupUiState.Exporting,
                    onRequestExport = {},
                    onRequestImport = {},
                    onConfirmRestore = {},
                    onDismissState = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Create metadata backup"))
        composeRule.onNodeWithText("Create metadata backup").assertIsNotEnabled()
        composeRule.onNodeWithText("Restore from backup").assertIsNotEnabled()
    }

    @Test
    fun restorePreviewReportsEverySectionAndMakesTruncationExplicit() {
        val preview = MetadataRestorePreviewUi(
            createdAt = Instant.parse("2026-08-12T00:00:00Z"),
            summary = summary(),
            newWorks = 2,
            mergedWorks = 0,
            skippedWorks = 1,
            conflicts = (1..5).map { index ->
                MetadataRestoreIssueUi("annotation_conflict", "annotation-$index")
            },
            missingProviders = (1..5).map { "provider-$it" },
            dormantReadingStates = 1,
            dormantBookmarks = 1,
            dormantAnnotations = 1,
            skippedRecords = 4,
        )
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DataBackupScreen(
                    state = MetadataBackupUiState.Preview(preview),
                    onRequestExport = {},
                    onRequestImport = {},
                    onConfirmRestore = {},
                    onDismissState = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("3 papers · 3 manifestations · 1 collection", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("2 reading states · 2 history entries", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("skipped papers 1 · skipped related records 4", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("5 unavailable providers", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Show more details").performClick()
        composeRule.onNodeWithText("provider-5", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("annotation-5", substring = true).assertIsDisplayed()
    }

    private fun summary() = MetadataBackupSummaryUi(
        works = 3,
        collections = 1,
        manifestations = 3,
        readingStates = 2,
        bookmarks = 1,
        annotations = 0,
        historyEntries = 2,
    )
}
