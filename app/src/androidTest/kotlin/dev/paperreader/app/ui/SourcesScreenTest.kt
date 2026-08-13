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
        composeRule.onNodeWithText("Discovery only.", substring = true).assertIsDisplayed()
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
}
