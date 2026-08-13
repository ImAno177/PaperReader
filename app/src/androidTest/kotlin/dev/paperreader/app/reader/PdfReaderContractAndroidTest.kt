package dev.paperreader.app.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReaderContractAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun readerIntentCarriesOnlyOwnedContentUriAndConcreteDocumentIdentity() {
        val contentUri = "content://dev.paperreader.app.files/downloaded_papers/work/document.pdf"
        val downloaded = DownloadedPaper(
            manifestationId = ManifestationId("manifestation"),
            contentUri = contentUri,
            sha256 = "a".repeat(64),
            byteLength = 42L,
        )

        val intent = PdfReaderActivity.createIntent(
            context,
            downloaded,
            WorkId("work"),
            "Paper title",
            PaperThemePreset.RETRO,
            themeMode = PaperThemeMode.DARK,
        )

        assertEquals(ComponentName(context, PdfReaderActivity::class.java), intent.component)
        assertEquals(contentUri, intent.dataString)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(contentUri, intent.clipData?.getItemAt(0)?.uri?.toString())
        assertEquals("retro", intent.getStringExtra(PdfReaderActivity.EXTRA_THEME_PRESET))
        assertEquals("dark", intent.getStringExtra(PdfReaderActivity.EXTRA_THEME_MODE))
    }

    @Test
    fun readableReaderIntentCarriesExplicitAppearanceMode() {
        val intent = ReadablePaperActivity.createIntent(
            context = context,
            workId = WorkId("work"),
            manifestationId = ManifestationId("manifestation"),
            title = "Paper title",
            themePreset = PaperThemePreset.DOODLE,
            themeMode = PaperThemeMode.LIGHT,
        )

        assertEquals(ComponentName(context, ReadablePaperActivity::class.java), intent.component)
        assertEquals("light", intent.getStringExtra(ReadablePaperActivity.EXTRA_THEME_MODE))
    }

    @Suppress("DEPRECATION")
    @Test
    fun readerActivityIsNotExported() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, PdfReaderActivity::class.java), 0)

        assertFalse(info.exported)
        assertTrue(info.themeResource != 0)
    }

    @Test
    fun pageIndicatorLayoutIsHiddenUntilLoadedAndHasAccessibleTapTarget() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_PaperReader_PdfReader)
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.activity_pdf_reader, null)
            .findViewById<TextView>(R.id.reader_page_indicator)

        assertEquals(View.GONE, view.visibility)
        assertTrue(view.isClickable)
        assertTrue(view.isFocusable)
        assertTrue(view.background != null)
        assertTrue(view.elevation > 0f)
        val minimumTouchTarget = (48 * context.resources.displayMetrics.density).toInt()
        assertTrue(view.minimumHeight >= minimumTouchTarget)
        assertTrue(view.minimumWidth >= minimumTouchTarget)
    }
}
