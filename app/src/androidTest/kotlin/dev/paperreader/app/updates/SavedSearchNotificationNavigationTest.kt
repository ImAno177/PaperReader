package dev.paperreader.app.updates

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.extensions.ExtensionNotificationPublisher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedSearchNotificationNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun notificationActionReusesMainActivityAndOpensUpdates() {
        val app = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val activityIdentity = System.identityHashCode(composeRule.activity)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty()
        }

        app.startActivity(
            Intent(app, MainActivity::class.java)
                .setAction(SavedSearchNotificationPublisher.ACTION_OPEN_UPDATES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Saved searches").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Saved searches").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(activityIdentity, System.identityHashCode(composeRule.activity))
            assertEquals(Intent.ACTION_MAIN, composeRule.activity.intent.action)
        }
    }

    @Test
    fun extensionNotificationReusesMainActivityAndOpensSources() {
        val app = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val activityIdentity = System.identityHashCode(composeRule.activity)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty()
        }

        app.startActivity(
            Intent(app, MainActivity::class.java)
                .setAction(ExtensionNotificationPublisher.ACTION_OPEN_EXTENSIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Sources").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Sources").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(activityIdentity, System.identityHashCode(composeRule.activity))
            assertEquals(Intent.ACTION_MAIN, composeRule.activity.intent.action)
        }
    }
}
