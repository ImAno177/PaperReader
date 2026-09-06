package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.PaperManifestation
import java.time.Instant
import org.jsoup.nodes.Document

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
