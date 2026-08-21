package dev.paperreader.app.importer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.domain.localPdfSourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingPdfRequestAndroidTest {
    private val uri = Uri.parse("content://fixture/paper.pdf")

    @Test
    fun validPdfIntentsResolveTheSameContentUri() {
        assertEquals(
            uri,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").incomingPdfUriOrNull(),
        )
        val stream = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, stream.incomingPdfUriOrNull())

        val clip = Intent(Intent.ACTION_SEND).setType("application/pdf").apply {
            clipData = ClipData.newRawUri("PDF", uri)
        }
        assertEquals(uri, clip.incomingPdfUriOrNull())
    }

    @Test
    fun unrelatedIntentIsIgnored() {
        assertNull(Intent(Intent.ACTION_MAIN, uri).setType("application/pdf").incomingPdfUriOrNull())
        assertNull(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").incomingPdfUriOrNull())
        assertNull(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("file:///sdcard/paper.pdf"), "application/pdf")
                .incomingPdfUriOrNull(),
        )
        assertNull(Intent(Intent.ACTION_VIEW, uri).incomingPdfUriOrNull())
    }

    @Test
    fun requestCarriesAStableOneWaySourceKeyForProcessRedelivery() {
        assertEquals(localPdfSourceKey(uri.toString()), IncomingPdfRequest(7, uri).sourceKey)
        assertEquals(IncomingPdfRequest(7, uri).sourceKey, IncomingPdfRequest(99, uri).sourceKey)
    }
}
