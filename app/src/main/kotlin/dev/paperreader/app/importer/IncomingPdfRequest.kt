package dev.paperreader.app.importer

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dev.paperreader.logic.domain.localPdfSourceKey

data class IncomingPdfRequest(
    val id: Long,
    val uri: Uri,
) {
    val sourceKey: String = localPdfSourceKey(uri.toString())
}

internal fun Intent.incomingPdfUriOrNull(): Uri? = runCatching {
    val mediaType = type?.substringBefore(';')?.trim()
    if (!mediaType.equals(PDF_MIME_TYPE, ignoreCase = true)) return@runCatching null
    val uri = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
            ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        else -> null
    }
    uri?.takeIf { candidate ->
        candidate.scheme.equals("content", ignoreCase = true) && !candidate.authority.isNullOrBlank()
    }
}.getOrNull()

private const val PDF_MIME_TYPE = "application/pdf"
