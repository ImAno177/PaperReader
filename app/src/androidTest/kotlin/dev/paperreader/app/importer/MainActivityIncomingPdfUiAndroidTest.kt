package dev.paperreader.app.importer

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import dev.paperreader.app.PaperReaderApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIncomingPdfUiAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shareAfterCompletedImportReplacesStatusAndSurfacesReview() {
        val application = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val activityIdentity = System.identityHashCode(composeRule.activity)

        application.startActivity(shareIntent(application.packageName, fixtureUri("completed-first")))
        waitForText("completed-first.pdf")
        composeRule.onNodeWithText("Review this local PDF").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(composeRule.activity.intent.incomingPdfUriOrNull())
        }

        composeRule.onNode(hasText("Import PDF") and hasClickAction()).performClick()
        waitForText("Imported")
        composeRule.onNodeWithText("Open imported paper").performClick()
        waitForText("Paper detail")

        val secondUri = fixtureUri("after-complete")
        application.startActivity(shareIntent(application.packageName, secondUri))
        waitForText("after-complete.pdf")

        composeRule.onNodeWithText("Review this local PDF").assertIsDisplayed()
        composeRule.onNodeWithText("after-complete.pdf").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(activityIdentity, System.identityHashCode(composeRule.activity))
            assertNull(composeRule.activity.intent.incomingPdfUriOrNull())
        }
        composeRule.onNodeWithText("Cancel").performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shareIntent(packageName: String, uri: Uri): Intent = Intent(Intent.ACTION_SEND)
        .setClassName(packageName, MainActivity::class.java.name)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun fixtureUri(name: String): Uri =
        Uri.parse("content://dev.paperreader.app.test.pdf-fixture/$name")
}
