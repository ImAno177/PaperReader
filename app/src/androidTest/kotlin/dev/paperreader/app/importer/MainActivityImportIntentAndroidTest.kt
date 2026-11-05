package dev.paperreader.app.importer

import dev.paperreader.app.importer.PdfFixtureContentProvider.fixtureUri
import dev.paperreader.app.importer.PdfFixtureContentProvider.shareIntent

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.paperreader.app.MainActivity
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityImportIntentAndroidTest {
    @Test
    fun providerRuntimeFailureIsReturnedAsSourceUnavailable() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PaperReaderApplication>()

        val result = application.logic.useCases.prepareLocalPdf.await(
            fixtureUri("runtime-open").toString(),
        )

        assertEquals(
            LocalPdfImportFailure.SOURCE_UNAVAILABLE,
            (result as PrepareLocalPdfResult.Rejected).reason,
        )
    }

    @Test
    fun secondShareReusesMainActivityAndKeepsFirstDurableSessionImportable() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PaperReaderApplication>()
        val firstUri = fixtureUri("first-share")
        val secondUri = fixtureUri("second-share")
        val prepared = application.logic.useCases.prepareLocalPdf.await(firstUri.toString())
            as PrepareLocalPdfResult.Ready
        val firstIntent = shareIntent(application.packageName, firstUri)

        ActivityScenario.launch<MainActivity>(firstIntent).use { scenario ->
            var firstActivityIdentity = 0
            scenario.onActivity { activity -> firstActivityIdentity = System.identityHashCode(activity) }

            application.startActivity(shareIntent(application.packageName, secondUri))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertEquals(firstActivityIdentity, System.identityHashCode(activity))
            }
            val recovered = checkNotNull(application.logic.useCases.recoverPendingLocalPdf.await())
            assertEquals(prepared.candidate.importToken, recovered.importToken)
            val imported = application.logic.useCases.importLocalPdf.await(
                recovered.importToken,
                "First shared paper",
            )
            assertTrue(imported is LocalPdfImportResult.Imported)
        }
    }

}
