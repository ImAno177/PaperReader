package dev.paperreader.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishLocaleTest {
    @Test
    fun activityUsesEnglishResourcesAndPluralRules() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("en", activity.resources.configuration.locales[0].language)
                assertEquals(
                    "1 saved paper",
                    activity.resources.getQuantityString(R.plurals.saved_papers_count, 1, 1),
                )
            }
        }
    }
}
