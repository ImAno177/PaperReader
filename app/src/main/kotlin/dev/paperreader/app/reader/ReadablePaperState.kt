package dev.paperreader.app.reader

import android.os.Bundle
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import java.time.Instant

internal data class RestoredReadableInstanceState(
    val progression: Double?,
    val documentSha256: String?,
    val citationReturnProgression: Double?,
)

internal fun restoreReadableInstanceState(
    savedState: Bundle?,
    manifestationId: ManifestationId,
): RestoredReadableInstanceState {
    val exactManifestation = savedState
        ?.takeIf { it.getString(STATE_MANIFESTATION_ID) == manifestationId.value }
    return RestoredReadableInstanceState(
        progression = exactManifestation?.getDouble(STATE_PROGRESSION)?.coerceIn(0.0, 1.0),
        documentSha256 = exactManifestation?.getString(STATE_DOCUMENT_SHA256),
        citationReturnProgression = exactManifestation
            ?.takeIf { it.containsKey(STATE_CITATION_RETURN_PROGRESSION) }
            ?.getDouble(STATE_CITATION_RETURN_PROGRESSION)
            ?.coerceIn(0.0, 1.0),
    )
}

internal fun Bundle.saveReadableInstanceState(
    manifestationId: ManifestationId,
    documentSha256: String,
    progression: Double,
    citationReturnProgression: Double?,
) {
    putString(STATE_MANIFESTATION_ID, manifestationId.value)
    putString(STATE_DOCUMENT_SHA256, documentSha256)
    putDouble(STATE_PROGRESSION, progression.coerceIn(0.0, 1.0))
    citationReturnProgression?.let { putDouble(STATE_CITATION_RETURN_PROGRESSION, it.coerceIn(0.0, 1.0)) }
}

internal fun readableStateForOpen(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    now: Instant,
): ReadingState = ReadingState(
    workId = workId,
    manifestationId = manifestationId,
    locator = if (existing.matchesReadable(manifestationId, documentSha256)) {
        existing!!.locator.copy(pageIndex = null)
    } else {
        ReadingLocator(documentSha256 = documentSha256)
    },
    status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
    updatedAt = now,
)

internal fun readableStateForProgress(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    progression: Double,
    now: Instant,
): ReadingState = ReadingState(
    workId = workId,
    manifestationId = manifestationId,
    locator = ReadingLocator(
        documentSha256 = documentSha256,
        progression = progression.coerceIn(0.0, 1.0),
    ),
    status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
    updatedAt = now,
)

internal fun restorableReadableProgress(
    state: ReadingState?,
    manifestationId: ManifestationId,
    documentSha256: String,
): Double? = state
    ?.takeIf { it.matchesReadable(manifestationId, documentSha256) }
    ?.locator
    ?.progression

private fun ReadingState?.matchesReadable(
    manifestationId: ManifestationId,
    documentSha256: String,
): Boolean = this?.manifestationId == manifestationId &&
    locator.documentSha256?.equals(documentSha256, ignoreCase = true) == true

private const val STATE_MANIFESTATION_ID = "readable_manifestation_id"
private const val STATE_DOCUMENT_SHA256 = "readable_document_sha256"
private const val STATE_PROGRESSION = "readable_progression"
private const val STATE_CITATION_RETURN_PROGRESSION = "readable_citation_return_progression"
