package dev.paperreader.logic.domain

import java.time.Instant

enum class ReadingStatus {
    UNREAD,
    READING,
    FINISHED,
}

data class ReadingLocator(
    val documentSha256: String? = null,
    val blockId: String? = null,
    val characterOffset: Int = 0,
    val pageIndex: Int? = null,
    val progression: Double = 0.0,
) {
    init {
        require(characterOffset >= 0) { "Character offset cannot be negative" }
        require(pageIndex == null || pageIndex >= 0) { "Page index is zero-based" }
        require(progression in 0.0..1.0) { "Progression must be between 0 and 1" }
    }
}

data class ReadingState(
    val workId: WorkId,
    val manifestationId: ManifestationId? = null,
    val locator: ReadingLocator = ReadingLocator(),
    val status: ReadingStatus = ReadingStatus.UNREAD,
    val updatedAt: Instant,
)

data class Annotation(
    val id: String,
    val workId: WorkId,
    val documentSha256: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quotePrefix: String,
    val quoteExact: String,
    val quoteSuffix: String,
    val pageIndex: Int?,
    val note: String?,
    val color: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank())
        require(documentSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(blockId.isNotBlank())
        require(startOffset >= 0 && endOffset >= startOffset)
        require(quoteExact.isNotEmpty())
        require(pageIndex == null || pageIndex >= 0)
    }
}
