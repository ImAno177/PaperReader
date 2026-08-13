package dev.paperreader.logic.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

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

data class AnnotationSelection(
    val documentSha256: String,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val quotePrefix: String,
    val quoteExact: String,
    val quoteSuffix: String,
    val pageIndex: Int? = null,
) {
    init {
        require(documentSha256 == canonicalDocumentSha256(documentSha256))
        require(blockId.matches(READABLE_BLOCK_ID)) { "Invalid readable block ID" }
        require(startOffset >= 0 && endOffset > startOffset)
        require(quoteExact.isNotBlank() && quoteExact.length <= MAX_ANNOTATION_QUOTE_LENGTH)
        require(endOffset - startOffset == quoteExact.length) { "Selection offsets must match the quote" }
        require(quotePrefix.length <= MAX_ANNOTATION_CONTEXT_LENGTH)
        require(quoteSuffix.length <= MAX_ANNOTATION_CONTEXT_LENGTH)
        require(pageIndex == null || pageIndex >= 0)
    }
}

fun annotationId(workId: WorkId, selection: AnnotationSelection): String {
    val identity = listOf(
        workId.value,
        selection.documentSha256,
        selection.blockId,
        selection.startOffset.toString(),
        selection.endOffset.toString(),
        selection.quoteExact,
    ).joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return "ann-" + digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}

const val MAX_ANNOTATION_NOTE_LENGTH = 4_000
const val MAX_ANNOTATION_QUOTE_LENGTH = 2_000
const val MAX_ANNOTATION_CONTEXT_LENGTH = 64
private val READABLE_BLOCK_ID = Regex("[A-Za-z0-9._:-]{1,160}")
