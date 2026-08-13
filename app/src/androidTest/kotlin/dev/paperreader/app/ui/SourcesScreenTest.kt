package dev.paperreader.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.screen.MoreScreen
import dev.paperreader.app.ui.screen.SourcesScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.provider.AvailableProviderPlugin
import dev.paperreader.logic.provider.InstalledProvider
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

        composeRule.onNodeWithText("Security attention").assertIsDisplayed()
        composeRule.onNodeWithText("dev.example.untrusted").assertIsDisplayed()
        composeRule.onNodeWithText("Blocked").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Installed"))
        composeRule.onNodeWithText("Installed").assertIsDisplayed()
        composeRule.onNodeWithText("arXiv").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Available packages"))
        composeRule.onNodeWithText("Available packages").assertIsDisplayed()
        composeRule.onNodeWithText("Example provider").assertIsDisplayed()
        composeRule.onNodeWithText("Android keeps the final install confirmation under your control.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun signedStoreShowsVerifiedReleaseAndDownloadAction() {
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
        composeRule.onNodeWithText("OpenAlex").assertIsDisplayed()
        composeRule.onNodeWithText("Open download page").assertIsDisplayed()
    }

    @Test
    fun MoreHubSurfacesBlockedProviderAttentionBeforeInstalledCount() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                MoreScreen(
                    selectedPreset = PaperThemePreset.NEOBRUTALISM,
                    providers = providerState(),
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("1 blocked provider needs review"))
        composeRule.onNodeWithText("1 blocked provider needs review").assertIsDisplayed()
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
            .performScrollToNode(hasText("No providers are installed."))
        composeRule.onNodeWithText("No providers are installed.").assertIsDisplayed()
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

    private fun signedStoreState() = ExtensionStoreRegistryState(
        stores = listOf(
            ExtensionStoreRecord(
                indexUrl = "https://example.org/index.json",
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
                            packageName = "dev.paperreader.extensions.openalex",
                            serviceClassName = "dev.paperreader.extensions.openalex.OpenAlexService",
                            displayName = "OpenAlex",
                            versionCode = 3,
                            minimumVersionCode = 2,
                            versionName = "1.2.0",
                            signerSha256 = "cd".repeat(32),
                            minimumHostApi = 1,
                            maximumHostApi = 1,
                            installUrl = "https://example.org/openalex.apk",
                            license = "Apache-2.0",
                            privacyUrl = null,
                            providerId = "openalex",
                            minimumRequestIntervalMillis = 1_000,
                        ),
                    ),
                ),
            ),
        ),
    )
}
