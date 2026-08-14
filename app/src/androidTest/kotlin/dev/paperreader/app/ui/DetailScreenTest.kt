package dev.paperreader.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.ManifestationUi
import dev.paperreader.app.ui.model.LocalCopyUi
import dev.paperreader.app.ui.screen.DetailScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.task.DeleteDownloadResult
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun removalRequiresConfirmationAndReportsCompletion() {
        composeRule.enableAccessibilityChecks()
        var removed = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(paper()),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = { removed = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Remove from library").performClick()
        composeRule.onNodeWithText("Remove this paper?").assertExists()
        composeRule.onNodeWithText("Remove").performClick()

        composeRule.runOnIdle { assertTrue(removed) }
    }

    @Test
    fun downloadActionUsesTheSelectedManifestationId() {
        composeRule.enableAccessibilityChecks()
        var requestedId: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(
                            manifestations = listOf(
                                manifestation(id = "manifestation-1", local = false),
                            ),
                        ),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRequestDownload = { requestedId = it },
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Download PDF").performClick()
        composeRule.runOnIdle { assertTrue(requestedId == "manifestation-1") }
    }

    @Test
    fun arxivManifestationMakesMobileReadingThePrimaryVisibleAction() {
        composeRule.enableAccessibilityChecks()
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(manifestations = listOf(manifestation(id = "manifestation-1", local = true))),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Read mobile version").assertExists().assertIsEnabled()
        composeRule.onNodeWithText("Read downloaded PDF").assertExists().assertIsEnabled()
    }

    @Test
    fun paperDetailSurfacesReadingAndAbstractWithoutLocalCopyBanner() {
        composeRule.enableAccessibilityChecks()
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(
                            manifestations = listOf(manifestation(id = "manifestation-1", local = false)),
                        ).copy(abstractText = "A concise abstract for the saved paper."),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Read mobile version").assertExists().assertIsEnabled()
        composeRule.onNodeWithText("Abstract").assertExists()
    }

    @Test
    fun arxivManifestationDefersLicenseToTheVerifiedMobileSource() {
        composeRule.enableAccessibilityChecks()
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(manifestations = listOf(manifestation(id = "manifestation-1", local = true))),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("License shown in verified mobile source")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun deletingLocalPdfRequiresConfirmation() {
        composeRule.enableAccessibilityChecks()
        var deleted = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(
                            manifestations = listOf(
                                manifestation(id = "manifestation-1", local = true),
                            ),
                        ),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onDeleteDownload = {
                        deleted = true
                        DeleteDownloadResult.Deleted
                    },
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Delete local copy").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this local PDF?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertTrue(deleted) }
    }

    @Test
    fun collectionAssignmentSendsSelectedCollectionIds() {
        composeRule.enableAccessibilityChecks()
        var assignedIds: Set<Long>? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(paper()),
                    collections = LoadState.Ready(listOf(PaperCollectionUi(11L, "Reading queue"))),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onSetCollections = { ids ->
                        assignedIds = ids
                        SetPaperCollectionsResult.Updated
                    },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Manage collections").performClick()
        composeRule.onNodeWithText("Reading queue").performClick()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle { assertTrue(assignedIds == setOf(11L)) }
    }

    @Test
    fun openingAssignmentWhileCollectionsLoadDoesNotEraseExistingSelection() {
        composeRule.enableAccessibilityChecks()
        val collectionState = mutableStateOf<LoadState<List<PaperCollectionUi>>>(LoadState.Loading)
        var assignedIds: Set<Long>? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(paper().copy(collectionIds = setOf(11L))),
                    collections = collectionState.value,
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onSetCollections = { ids ->
                        assignedIds = ids
                        SetPaperCollectionsResult.Updated
                    },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Manage collections").performClick()
        composeRule.runOnIdle {
            collectionState.value = LoadState.Ready(listOf(PaperCollectionUi(11L, "Reading queue")))
        }
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle { assertTrue(assignedIds == setOf(11L)) }
    }

    @Test
    fun recreationDuringAssignmentDoesNotLockDialogOrLoseSelection() {
        val restorationTester = StateRestorationTester(composeRule)
        val requestGate = CompletableDeferred<Unit>()
        var attempts = 0
        var retriedIds: Set<Long>? = null
        restorationTester.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(paper()),
                    collections = LoadState.Ready(listOf(PaperCollectionUi(11L, "Reading queue"))),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onSetCollections = { ids ->
                        attempts += 1
                        if (attempts == 1) {
                            requestGate.await()
                        } else {
                            retriedIds = ids
                        }
                        SetPaperCollectionsResult.Updated
                    },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Manage collections").performClick()
        composeRule.onNodeWithText("Reading queue").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { attempts == 1 }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Cancel").assertIsEnabled()
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { attempts == 2 }
        composeRule.runOnIdle { assertTrue(retriedIds == setOf(11L)) }
    }

    private fun paper(manifestations: List<ManifestationUi> = emptyList()): PaperUi {
        val identifier = PaperIdentifier(IdentifierType.ARXIV, "1706.03762")
        return PaperUi(
            id = "work-1",
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani"),
            savedAt = Instant.parse("2026-08-11T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-11T00:00:00Z"),
            publishedDate = LocalDate.of(2017, 6, 12),
            sources = listOf("arxiv"),
            primaryIdentifier = identifier,
            identifiers = listOf(identifier),
            abstractText = null,
            progress = 0f,
            status = ReadingStatus.UNREAD,
            subjects = emptyList(),
            manifestations = manifestations,
        )
    }

    private fun manifestation(id: String, local: Boolean) = ManifestationUi(
        id = id,
        type = ManifestationType.PREPRINT,
        source = "arxiv",
        version = "1",
        publishedDate = LocalDate.of(2017, 6, 12),
        landingPageUrl = "https://arxiv.org/abs/1706.03762",
        pdfUrl = "https://arxiv.org/pdf/1706.03762",
        license = null,
        localCopy = if (local) {
            LocalCopyUi(
                sha256 = "a".repeat(64),
                byteLength = 1_048_576,
                updatedAt = Instant.parse("2026-08-11T00:00:00Z"),
            )
        } else {
            null
        },
    )
}
