package dev.paperreader.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.screen.ReadingImportsScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPdfImportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importRequiresFilenameReviewAndEditableEnglishTitle() {
        composeRule.enableAccessibilityChecks()
        var confirmedTitle: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                ReadingImportsScreen(
                    state = LocalPdfImportUiState.Confirming(candidate()),
                    onRequestImport = {},
                    onConfirmImport = { confirmedTitle = it },
                    onDismissImport = {},
                    onOpenImportedPaper = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Review this local PDF").assertIsDisplayed()
        composeRule.onNodeWithText("attention-paper.pdf").assertIsDisplayed()
        val titleField = composeRule.onNodeWithText("Attention Paper")
        titleField.performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("A verified title")
        composeRule.onNodeWithText("Import PDF").performClick()

        composeRule.runOnIdle { assertEquals("A verified title", confirmedTitle) }
    }

    @Test
    fun importingLocksTheDialogAndExplainsVerification() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                ReadingImportsScreen(
                    state = LocalPdfImportUiState.Importing(candidate(), "Attention Paper"),
                    onRequestImport = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onOpenImportedPaper = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Verifying and importing the PDF").assertIsDisplayed()
        composeRule.onNodeWithText("Import PDF").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun editedTitleSurvivesSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                ReadingImportsScreen(
                    state = LocalPdfImportUiState.Confirming(candidate()),
                    onRequestImport = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onOpenImportedPaper = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("Process-safe edited title")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Process-safe edited title").assertIsDisplayed()
    }

    @Test
    fun exactDuplicateIsReportedAndCanOpenExistingPaper() {
        var openedWorkId: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                ReadingImportsScreen(
                    state = LocalPdfImportUiState.Complete(
                        workId = "w-existing",
                        title = "Attention Paper",
                        alreadyImported = true,
                    ),
                    onRequestImport = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onOpenImportedPaper = { openedWorkId = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("No duplicate was created.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Open imported paper").performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals("w-existing", openedWorkId) }
    }

    @Test
    fun invalidPdfFailureSaysNothingChangedAndRetryActionRemainsAvailable() {
        var chooseRequested = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                ReadingImportsScreen(
                    state = LocalPdfImportUiState.Failed(LocalPdfImportFailure.INVALID_PDF),
                    onRequestImport = { chooseRequested = true },
                    onConfirmImport = {},
                    onDismissImport = {},
                    onOpenImportedPaper = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("not a valid PDF", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Choose PDF").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(chooseRequested) }
    }

    private fun candidate() = LocalPdfCandidate(
        importToken = "fixture-import-token",
        sourceKey = "0".repeat(64),
        displayName = "attention-paper.pdf",
        suggestedTitle = "Attention Paper",
        byteLength = 2_048,
    )
}
