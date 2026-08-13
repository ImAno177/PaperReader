package dev.paperreader.app.importer

import android.content.Intent
import android.net.Uri
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

    private fun shareIntent(packageName: String, uri: Uri): Intent = Intent(Intent.ACTION_SEND)
        .setClassName(packageName, MainActivity::class.java.name)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun fixtureUri(name: String): Uri =
        Uri.parse("content://dev.paperreader.app.test.pdf-fixture/$name")
}
