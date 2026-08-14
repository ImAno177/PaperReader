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
    val sharedText = when (action) {
        Intent.ACTION_VIEW -> data?.toString()
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: return@runCatching null

        Intent.ACTION_SEND -> {
            if (!mediaType.equals(TEXT_MIME_TYPE, ignoreCase = true)) return@runCatching null
            getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                ?: return@runCatching IncomingPaperReferencePayload.Invalid
        }

        else -> return@runCatching null
    }
    val reference = PaperReferenceParser.parseSharedText(sharedText.toString())
    when {
        reference != null -> IncomingPaperReferencePayload.Valid(reference)
        action == Intent.ACTION_SEND -> IncomingPaperReferencePayload.Invalid
        else -> null
    }
}.getOrNull()

private const val TEXT_MIME_TYPE = "text/plain"
