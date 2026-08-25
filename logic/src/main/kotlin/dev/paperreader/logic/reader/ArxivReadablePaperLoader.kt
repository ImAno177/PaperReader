package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.PaperManifestation
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

internal class ArxivReadablePaperLoader(
    private val fetcher: ReadableResourceFetcher,
    private val cache: ReadablePaperCache,
    private val sanitizer: ArxivHtmlSanitizer = ArxivHtmlSanitizer(),
    private val now: () -> Instant = Instant::now,
) : ReadablePaperLoader {
    override suspend fun load(
        title: String,
        manifestation: PaperManifestation,
        retainDocumentSha256: String?,
    ): ReadablePaperResult {
        if (!manifestation.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) {
            return ReadablePaperResult.Unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        }
        if (retainDocumentSha256 != null && !DOCUMENT_SHA256.matches(retainDocumentSha256)) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val versionedId = versionedArxivId(manifestation)
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.UNVERSIONED_SOURCE)
        val sourceUrl = sourceUrl(versionedId)
        val cacheKey = cacheKey(sourceUrl)
        cache.readForLoad(cacheKey)?.let { cached ->
            if (retainDocumentSha256 != null && cached.documentSha256 != retainDocumentSha256) {
                return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
            }
            val retained = cached.keptForOffline ||
                (retainDocumentSha256 != null && cache.keepForOffline(cacheKey, cached))
            return ReadablePaperResult.Ready(
                cached.copy(keptForOffline = retained).toDocument(
                    title = title,
                    sourceProvider = manifestation.sourceProvider,
                    sourceVersion = versionedId.substringAfterLast('v').let { "v$it" },
                    license = manifestation.license,
                    servedFromCache = true,
                ),
            )
        }
        try {
            cache.removeBySourceUrl(sourceUrl, exceptKey = cacheKey)
        } catch (_: IOException) {
            // Contract cleanup is opportunistic on cache misses; paper removal uses the strict path.
        }
        if (retainDocumentSha256 != null) {
            return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
        }

        val page = when (
            val result = fetcher.fetch(
                ReadableResourceRequest(
                    url = sourceUrl,
                    accept = "text/html, application/xhtml+xml;q=0.9",
                    maximumBytes = MAXIMUM_HTML_BYTES,
                ),
            )
        ) {
            is ReadableRemoteResult.Success -> result.resource
            ReadableRemoteResult.NotFound -> return unavailable(ReadablePaperFailure.SOURCE_NOT_FOUND)
            is ReadableRemoteResult.RateLimited -> return ReadablePaperResult.Unavailable(
                ReadablePaperFailure.RATE_LIMITED,
                result.retryAfterMillis,
            )
            ReadableRemoteResult.Unavailable -> return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
            ReadableRemoteResult.TooLarge -> return unavailable(ReadablePaperFailure.RESPONSE_TOO_LARGE)
            ReadableRemoteResult.Invalid -> return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        if (!page.mediaType.isHtmlMediaType()) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val rawHtml = page.bytes.toString(Charsets.UTF_8)
        val sanitized = sanitizer.sanitize(
            rawHtml = rawHtml,
            sourceUrl = sourceUrl,
            fetchAsset = { url, maximumBytes ->
                fetcher.fetch(
                    ReadableResourceRequest(
                        url = url,
                        accept = "image/png, image/jpeg, image/webp, image/gif",
                        maximumBytes = maximumBytes,
                    ),
                )
            },
        ) ?: return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        val retrievedAt = now()
        val record = CachedReadablePaper(
            bodyHtml = sanitized.bodyHtml,
            sourceUrl = sourceUrl,
            sourceSha256 = sha256(page.bytes),
            documentSha256 = sha256(sanitized.bodyHtml.toByteArray(Charsets.UTF_8)),
            retrievedAt = retrievedAt,
            sourceLicense = sanitized.sourceLicense,
            sections = sanitized.sections,
            warnings = sanitized.warnings,
        )
        try {
            cache.write(cacheKey, record)
        } catch (_: IOException) {
            // The verified document remains readable even when app-private storage is unavailable.
        }
        return ReadablePaperResult.Ready(
            record.toDocument(
                title = title,
                sourceProvider = manifestation.sourceProvider,
                sourceVersion = versionedId.substringAfterLast('v').let { "v$it" },
                license = manifestation.license,
                servedFromCache = false,
            ),
        )
    }

    override suspend fun retain(document: ReadablePaperDocument): Boolean {
        if (!document.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) return false
        if (!DOCUMENT_SHA256.matches(document.sourceSha256) || !DOCUMENT_SHA256.matches(document.documentSha256)) {
            return false
        }
        val versionedId = document.sourceUrl.removePrefix(ARXIV_HTML_PREFIX)
            .takeIf { document.sourceUrl == sourceUrl(it) && VERSIONED_ARXIV_ID.matches(it) }
            ?: return false
        val record = CachedReadablePaper(
            bodyHtml = document.bodyHtml,
            sourceUrl = document.sourceUrl,
            sourceSha256 = document.sourceSha256,
            documentSha256 = document.documentSha256,
            retrievedAt = document.retrievedAt,
            sourceLicense = document.license,
            sections = document.sections,
            warnings = document.warnings,
        )
        return cache.keepForOffline(
            key = cacheKey(sourceUrl(versionedId)),
            verified = record,
        )
    }

    suspend fun removeArtifacts(manifestation: PaperManifestation) {
        if (!manifestation.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) return
        val versionedId = versionedArxivId(manifestation) ?: return
        withContext(Dispatchers.IO) {
            cache.removeBySourceUrl(sourceUrl(versionedId))
        }
    }
    suspend fun reconcileArtifacts(manifestations: Collection<PaperManifestation>) {
        val sourceUrls = manifestations.asSequence()
            .filter { it.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true) }
            .mapNotNull(::versionedArxivId)
            .map(::sourceUrl)
            .toSet()
        withContext(Dispatchers.IO) {
            try {
                cache.removeSourcesNotIn(sourceUrls)
            } catch (_: IOException) {
                // Retry orphan cleanup on the next start without blocking this one.
            }
            sourceUrls.forEach { url ->
                try {
                    cache.removeBySourceUrl(url, exceptKey = cacheKey(url))
                } catch (_: IOException) {
                    // Contract cleanup is opportunistic; the next start retries it.
                }
            }
        }
    }
    private fun cacheKey(sourceUrl: String): String = ReadablePaperCache.keyFor(
        sourceUrl = sourceUrl,
        sanitizerPolicyVersion = SANITIZER_POLICY_VERSION,
        rendererContractVersion = RENDERER_CONTRACT_VERSION,
    )

    private fun sourceUrl(versionedId: String): String = "https://arxiv.org/html/$versionedId"

    private fun versionedArxivId(manifestation: PaperManifestation): String? {
        val recordId = manifestation.sourceRecordId.trim().removePrefix("arXiv:")
        val explicit = VERSIONED_ARXIV_ID.matchEntire(recordId)?.value
        if (explicit != null) return explicit
        if (!UNVERSIONED_ARXIV_ID.matches(recordId)) return null
        val version = manifestation.version
            ?.trim()
            ?.removePrefix("v")
            ?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
            ?: return null
        return "${recordId}v$version"
    }

    private fun unavailable(reason: ReadablePaperFailure) = ReadablePaperResult.Unavailable(reason)

    companion object {
        private const val ARXIV_PROVIDER_ID = "arxiv"
        private const val ARXIV_HTML_PREFIX = "https://arxiv.org/html/"
        // Versioned contracts prevent older cached output from bypassing fidelity or security fixes.
        private const val SANITIZER_POLICY_VERSION = "arxiv-html-sanitizer-10"
        private const val RENDERER_CONTRACT_VERSION = "mobile-html-7"
        private const val MAXIMUM_HTML_BYTES = 4L * 1024L * 1024L
        private val UNVERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})",
        )
        private val VERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})v[1-9][0-9]*",
        )
        private val DOCUMENT_SHA256 = Regex("[0-9a-f]{64}")
    }
}

