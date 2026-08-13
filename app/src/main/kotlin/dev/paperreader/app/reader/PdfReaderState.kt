package dev.paperreader.app.reader

import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.ReadingLocator
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import androidx.lifecycle.ViewModel
import java.time.Duration
import java.time.Instant

internal class ReaderSessionViewModel : ViewModel() {
    private var accumulatedMillis = 0L
    private var startedAtMillis: Long? = null

    fun resume(nowMillis: Long) {
        if (startedAtMillis == null) startedAtMillis = nowMillis
    }

    fun pause(nowMillis: Long) {
        val started = startedAtMillis ?: return
        accumulatedMillis += (nowMillis - started).coerceAtLeast(0L)
        startedAtMillis = null
    }

    fun drain(minimumMillis: Long): Duration? {
        require(minimumMillis >= 0L)
        val elapsedMillis = accumulatedMillis
        accumulatedMillis = 0L
        return Duration.ofMillis(elapsedMillis).takeIf { elapsedMillis >= minimumMillis }
    }
}

internal data class ReaderPosition(
    val pageIndex: Int,
    val progression: Double,
)

internal data class ReaderPageLocation(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun calculateReaderPosition(firstVisiblePage: Int, pageCount: Int): ReaderPosition? {
    if (pageCount <= 0) return null
    val pageIndex = firstVisiblePage.coerceIn(0, pageCount - 1)
    return ReaderPosition(
        pageIndex = pageIndex,
        progression = (pageIndex + 1).toDouble() / pageCount,
    )
}

internal fun calculateDominantReaderPosition(
    firstVisiblePage: Int,
    pageCount: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    pageLocations: List<ReaderPageLocation>,
): ReaderPosition? {
    val fallback = calculateReaderPosition(firstVisiblePage, pageCount) ?: return null
    if (viewportWidth <= 0 || viewportHeight <= 0) return fallback

    val dominantPage = pageLocations
        .asSequence()
        .filter { location ->
            location.pageIndex in 0 until pageCount &&
                location.left.isFinite() && location.top.isFinite() &&
                location.right.isFinite() && location.bottom.isFinite()
        }
        .mapNotNull { location ->
            val visibleWidth = (
                minOf(location.right, viewportWidth.toFloat()) - maxOf(location.left, 0f)
                ).coerceAtLeast(0f)
            val visibleHeight = (
                minOf(location.bottom, viewportHeight.toFloat()) - maxOf(location.top, 0f)
                ).coerceAtLeast(0f)
            val visibleArea = visibleWidth * visibleHeight
            if (visibleArea <= 0f || !visibleArea.isFinite()) return@mapNotNull null
            val centerDistance = kotlin.math.abs(
                (location.top + location.bottom) / 2f - viewportHeight / 2f,
            )
            Triple(location.pageIndex, visibleArea, centerDistance)
        }
        .maxWithOrNull(
            compareBy<Triple<Int, Float, Float>> { it.second }
                .thenBy { -it.third }
                .thenBy { -it.first },
        )
        ?.first
        ?: fallback.pageIndex

    return calculateReaderPosition(dominantPage, pageCount)
}

internal fun selectInitialReaderPosition(
    observedPosition: ReaderPosition?,
    firstVisiblePage: Int,
    pageCount: Int,
): ReaderPosition? = observedPosition ?: calculateReaderPosition(firstVisiblePage, pageCount)

internal fun readerPageIndexFromInput(input: String, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val pageNumber = input.trim().toIntOrNull() ?: return null
    return pageNumber.takeIf { it in 1..pageCount }?.minus(1)
}

internal fun isReaderPageBookmarked(bookmarks: List<ReadingBookmark>, pageIndex: Int): Boolean =
    pageIndex >= 0 && bookmarks.any { it.pageIndex == pageIndex }

internal fun boundedReaderBookmarks(
    bookmarks: List<ReadingBookmark>,
    pageCount: Int,
): List<ReadingBookmark> = if (pageCount <= 0) {
    emptyList()
} else {
    bookmarks.filter { it.pageIndex in 0 until pageCount }.sortedBy(ReadingBookmark::pageIndex)
}

internal fun isTrustedLocalPdfLocation(
    scheme: String?,
    authority: String?,
    expectedAuthority: String,
): Boolean = scheme == "content" && authority == expectedAuthority

internal fun readerStateForOpen(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    now: Instant,
): ReadingState {
    requireSha256(documentSha256)
    val sameDocument = existing.matches(manifestationId, documentSha256)
    return ReadingState(
        workId = workId,
        manifestationId = manifestationId,
        locator = if (sameDocument) existing!!.locator else ReadingLocator(documentSha256 = documentSha256),
        status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
        updatedAt = now,
    )
}

internal fun readerStateForPosition(
    existing: ReadingState?,
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    position: ReaderPosition,
    now: Instant,
): ReadingState {
    requireSha256(documentSha256)
    return ReadingState(
        workId = workId,
        manifestationId = manifestationId,
        locator = ReadingLocator(
            documentSha256 = documentSha256,
            pageIndex = position.pageIndex,
            progression = position.progression,
        ),
        status = if (existing?.status == ReadingStatus.FINISHED) ReadingStatus.FINISHED else ReadingStatus.READING,
        updatedAt = now,
    )
}

internal fun restorableReaderPage(
    state: ReadingState?,
    manifestationId: ManifestationId,
    documentSha256: String,
    pageCount: Int,
): Int? {
    if (pageCount <= 0 || !state.matches(manifestationId, documentSha256)) return null
    return state?.locator?.pageIndex?.takeIf { it in 0 until pageCount }
}

private fun ReadingState?.matches(manifestationId: ManifestationId, documentSha256: String): Boolean =
    this?.manifestationId == manifestationId &&
        locator.documentSha256?.equals(documentSha256, ignoreCase = true) == true

private fun requireSha256(value: String) {
    require(value.matches(Regex("[0-9a-fA-F]{64}"))) { "Document SHA-256 must be 64 hexadecimal characters" }
}
