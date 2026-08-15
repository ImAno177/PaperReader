package dev.paperreader.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.screen.DiscoverScreen
import dev.paperreader.app.ui.screen.UpdatesScreen
import dev.paperreader.app.ui.model.toSearchPaperUi
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.SavedSearch
import dev.paperreader.logic.domain.SavedSearchFailure
import dev.paperreader.logic.domain.SavedSearchFailureKind
import dev.paperreader.logic.domain.SavedSearchFeed
import dev.paperreader.logic.domain.SavedSearchHit
import dev.paperreader.logic.domain.SavedSearchHitId
import dev.paperreader.logic.domain.SavedSearchId
import dev.paperreader.logic.domain.SavedSearchSource
import dev.paperreader.logic.provider.InstalledProvider
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.provider.RemotePaper
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.CitationMetrics
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.usecase.SearchResultCluster
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedSearchUpdatesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun discoverFieldTracksANewExternallySubmittedQuery() {
        var state by mutableStateOf(SearchUiState(submittedQuery = "old query"))
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = state,
                    onSearch = {},
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                )
            }
        }

        composeRule.onNodeWithText("old query").assertExists()
        composeRule.runOnIdle {
            state = SearchUiState(submittedQuery = "2501.04510v2", running = true)
        }
        composeRule.onNodeWithText("2501.04510v2").assertExists()
    }

    @Test
    fun discoverCreatesTheSubmittedRealProviderSearch() {
        composeRule.enableAccessibilityChecks()
        var savedQuery: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = SearchUiState(
                        submittedQuery = "graph learning",
                        providerCount = 2,
                        providerIds = setOf("arxiv", "semanticscholar"),
                    ),
                    onSearch = {},
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                    onSaveSearch = { savedQuery = it },
                )
            }
        }

        composeRule.onNodeWithText("Save search").performClick()

        composeRule.runOnIdle { assertEquals("graph learning", savedQuery) }
    }

    @Test
    fun discoverDoesNotReuseCreatedStateFromAnotherProviderSet() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = SearchUiState(
                        submittedQuery = "graph learning",
                        providerCount = 2,
                        providerIds = setOf("arxiv", "semanticscholar"),
                    ),
                    savedSearchActions = SavedSearchActionUiState(
                        createdSearchIds = mapOf(
                            SavedSearchActionKey("graph learning", listOf("arxiv")) to "arxiv-only",
                        ),
                    ),
                    onSearch = {},
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                    onSaveSearch = {},
                )
            }
        }

        composeRule.onNodeWithText("Save search").assertExists()
        composeRule.onNodeWithText("View in Updates").assertDoesNotExist()
    }

    @Test
    fun discoverResultOpensAFullRemotePreviewBeforeSaving() {
        composeRule.enableAccessibilityChecks()
        val result = SearchResultCluster(
            listOf(
                RemotePaper(
                    providerId = "semanticscholar",
                    providerRecordId = "10.1000/preview",
                    title = "A remote paper worth previewing",
                    abstractText = "This complete abstract remains readable before the paper is saved locally.",
                    manifestations = listOf(
                        RemoteManifestation(
                            type = ManifestationType.VERSION_OF_RECORD,
                            landingPageUrl = "https://doi.org/10.1000/preview",
                            pdfUrl = "https://example.org/paper.pdf",
                            license = "CC BY 4.0",
                        ),
                    ),
                    citationMetrics = CitationMetrics(42, "semanticscholar", NOW),
                ),
            ),
        ).toSearchPaperUi(mapOf("semanticscholar" to "Semantic Scholar"))
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = SearchUiState(submittedQuery = "remote preview", results = listOf(result)),
                    onSearch = {},
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                )
            }
        }

        composeRule.onNodeWithText("A remote paper worth previewing").performClick()

        composeRule.onNodeWithText("Abstract").assertExists()
        composeRule.onNodeWithText("42 citations").assertExists()
        composeRule.onNodeWithText("Available versions").assertExists()
        composeRule.onNodeWithText("Open source page").assertExists()
        composeRule.onNodeWithText("Open PDF").assertExists()
    }

    @Test
    fun discoverShowsProviderAwareSearchFilters() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = SearchUiState(
                        submittedQuery = "attention is all you need",
                        providerStates = listOf(
                            ProviderSearchUiState(
                                providerId = "arxiv",
                                providerName = "arXiv",
                                status = ProviderSearchStatus.SEARCHING,
                            ),
                            ProviderSearchUiState(
                                providerId = "semanticscholar",
                                providerName = "Semantic Scholar",
                                status = ProviderSearchStatus.READY,
                                resultCount = 2,
                            ),
                        ),
                    ),
                    onSearch = {},
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                )
            }
        }

        composeRule.onNodeWithText("All sources").assertExists()
        composeRule.onNodeWithText("Has results").assertExists()
        composeRule.onNodeWithText("arXiv · searching").assertExists()
        composeRule.onNodeWithText("Semantic Scholar · 2").assertExists()
    }

    @Test
    fun discoverCanRerunARecentNaturalLanguageQuery() {
        var submitted: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                DiscoverScreen(
                    state = SearchUiState(recentQueries = listOf("attention is all you need")),
                    onSearch = { submitted = it },
                    onClear = {},
                    onSave = {},
                    onOpenPaper = {},
                )
            }
        }

        composeRule.onNodeWithText("attention is all you need").performClick()
        composeRule.runOnIdle { assertEquals("attention is all you need", submitted) }
    }

    @Test
    fun updatesRendersDurableHitsAndDispatchesInboxActions() {
        composeRule.enableAccessibilityChecks()
        val feed = feed()
        var savedHit: String? = null
        var readHit: String? = null
        var deletedSearch: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.DOODLE) {
                UpdatesScreen(
                    tasks = LoadState.Ready(emptyList()),
                    providers = providerState(),
                    savedSearches = LoadState.Ready(listOf(feed)),
                    onSaveHit = { savedHit = it },
                    onMarkHitRead = { readHit = it },
                    onDeleteSearch = { deletedSearch = it },
                )
            }
        }

        composeRule.onNodeWithText("A real saved-search result").assertExists()
        composeRule.onNodeWithText("New").assertExists()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Mark read").performClick()
        composeRule.onNodeWithContentDescription("Delete saved search").performClick()
        composeRule.onNodeWithText("Delete this saved search?").assertExists()
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.runOnIdle {
            assertEquals(HIT_ID.value, savedHit)
            assertEquals(HIT_ID.value, readHit)
            assertEquals(SEARCH_ID.value, deletedSearch)
        }
    }

    @Test
    fun staleResultsRemainVisibleWithTypedProviderFailure() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.DOODLE) {
                UpdatesScreen(
                    tasks = LoadState.Ready(emptyList()),
                    providers = providerState(),
                    savedSearches = LoadState.Ready(
                        listOf(
                            feed().copy(
                                search = feed().search.copy(
                                    sources = listOf(
                                        SavedSearchSource(
                                            providerId = "arxiv",
                                            lastCheckedAt = NOW,
                                            lastSuccessAt = Instant.EPOCH,
                                            failure = SavedSearchFailure(SavedSearchFailureKind.UNAVAILABLE),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("A real saved-search result").assertExists()
        composeRule.onNodeWithText("arXiv is unavailable.").assertExists()
    }

    private fun feed() = SavedSearchFeed(
        search = SavedSearch(
            id = SEARCH_ID,
            queryText = "graph learning",
            sources = listOf(
                SavedSearchSource(
                    providerId = "arxiv",
                    lastCheckedAt = NOW,
                    lastSuccessAt = NOW,
                ),
            ),
            createdAt = Instant.EPOCH,
        ),
        hits = listOf(
            SavedSearchHit(
                id = HIT_ID,
                searchId = SEARCH_ID,
                paper = RemotePaper(
                    providerId = "arxiv",
                    providerRecordId = "2401.00001v2",
                    title = "A real saved-search result",
                ),
                fingerprint = "a".repeat(64),
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                unread = true,
            ),
        ),
    )

    private fun providerState() = ProviderManagerState(
        installed = listOf(
            InstalledProvider(
                descriptor = ProviderDescriptor("arxiv", "arXiv", 3_000),
                origin = ProviderOrigin.BUILT_IN,
            ),
        ),
    )

    private companion object {
        val SEARCH_ID = SavedSearchId("saved-search-ui")
        val HIT_ID = SavedSearchHitId("saved-search-hit-ui")
        val NOW: Instant = Instant.parse("2026-08-12T12:00:00Z")
    }
}
