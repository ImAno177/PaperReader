package dev.paperreader.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryTabDoesNotRestoreTheTabItJustPopped() {
        waitForScreenTitle("Library")

        composeRule.onNodeWithText("More").performClick()
        waitForScreenTitle("More")

        composeRule.onNodeWithText("Library").performClick()
        waitForScreenTitle("Library")
    }

    @Test
    fun everyMoreBranchHidesPrimaryNavigationAndReturnsToTheHub() {
        waitForScreenTitle("Library")
        composeRule.onNodeWithText("More").performClick()
        waitForScreenTitle("More")
        waitForPrimaryNavigation(visible = true)
        composeRule.onNode(hasText("More") and hasClickAction()).assertIsSelected()

        listOf(
            "Appearance",
            "Collections",
            "Reading & imports",
            "Updates & notifications",
            "Data & backup",
            "Sources",
        ).forEach { branch ->
            composeRule.onNodeWithText(branch).performClick()
            composeRule.onNodeWithText(branch).assertIsDisplayed()
            waitForPrimaryNavigation(visible = false)
            composeRule.onNodeWithContentDescription("Back").performClick()
            waitForScreenTitle("More")
            waitForPrimaryNavigation(visible = true)
            composeRule.onNode(hasText("More") and hasClickAction()).assertIsSelected()
        }
    }

    private fun waitForScreenTitle(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            // One node is the navigation label and the other is the screen title.
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size >= 2
        }
    }

    private fun waitForPrimaryNavigation(visible: Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(PRIMARY_NAVIGATION_TEST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty() == visible
        }
    }
}
