package dev.paperreader.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperReaderLogicAndroidTest {
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun openBuildsTheApplicationFacadeAndClosesItsDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "paper-reader-test-${UUID.randomUUID()}.db"
        databaseNames += databaseName

        val logic = PaperReaderLogic.open(
            context = context,
            builtInProviders = emptyList(),
            configuration = PaperReaderConfiguration(databaseName = databaseName),
        )

        assertTrue(logic.providers.state.value.installed.isEmpty())
        logic.setDisabledProviderIds(setOf("missing"))
        assertTrue("missing" in logic.providers.state.value.disabledProviderIds)
        logic.close()
    }
}
