package dev.paperreader.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.theme.PaperThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearancePreferencesRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun themeModeAndLibraryLayoutPersistAcrossPreferenceInstances() = runBlocking {
        val preferences = PaperReaderPreferences(context)
        val originalMode = preferences.themeMode.first()
        val originalLayout = preferences.libraryLayout.first()

        try {
            preferences.setThemeMode(PaperThemeMode.DARK)
            preferences.setLibraryLayout(LibraryLayout.GRID)

            val reopened = PaperReaderPreferences(context)
            assertEquals(PaperThemeMode.DARK, reopened.themeMode.first())
            assertEquals(LibraryLayout.GRID, reopened.libraryLayout.first())
        } finally {
            preferences.setThemeMode(originalMode)
            preferences.setLibraryLayout(originalLayout)
        }
    }
}
