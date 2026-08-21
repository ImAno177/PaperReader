package dev.paperreader.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.screen.MoreScreen
import dev.paperreader.app.ui.screen.SourcesScreen
import dev.paperreader.app.extensions.ExtensionInstallState
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.provider.InstalledProvider
import dev.paperreader.logic.provider.OrphanedProviderPlugin
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.provider.UntrustedProviderPlugin
import dev.paperreader.logic.plugin.ExtensionReleaseKind
import dev.paperreader.logic.plugin.ExtensionStoreRecord
import dev.paperreader.logic.plugin.ExtensionStoreRegistryState
import dev.paperreader.logic.plugin.VerifiedExtensionRelease
import dev.paperreader.logic.plugin.VerifiedExtensionStoreIndex
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceLifecycleShowsInstalledAvailableAndBlockedPackages() {
        composeRule.enableAccessibilityChecks()
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(providers = providerState(), onBack = {})
            }
        }

        composeRule.onNodeWithText("Blocked extensions").assertIsDisplayed()
        composeRule.onNodeWithText("Untrusted").assertIsDisplayed()
        composeRule.onNodeWithText("Blocked extensions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Show details for Untrusted").performClick()
        composeRule.onNodeWithText("Package: dev.example.untrusted").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Installed"))
        composeRule.onNodeWithText("Installed").assertIsDisplayed()
        composeRule.onNodeWithText("arXiv").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Available"))
        composeRule.onNodeWithText("Available").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Example provider"))
        composeRule.onNodeWithText("Example provider").assertIsDisplayed()
        composeRule.onNodeWithText("v2").assertIsDisplayed()
    }

    @Test
    fun signedStoreShowsVerifiedReleaseAndInstallAction() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(),
                    extensionStores = signedStoreState(),
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PaperReader community"))
        composeRule.onNodeWithText("PaperReader community").assertIsDisplayed()
        composeRule.onNodeWithText("Semantic Scholar").assertDoesNotExist()
        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNodeWithText("Semantic Scholar").assertIsDisplayed()
        composeRule.onNodeWithText("Install extension").assertIsDisplayed()
    }

    @Test
    fun signedThemeUsesVerifiedInAppInstallerInsteadOfExternalDownload() {
        var requestedPackage: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(),
                    extensionStores = signedThemeStoreState(),
                    onInstallExtension = { requestedPackage = it.packageName },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PaperReader Community Theme"))
        composeRule.onNodeWithText("Install extension").performClick()
        assertEquals("dev.paperreader.themes.community", requestedPackage)
    }

    @Test
    fun installedThemeDoesNotOfferInstallAgain() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(),
                    extensionStores = signedThemeStoreState(),
                    installedThemeVersions = mapOf("dev.paperreader.themes.community" to 4L),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PaperReader Community Theme"))
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()
        assertTrue(composeRule.onAllNodesWithText("Installed").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun pendingInstallExposesARealCancelAction() {
        var cancelledPackage: String? = null
        val packageName = "dev.paperreader.extensions.semanticscholar"
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(),
                    extensionStores = signedStoreState(),
                    installStates = mapOf(packageName to ExtensionInstallState.Pending),
                    onDismissInstallState = { cancelledPackage = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Cancel · Waiting to download"))
        composeRule.onNodeWithText("Cancel · Waiting to download").performClick()
        assertEquals(packageName, cancelledPackage)
    }

    @Test
    fun pinnedStoreAndBlockedInstalledReleaseDoNotOfferDestructiveOrRepeatActions() {
        val blockedPackage = "dev.paperreader.extensions.semanticscholar"
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(
                        available = listOf(
                            AvailableProviderPlugin(
                                packageName = blockedPackage,
                                displayName = "Semantic Scholar",
                                versionCode = 3,
                                providerIds = setOf("semanticscholar"),
                                installedVersionCode = 1,
                            ),
                        ),
                        untrusted = listOf(
                            UntrustedProviderPlugin(
                                packageName = blockedPackage,
                                signerSha256 = "cd".repeat(32),
                                reason = "Signer mismatch",
                                installedVersionCode = 1,
                                updateCanRemediate = false,
                            ),
                        ),
                    ),
                    extensionStores = signedStoreState(pinned = true),
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PaperReader community"))
        composeRule.onNodeWithText("Official").assertIsDisplayed()
        composeRule.onNodeWithText("Blocked extensions").assertIsDisplayed()
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()
    }

    @Test
    fun blockedBelowMinimumReleaseOffersVerifiedUpdate() {
        val packageName = "dev.paperreader.extensions.semanticscholar"
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(
                        available = listOf(
                            AvailableProviderPlugin(
                                packageName = packageName,
                                displayName = "Semantic Scholar",
                                versionCode = 3,
                                providerIds = setOf("semanticscholar"),
                                installedVersionCode = 1,
                            ),
                        ),
                        untrusted = listOf(
                            UntrustedProviderPlugin(
                                packageName = packageName,
                                signerSha256 = "cd".repeat(32),
                                reason = "Installed version is below the release minimum",
                                installedVersionCode = 1,
                                updateCanRemediate = true,
                            ),
                        ),
                    ),
                    extensionStores = signedStoreState(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Semantic Scholar"))
        assertTrue(
            composeRule.onAllNodesWithText("Update extension").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText("Installed but blocked").assertDoesNotExist()
    }

    @Test
    fun catalogReleaseWithoutArtifactCannotOfferInstall() {
        val store = signedStoreState().stores.single()
        val release = store.index.releases.single().copy(apkSha256 = null, apkSizeBytes = null)
        val legacyStore = signedStoreState().copy(
            stores = listOf(store.copy(index = store.index.copy(releases = listOf(release)))),
        )
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(),
                    extensionStores = legacyStore,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("1 release").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Semantic Scholar"))
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()
        composeRule.onNodeWithText("Unavailable").assertIsDisplayed()
    }

    @Test
    fun currentInstalledReleaseDoesNotOfferInstallAgain() {
        val installedPackage = "dev.paperreader.extensions.semanticscholar"
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(
                        installed = listOf(
                            InstalledProvider(
                                descriptor = ProviderDescriptor(
                                    id = "semanticscholar",
                                    displayName = "Semantic Scholar",
                                    minimumRequestIntervalMillis = 1_000,
                                ),
                                origin = ProviderOrigin.COMMUNITY_PLUGIN,
                                packageName = installedPackage,
                                versionCode = 3,
                            ),
                        ),
                    ),
                    extensionStores = signedStoreState(),
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("PaperReader community"))
        composeRule.onNodeWithText("Install extension").assertDoesNotExist()
        assertTrue(composeRule.onAllNodesWithText("Installed").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun MoreHubCombinesAllProviderIssuesIntoOneReviewCount() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                MoreScreen(
                    selectedPreset = PaperThemePreset.NEOBRUTALISM,
                    providers = providerState().copy(
                        orphaned = listOf(
                            OrphanedProviderPlugin(
                                packageName = "dev.example.orphaned",
                                displayName = "Orphaned",
                                versionCode = 1,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("2 need review"))
        composeRule.onNodeWithText("2 need review").assertIsDisplayed()
    }

    @Test
    fun MoreHubIncludesFocusedAboutBranch() {
        var opened = false
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                MoreScreen(
                    selectedPreset = PaperThemePreset.NEOBRUTALISM,
                    onOpenAbout = { opened = true },
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").performClick()
        assertTrue(opened)
    }

    @Test
    fun installedProviderCanBeExcludedWithoutBeingUninstalled() {
        var changed: Pair<String, Boolean>? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = providerState(),
                    onProviderEnabledChange = { id, enabled -> changed = id to enabled },
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("arXiv"))
        composeRule.onNodeWithContentDescription("arXiv").assertIsOn().performClick()
        assertEquals("arxiv" to false, changed)
    }

    @Test
    fun orphanedProviderDefersTechnicalDetails() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(
                    providers = ProviderManagerState(
                        orphaned = listOf(
                            OrphanedProviderPlugin(
                                packageName = "dev.example.orphaned",
                                displayName = "Example source",
                                versionCode = 1,
                            ),
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Package: dev.example.orphaned").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show details for Example source").performClick()
        composeRule.onNodeWithText("Package: dev.example.orphaned").assertIsDisplayed()
    }

    @Test
    fun emptySourcesStateRendersInsideLazyListWithoutNestedScrollCrash() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                SourcesScreen(providers = ProviderManagerState(), onBack = {})
            }
        }

        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("No providers installed"))
        composeRule.onNodeWithText("No providers installed").assertIsDisplayed()
    }

    private fun providerState() = ProviderManagerState(
        installed = listOf(
            InstalledProvider(
                descriptor = ProviderDescriptor(
                    id = "arxiv",
                    displayName = "arXiv",
                    minimumRequestIntervalMillis = 3_000,
                ),
                origin = ProviderOrigin.BUILT_IN,
            ),
        ),
        available = listOf(
            AvailableProviderPlugin(
                packageName = "dev.example.provider",
                displayName = "Example provider",
                versionCode = 2,
                providerIds = setOf("example"),
            ),
        ),
        untrusted = listOf(
            UntrustedProviderPlugin(
                packageName = "dev.example.untrusted",
                signerSha256 = "ab".repeat(32),
                reason = "Signer mismatch",
            ),
        ),
    )

    private fun signedStoreState(pinned: Boolean = false) = ExtensionStoreRegistryState(
        stores = listOf(
            ExtensionStoreRecord(
                indexUrl = "https://example.org/index.json",
                pinned = pinned,
                index = VerifiedExtensionStoreIndex(
                    storeId = "paperreader.community",
                    displayName = "PaperReader community",
                    websiteUrl = "https://example.org/extensions",
                    sequence = 7,
                    generatedAt = Instant.parse("2026-08-13T05:59:00Z"),
                    publicKeySha256 = "ab".repeat(32),
                    signedPayloadSha256 = "ef".repeat(32),
                    releases = listOf(
                        VerifiedExtensionRelease(
                            kind = ExtensionReleaseKind.SOURCE,
                            packageName = "dev.paperreader.extensions.semanticscholar",
                            serviceClassName = "dev.paperreader.extensions.semanticscholar.SemanticScholarService",
                            displayName = "Semantic Scholar",
                            versionCode = 3,
                            minimumVersionCode = 2,
                            versionName = "1.2.0",
                            signerSha256 = "cd".repeat(32),
                            minimumHostApi = 1,
                            maximumHostApi = 1,
                            installUrl = "https://example.org/semanticscholar.apk",
                            apkSha256 = "01".repeat(32),
                            apkSizeBytes = 1_048_576,
                            license = "Apache-2.0",
                            privacyUrl = null,
                            providerId = "semanticscholar",
                            minimumRequestIntervalMillis = 1_000,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun signedThemeStoreState() = ExtensionStoreRegistryState(
        stores = listOf(
            ExtensionStoreRecord(
                indexUrl = "https://example.org/index.json",
                pinned = false,
                index = VerifiedExtensionStoreIndex(
                    storeId = "paperreader.themes",
                    displayName = "PaperReader themes",
                    websiteUrl = "https://example.org/themes",
                    sequence = 4,
                    generatedAt = Instant.parse("2026-08-13T05:59:00Z"),
                    publicKeySha256 = "ab".repeat(32),
                    signedPayloadSha256 = "ef".repeat(32),
                    releases = listOf(
                        VerifiedExtensionRelease(
                            kind = ExtensionReleaseKind.THEME,
                            packageName = "dev.paperreader.themes.community",
                            serviceClassName = "dev.paperreader.themes.community.CommunityThemeService",
                            displayName = "PaperReader Community Theme",
                            versionCode = 4,
                            minimumVersionCode = 4,
                            versionName = "1.0.0",
                            signerSha256 = "cd".repeat(32),
                            minimumHostApi = 1,
                            maximumHostApi = 1,
                            installUrl = "https://example.org/community-theme.apk",
                            apkSha256 = "02".repeat(32),
                            apkSizeBytes = 524_288,
                            license = "Apache-2.0",
                            privacyUrl = null,
                            themeIds = setOf("community"),
                        ),
                    ),
                ),
            ),
        ),
    )
}
