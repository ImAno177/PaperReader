package dev.paperreader.app.importer

import android.content.Intent
import dev.paperreader.logic.domain.identity.PaperReferenceParser
import dev.paperreader.logic.domain.identity.PaperReferenceQuery

data class IncomingPaperReferenceRequest(
    val id: Long,
    val payload: IncomingPaperReferencePayload,
)

sealed interface IncomingPaperReferencePayload {
    data class Valid(val reference: PaperReferenceQuery) : IncomingPaperReferencePayload
    data object Invalid : IncomingPaperReferencePayload
}

internal fun Intent.incomingPaperReferencePayloadOrNull(): IncomingPaperReferencePayload? = runCatching {
    val mediaType = type?.substringBefore(';')?.trim()
    if (action != Intent.ACTION_SEND || !mediaType.equals(TEXT_MIME_TYPE, ignoreCase = true)) {
        return@runCatching null
    }
    val sharedText = getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        ?: return@runCatching IncomingPaperReferencePayload.Invalid
    PaperReferenceParser.parseSharedText(sharedText.toString())
        ?.let(IncomingPaperReferencePayload::Valid)
        ?: IncomingPaperReferencePayload.Invalid
}.getOrNull()

private const val TEXT_MIME_TYPE = "text/plain"
