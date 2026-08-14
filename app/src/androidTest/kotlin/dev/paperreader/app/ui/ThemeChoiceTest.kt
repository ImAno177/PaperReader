package dev.paperreader.app.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.extensions.ThemeExtensionIssue
import dev.paperreader.app.ui.screen.AppearanceScreen
import dev.paperreader.app.ui.screen.CollectionsScreen
import dev.paperreader.app.ui.screen.UpdatesNotificationsScreen
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.PaperCollection
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeChoiceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allVisualPresetsAreAvailableAndSelectable() {
        composeRule.enableAccessibilityChecks()
        var selectedPreset by mutableStateOf(PaperThemePreset.NEOBRUTALISM)

        composeRule.setContent {
            PaperReaderTheme(preset = selectedPreset) {
                AppearanceScreen(
                    selectedPreset = selectedPreset,
                    onPresetChange = { selectedPreset = it },
                    onThemeModeChange = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Doodle").assertExists()
        composeRule.onNodeWithText("Neobrutalism").assertExists()
        composeRule.onNodeWithText("Doodle").performClick()

        composeRule.runOnIdle {
            assertEquals(PaperThemePreset.DOODLE, selectedPreset)
        }
    }

    @Test
    fun colorModeOffersSystemLightAndDarkAndReportsSelection() {
        composeRule.enableAccessibilityChecks()
        var selectedMode by mutableStateOf(PaperThemeMode.SYSTEM)

        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM, themeMode = selectedMode) {
                AppearanceScreen(
                    selectedPreset = PaperThemePreset.NEOBRUTALISM,
                    selectedThemeMode = selectedMode,
                    onPresetChange = {},
                    onThemeModeChange = { selectedMode = it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("System").assertExists()
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.runOnIdle { assertEquals(PaperThemeMode.DARK, selectedMode) }
    }

    @Test
    fun blockedCommunityThemeIsVisibleInsteadOfSilentlyDisappearing() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                AppearanceScreen(
                    selectedThemeKey = PaperThemePreset.NEOBRUTALISM.storageKey,
                    communityThemes = emptyList(),
                    communityThemesLoading = false,
                    communityThemeIssues = listOf(
                        ThemeExtensionIssue(
                            packageName = "dev.example.unsafe.theme",
                            message = "Theme package signer is not trusted",
                        ),
                    ),
                    onThemeChange = {},
                    onThemeModeChange = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Theme security"))
        composeRule.onNodeWithText("dev.example.unsafe.theme").assertExists()
        composeRule.onNodeWithText("Blocked").assertExists()
        composeRule.onNodeWithText("Theme package signer is not trusted").assertExists()
    }

    @Test
    fun automaticSavedSearchRefreshIsExplicitlyOptInAndReportsBlockedNotifications() {
        composeRule.enableAccessibilityChecks()
        var requested: Boolean? = null
        var automaticRefreshEnabled by mutableStateOf(false)
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                UpdatesNotificationsScreen(
                    automaticRefreshEnabled = automaticRefreshEnabled,
                    notificationsAvailable = false,
                    onAutomaticRefreshChange = { enabled ->
                        requested = enabled
                        automaticRefreshEnabled = enabled
                        true
                    },
                    onOpenNotificationSettings = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Off · refresh from Updates when needed.")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithContentDescription("Automatic saved-search refresh").performClick()
        composeRule.waitUntil { requested == true }
        composeRule.onNodeWithText("Background checks continue; notifications are blocked.")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("Open notification settings").assertExists()
    }

    @Test
    fun newCollectionSubmitsAnEnglishNamedRealUseCaseRequest() {
        composeRule.enableAccessibilityChecks()
        var submittedName: String? = null
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                CollectionsScreen(
                    collections = LoadState.Ready(emptyList()),
                    onCreateCollection = { name ->
                        submittedName = name
                        CreateCollectionResult.Created(
                            PaperCollection(
                                id = CollectionId(1L),
                                name = name,
                                sortOrder = 0,
                                createdAt = Instant.EPOCH,
                                updatedAt = Instant.EPOCH,
                            ),
                        )
                    },
                    onRenameCollection = { _, _ -> error("Rename is not exercised") },
                    onDeleteCollection = { error("Delete is not exercised") },
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("New collection"))
        composeRule.onNodeWithText("New collection").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Core methods")
        composeRule.onNodeWithText("Create").performClick()

        composeRule.runOnIdle { assertEquals("Core methods", submittedName) }
    }

    @Test
    fun recreationDuringCollectionCreationDoesNotLeaveDialogLocked() {
        val restorationTester = StateRestorationTester(composeRule)
        val requestGate = CompletableDeferred<Unit>()
        var attempts = 0
        var retriedName: String? = null
        restorationTester.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                CollectionsScreen(
                    collections = LoadState.Ready(emptyList()),
                    onCreateCollection = { name ->
                        attempts += 1
                        if (attempts == 1) {
                            requestGate.await()
                        } else {
                            retriedName = name
                        }
                        CreateCollectionResult.InvalidName
                    },
                    onRenameCollection = { _, _ -> error("Rename is not exercised") },
                    onDeleteCollection = { error("Delete is not exercised") },
                    onBack = {},
                )
            }
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("New collection"))
        composeRule.onNodeWithText("New collection").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Pending collection")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil { attempts == 1 }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Cancel").assertIsEnabled()
        composeRule.onNodeWithText("Create").assertIsEnabled()
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil { attempts == 2 }
        composeRule.runOnIdle { assertEquals("Pending collection", retriedName) }
    }
}
