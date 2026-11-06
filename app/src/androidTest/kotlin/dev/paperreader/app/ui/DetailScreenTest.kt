package dev.paperreader.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import dev.paperreader.app.ui.state.LoadState
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
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperDocument
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.task.DeleteDownloadResult
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
    fun arxivManifestationSurfacesReadingAbstractAndVerifiedLicense() {
        composeRule.enableAccessibilityChecks()
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(manifestations = listOf(manifestation(id = "manifestation-1", local = true)))
                            .copy(abstractText = "A concise abstract for the saved paper."),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Read").assertExists().assertIsEnabled()
        composeRule.onNodeWithText("Open PDF").assertExists().assertIsEnabled()
        composeRule.onNodeWithText("Abstract").assertExists()
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

        composeRule.onNodeWithText("More file options").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete local copy").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this local PDF?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertTrue(deleted) }
    }

    @Test
    fun arxivVersionOffersReadableHtmlBesideItsFileActions() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(manifestations = listOf(manifestation(id = "manifestation-1", local = false))),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Download PDF").performScrollTo().assertExists()
        composeRule.onNodeWithText("Download readable HTML").performScrollTo().assertExists()
    }

    @Test
    fun readableHtmlFailureIsExplainedInsideTheVersionCard() {
        var loadAttempts = 0
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DetailScreen(
                    state = LoadState.Ready(
                        paper(manifestations = listOf(manifestation(id = "manifestation-1", local = false))),
                    ),
                    onBack = {},
                    onStatusChange = {},
                    onLoadReadablePaper = { _, _ ->
                        loadAttempts += 1
                        ReadablePaperResult.Unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND)
                    },
                    onRemove = { RemovePaperResult.Removed },
                    onRemoved = {},
                )
            }
        }

        composeRule.onNodeWithText("Download readable HTML").performScrollTo().performClick()
        composeRule.waitUntil { loadAttempts == 1 }
        composeRule.onNodeWithText("No official HTML for this version. The PDF is unchanged.")
            .assertExists()
    }

    @Test
    fun readableHtmlExportRestoresTheExactDocumentAfterSavedInstanceStateRestore() {
        val restorationTester = StateRestorationTester(composeRule)
        val registry = RecordingActivityResultRegistry()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File.createTempFile("paperreader-readable-export", ".html", targetContext.cacheDir)
        val original = readableDocument()
        val restoredHashRequests = CopyOnWriteArrayList<String?>()
        val restored = AtomicBoolean(false)
        val completedHtml = java.util.concurrent.atomic.AtomicReference<String?>()

        try {
            restorationTester.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                        DetailScreen(
                            state = LoadState.Ready(
                                paper(manifestations = listOf(manifestation(id = "manifestation-1", local = false))),
                            ),
                            onBack = {},
                            onStatusChange = {},
                            onLoadReadablePaper = { _, requestedHash ->
                                if (restored.get()) restoredHashRequests.add(requestedHash)
                                when (requestedHash) {
                                    null -> ReadablePaperResult.Ready(original)
                                    original.documentSha256 -> {
                                        ReadablePaperResult.Ready(original.copy(keptForOffline = true))
                                    }
                                    else -> ReadablePaperResult.Unavailable(ReadablePaperFailure.INVALID_RESPONSE)
                                }
                            },
                            onRemove = { RemovePaperResult.Removed },
                            onRemoved = {},
                        )
                    }
                }
            }

            composeRule.onNodeWithText("Download readable HTML").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                registry.launchedFileName != null
            }

            restorationTester.emulateSavedInstanceStateRestore()
            restored.set(true)
            composeRule.runOnIdle {
                assertTrue(registry.dispatchSavedDocument(Uri.fromFile(destination)))
            }

            composeRule.waitUntil(timeoutMillis = 5_000) {
                restoredHashRequests.contains(original.documentSha256)
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                val html = runCatching { destination.readText(Charsets.UTF_8) }.getOrNull()
                    ?: return@waitUntil false
                if (html.contains("ORIGINAL_EXPORT_BODY_MARKER") && html.trimEnd().endsWith("</html>")) {
                    completedHtml.set(html)
                    true
                } else {
                    false
                }
            }

            val html = checkNotNull(completedHtml.get())
            assertTrue(html.contains("ORIGINAL_EXPORT_BODY_MARKER"))
            assertTrue(
                html.contains(
                    "name=\"paperreader-title\" content=\"Original export title\"",
                ),
            )
            assertTrue(
                html.contains(
                    "name=\"paperreader-document-sha256\" content=\"${original.documentSha256}\"",
                ),
            )
        } finally {
            destination.delete()
        }
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

    private fun readableDocument() = ReadablePaperDocument(
        bodyHtml = "<article><p>ORIGINAL_EXPORT_BODY_MARKER</p></article>",
        title = "Original export title",
        sourceUrl = "https://arxiv.org/html/1706.03762v1",
        sourceProvider = "arxiv",
        sourceVersion = "v1",
        license = "CC BY 4.0",
        sourceSha256 = "a".repeat(64),
        documentSha256 = "b".repeat(64),
        retrievedAt = Instant.parse("2026-08-11T00:00:00Z"),
        servedFromCache = false,
        sections = emptyList(),
        warnings = emptySet(),
        sourceRecordId = "1706.03762v1",
        readableContractVersion = "arxiv-html-10",
    )

    private class RecordingActivityResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry
            get() = this

        private var createDocumentRequestCode: Int? = null
        var launchedFileName: String? = null
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            createDocumentRequestCode = requestCode
            launchedFileName = input as? String
        }

        fun dispatchSavedDocument(destination: Uri): Boolean {
            val requestCode = createDocumentRequestCode ?: return false
            return dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(destination))
        }
    }
}
