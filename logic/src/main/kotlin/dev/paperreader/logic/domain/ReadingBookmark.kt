package dev.paperreader.logic.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

@JvmInline
value class ReadingBookmarkId(val value: String) {
    init {
        require(value.matches(HEX_SHA256)) { "Bookmark ID must be a SHA-256 value" }
    }
}

data class ReadingBookmark(
    val id: ReadingBookmarkId,
    val workId: WorkId,
    val manifestationId: ManifestationId,
    val documentSha256: String,
    val pageIndex: Int,
    val createdAt: Instant,
) {
    init {
        require(documentSha256.matches(HEX_SHA256)) { "Invalid document SHA-256" }
        require(documentSha256 == documentSha256.lowercase(Locale.ROOT)) {
            "Document SHA-256 must be canonical lowercase"
        }
        require(pageIndex >= 0) { "Bookmark page index is zero-based" }
    }
}

fun canonicalDocumentSha256(value: String): String = value.lowercase(Locale.ROOT).also { canonical ->
    require(canonical.matches(HEX_SHA256)) { "Invalid document SHA-256" }
}

fun readingBookmarkId(
    workId: WorkId,
    manifestationId: ManifestationId,
    documentSha256: String,
    pageIndex: Int,
): ReadingBookmarkId {
    require(pageIndex >= 0) { "Bookmark page index is zero-based" }
    val canonicalSha = canonicalDocumentSha256(documentSha256)
    val identity = listOf(workId.value, manifestationId.value, canonicalSha, pageIndex.toString())
        .joinToString(IDENTITY_SEPARATOR)
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return ReadingBookmarkId(digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) })
}

private const val IDENTITY_SEPARATOR = "\u001f"
private val HEX_SHA256 = Regex("[0-9a-f]{64}")
