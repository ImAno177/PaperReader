package dev.paperreader.app.importer

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import dev.paperreader.app.PaperReaderApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIncomingReferenceUiAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun invalidTextShareReusesActivityAndIsNeutralizedAfterFeedback() {
        val application = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val activityIdentity = System.identityHashCode(composeRule.activity)

        application.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClassName(application.packageName, MainActivity::class.java.name)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "not-a-paper-reference")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.intent.action == Intent.ACTION_MAIN
        }
        composeRule.runOnIdle {
            assertEquals(activityIdentity, System.identityHashCode(composeRule.activity))
            assertEquals(Intent.ACTION_MAIN, composeRule.activity.intent.action)
        }
    }
}
