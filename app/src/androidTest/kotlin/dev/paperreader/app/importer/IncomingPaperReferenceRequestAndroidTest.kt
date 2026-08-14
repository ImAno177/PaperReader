package dev.paperreader.app.importer

import android.content.ClipData
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.logic.domain.IdentifierType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingPaperReferenceRequestAndroidTest {
    @Test
    fun plainTextShareReturnsAnExactReferenceQuery() {
        val reference = Intent(Intent.ACTION_SEND)
            .setType("text/plain; charset=utf-8")
            .putExtra(Intent.EXTRA_TEXT, "Read this\nhttps://arxiv.org/abs/2501.04510v2")
            .incomingPaperReferencePayloadOrNull()
            .let { it as IncomingPaperReferencePayload.Valid }
            .reference

        assertEquals(IdentifierType.ARXIV, reference?.identifier?.type)
        assertEquals("2501.04510", reference?.identifier?.value)
        assertEquals("2501.04510v2", reference?.query)
    }

    @Test
    fun clipTextIsAConstrainedFallback() {
        val reference = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            clipData = ClipData.newPlainText("DOI", "https://doi.org/10.1000/XYZ+ABC")
        }.incomingPaperReferencePayloadOrNull()
            .let { it as IncomingPaperReferencePayload.Valid }
            .reference

        assertEquals(IdentifierType.DOI, reference?.identifier?.type)
        assertEquals("10.1000/xyz+abc", reference?.query)
    }

    @Test
    fun arxivViewLinkReturnsAnExactReferenceQuery() {
        val reference = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://arxiv.org/html/1706.03762v7"),
        ).incomingPaperReferencePayloadOrNull()
            .let { it as IncomingPaperReferencePayload.Valid }
            .reference

        assertEquals(IdentifierType.ARXIV, reference?.identifier?.type)
        assertEquals("1706.03762", reference?.identifier?.value)
        assertEquals("1706.03762v7", reference?.query)
    }

    @Test
    fun unrelatedOrAmbiguousIntentsAreIgnored() {
        assertNull(
            Intent(Intent.ACTION_VIEW)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://arxiv.org/abs/2501.04510")
                .incomingPaperReferencePayloadOrNull(),
        )
        assertNull(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/paper"))
                .incomingPaperReferencePayloadOrNull(),
        )
        assertNull(
            Intent(Intent.ACTION_SEND)
                .setType("text/html")
                .putExtra(Intent.EXTRA_TEXT, "https://arxiv.org/abs/2501.04510")
                .incomingPaperReferencePayloadOrNull(),
        )
        assertEquals(
            IncomingPaperReferencePayload.Invalid,
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Just an interesting paper")
                .incomingPaperReferencePayloadOrNull(),
        )
        assertEquals(
            IncomingPaperReferencePayload.Invalid,
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(
                    Intent.EXTRA_TEXT,
                    "https://arxiv.org/abs/2501.04510 https://doi.org/10.1000/another",
                )
                .incomingPaperReferencePayloadOrNull(),
        )
    }
}
