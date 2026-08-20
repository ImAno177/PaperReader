package dev.paperreader.app.ui

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun everyPrimaryDestinationRemainsReachableAcrossRepeatedTabPermutations() {
        waitForScreenTitle("Library")

        val destinations = listOf(
            "library" to "Library",
            "discover" to "Discover",
            "updates" to "Updates",
            "history" to "History",
            "more" to "More",
        )

        destinations.forEach { outerDestination ->
            navigateToPrimaryDestination(outerDestination)
            destinations.forEach(::navigateToPrimaryDestination)
        }
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

    @Test
    fun bottomNavigationKeepsTheVisualTileInsideItsLargerTouchTarget() {
        waitForScreenTitle("Library")

        val hitBounds = composeRule
            .onAllNodesWithTag("${PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX}library", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot
        val visualBounds = composeRule
            .onAllNodesWithTag("${PRIMARY_NAVIGATION_ITEM_VISUAL_TEST_TAG_PREFIX}library", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot

        assertTrue(visualBounds.left > hitBounds.left)
        assertTrue(visualBounds.right < hitBounds.right)
        assertTrue(hitBounds.width > visualBounds.width)
        assertTrue(hitBounds.height > visualBounds.height)
    }

    @Test
    fun bottomNavigationVisualTilesHaveEqualGeometry() {
        waitForScreenTitle("Library")

        val routes = listOf("library", "discover", "updates", "history", "more")
        val visualBounds = routes.map { route ->
            composeRule
                .onAllNodesWithTag("$PRIMARY_NAVIGATION_ITEM_VISUAL_TEST_TAG_PREFIX$route", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single()
                .boundsInRoot
        }

        val first = visualBounds.first()
        visualBounds.drop(1).forEach { bounds ->
            assertEquals(first.width, bounds.width, 1f)
            assertEquals(first.height, bounds.height, 1f)
            assertEquals(first.top, bounds.top, 1f)
            assertEquals(first.bottom, bounds.bottom, 1f)
        }

        val density = composeRule.activity.resources.displayMetrics.density
        routes.map { route ->
            composeRule
                .onAllNodesWithTag("$PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX$route", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single()
                .boundsInRoot
        }.forEach { bounds ->
            assertTrue("Navigation hit width must be at least 48dp", bounds.width / density >= 48f)
            assertTrue("Navigation hit height must be at least 48dp", bounds.height / density >= 48f)
        }
    }

    @Test
    fun navigationRailStaysNarrowOnWideLayouts() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(PRIMARY_NAVIGATION_TEST_TAG)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.boundsInRoot
                ?.let { bounds -> bounds.height > bounds.width } == true
        }

        val railBounds = composeRule.onAllNodesWithTag(PRIMARY_NAVIGATION_TEST_TAG)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot
        val itemBounds = composeRule
            .onAllNodesWithTag("${PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX}library", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot

        assertTrue(railBounds.height > railBounds.width)
        assertTrue(itemBounds.width <= railBounds.width)
        assertTrue(itemBounds.width < railBounds.height)
        listOf("library", "discover", "updates", "history", "more").forEach { route ->
            val item = composeRule
                .onNodeWithTag("$PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX$route")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("$route starts outside the rail", item.top >= railBounds.top)
            assertTrue("$route ends outside the rail", item.bottom <= railBounds.bottom)
        }
    }

    private fun waitForScreenTitle(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            // One node is the navigation label and the other is the screen title.
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size >= 2
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun navigateToPrimaryDestination(destination: Pair<String, String>) {
        val (route, title) = destination
        composeRule
            .onNodeWithTag("$PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX$route")
            .performClick()
        if (route == "discover") {
            waitForText(title)
        } else {
            waitForScreenTitle(title)
        }
        composeRule
            .onNodeWithTag("$PRIMARY_NAVIGATION_ITEM_HIT_TEST_TAG_PREFIX$route")
            .assertIsSelected()
    }

    private fun waitForPrimaryNavigation(visible: Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(PRIMARY_NAVIGATION_TEST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty() == visible
        }
    }
}
