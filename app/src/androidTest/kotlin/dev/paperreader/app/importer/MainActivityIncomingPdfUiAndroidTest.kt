package dev.paperreader.app.importer

import dev.paperreader.app.importer.PdfFixtureContentProvider.fixtureUri
import dev.paperreader.app.importer.PdfFixtureContentProvider.shareIntent

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
        composeRule.onNodeWithText("Review PDF").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(composeRule.activity.intent.incomingPdfUriOrNull())
        }

        composeRule.onNode(hasText("Import PDF") and hasClickAction()).performClick()
        waitForText("Imported")
        composeRule.onNodeWithText("Open paper").performClick()
        waitForText("Paper details")

        val secondUri = fixtureUri("after-complete")
        application.startActivity(shareIntent(application.packageName, secondUri))
        waitForText("after-complete.pdf")

        composeRule.onNodeWithText("Review PDF").assertIsDisplayed()
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

}
