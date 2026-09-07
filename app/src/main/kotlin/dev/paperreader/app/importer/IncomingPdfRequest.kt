package dev.paperreader.app.importer

import android.content.Intent
import android.net.Uri
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
        Intent.ACTION_SEND -> sendStreamUriOrNull()
        else -> null
    }
    uri?.takeIf { candidate ->
        candidate.scheme.equals("content", ignoreCase = true) && !candidate.authority.isNullOrBlank()
    }
}.getOrNull()

private fun Intent.sendStreamUriOrNull(): Uri? {
    val stream = runCatching { extras?.get(Intent.EXTRA_STREAM) }.getOrNull()
    val singleStream = stream as? Uri
    val firstMultipleStream = (stream as? ArrayList<*>)?.firstOrNull { it is Uri } as? Uri
    return singleStream
        ?: firstMultipleStream
        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
}

private const val PDF_MIME_TYPE = "application/pdf"
