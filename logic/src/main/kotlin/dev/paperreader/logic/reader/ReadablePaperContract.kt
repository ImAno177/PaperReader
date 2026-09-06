package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.PaperManifestation
import java.io.InputStream
import java.time.Instant
import org.jsoup.nodes.Document

/** Version of the arXiv HTML sanitization contract embedded in cached/exported documents. */
const val ARXIV_READABLE_SANITIZER_POLICY_VERSION = "arxiv-html-sanitizer-15"

data class ReadablePaperDocument(
    /** A sanitized fragment. Presentation and the CSP remain owned by the UI renderer. */
    val bodyHtml: String,
    val title: String,
    val sourceUrl: String,
    val sourceProvider: String,
    val sourceVersion: String,
    val license: String?,
    val sourceSha256: String,
    val documentSha256: String,
    val retrievedAt: Instant,
    val servedFromCache: Boolean,
    /** True only when the verified app-private artifact is protected from ordinary cache eviction. */
    val keptForOffline: Boolean = false,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
    /** Opaque metadata for locally cached figure assets; no filesystem path is exposed. */
    val assetGroupKey: String? = null,
    val assets: List<ReadablePaperAsset> = emptyList(),
)

data class ReadablePaperAsset(
    val id: String,
    val mediaType: String,
    val sha256: String,
    val byteLength: Long,
) {
    init {
        require(id.matches(READABLE_ASSET_ID))
        require(mediaType in SAFE_READABLE_ASSET_MEDIA_TYPES)
        require(sha256.matches(READABLE_SHA256))
        require(byteLength in 1..MAXIMUM_READABLE_ASSET_BYTES)
    }
}

/** A verified app-private asset stream used by the local WebView/export boundary. */
data class ReadablePaperAssetContent(
    val mediaType: String,
    val inputStream: InputStream,
)

data class ReadablePaperSection(
    val anchor: String,
    val title: String,
    val level: Int,
) {
    init {
        require(anchor.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
        require(title.isNotBlank())
        require(level in 1..3)
    }
}

enum class ReadablePaperWarning {
    TABLE_OF_CONTENTS_MISSING,
    FIGURE_UNAVAILABLE,
    FIGURE_LIMIT_REACHED,
    SOURCE_CONVERSION_ARTIFACT_NORMALIZED,
}

enum class ReadablePaperFailure {
    PAPER_NOT_FOUND,
    MANIFESTATION_NOT_FOUND,
    UNSUPPORTED_SOURCE,
    UNVERSIONED_SOURCE,
    SOURCE_NOT_FOUND,
    RATE_LIMITED,
    OFFLINE_OR_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
}

sealed interface ReadablePaperResult {
    data class Ready(val document: ReadablePaperDocument) : ReadablePaperResult

    data class Unavailable(
        val reason: ReadablePaperFailure,
        val retryAfterMillis: Long? = null,
    ) : ReadablePaperResult
}

internal data class ReadableRemoteResource(
    val bytes: ByteArray,
    val mediaType: String,
)

internal data class ReadablePaperAssetReference(
    val id: String,
    val sourceUrl: String,
) {
    init {
        require(id.matches(READABLE_ASSET_ID))
        require(sourceUrl.isNotBlank())
    }
}

internal sealed interface ReadableRemoteResult {
    data class Success(val resource: ReadableRemoteResource) : ReadableRemoteResult
    data object NotFound : ReadableRemoteResult
    data class RateLimited(val retryAfterMillis: Long?) : ReadableRemoteResult
    data object Unavailable : ReadableRemoteResult
    data object TooLarge : ReadableRemoteResult
    data object Invalid : ReadableRemoteResult
}

internal fun interface ReadableResourceFetcher {
    suspend fun fetch(request: ReadableResourceRequest): ReadableRemoteResult
}

internal enum class ReadableResourceKind {
    DOCUMENT,
    ASSET,
}

internal data class ReadableResourceRequest(
    val url: String,
    val accept: String,
    val maximumBytes: Long,
    val kind: ReadableResourceKind = ReadableResourceKind.DOCUMENT,
)

internal fun interface ReadablePaperLoader {
    suspend fun load(
        title: String,
        manifestation: PaperManifestation,
        retainDocumentSha256: String?,
    ): ReadablePaperResult

    /** Retains a document that this loader has already verified without loading it again. */
    suspend fun retain(document: ReadablePaperDocument): Boolean = false
}

internal val SAFE_READABLE_ASSET_MEDIA_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/svg+xml",
)

internal const val MAXIMUM_READABLE_ASSET_BYTES = 8L * 1024L * 1024L

internal val READABLE_ASSET_ID = Regex("[0-9a-f]{64}")
internal val READABLE_SHA256 = Regex("[0-9a-f]{64}")
