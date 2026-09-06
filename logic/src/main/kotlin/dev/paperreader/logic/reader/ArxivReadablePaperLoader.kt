package dev.paperreader.logic.reader

import dev.paperreader.logic.domain.PaperManifestation
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        val cached = cache.readForLoad(cacheKey)
        if (
            cached != null &&
            cache.assetCache.allPresent(cached.assetGroupKey, cached.assets) &&
            bodyReferencesKnownAssets(cached.bodyHtml, cached.assets)
        ) {
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
        if (cached != null) {
            runCatching { cache.removeBySourceUrl(sourceUrl) }
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
        val sanitized = sanitizer.sanitizeForReader(
            rawHtml = rawHtml,
            sourceUrl = sourceUrl,
        ) ?: return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        val materialized = materializeAssets(cacheKey, sanitized.assets)
        val warnings = sanitized.warnings.toMutableSet()
        if (materialized.unavailableIds.isNotEmpty()) warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
        val bodyHtml = sanitizer.replaceUnavailableAssetReferences(
            bodyHtml = sanitized.bodyHtml,
            unavailableIds = materialized.unavailableIds,
        )
        val retrievedAt = now()
        val record = CachedReadablePaper(
            bodyHtml = bodyHtml,
            sourceUrl = sourceUrl,
            sourceSha256 = sha256(page.bytes),
            documentSha256 = sha256(bodyHtml.toByteArray(Charsets.UTF_8)),
            retrievedAt = retrievedAt,
            sourceLicense = sanitized.sourceLicense,
            sections = sanitized.sections,
            warnings = warnings,
            assetGroupKey = cacheKey.takeIf { materialized.assets.isNotEmpty() },
            assets = materialized.assets,
        )
        try {
            cache.write(cacheKey, record)
        } catch (_: IOException) {
            // The verified document remains readable even when app-private storage is unavailable.
        } catch (_: IllegalArgumentException) {
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
            assetGroupKey = document.assetGroupKey,
            assets = document.assets,
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

    fun openAsset(document: ReadablePaperDocument, assetId: String): ReadablePaperAssetContent? {
        val asset = document.assets.firstOrNull { it.id == assetId } ?: return null
        return cache.assetCache.open(document.assetGroupKey, asset)
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
            cache.assetCache.removeGroupsNotIn(sourceUrls.map(::cacheKey).toSet())
        }
    }

    private suspend fun materializeAssets(
        assetGroupKey: String,
        references: List<ReadablePaperAssetReference>,
    ): MaterializedAssets = coroutineScope {
        val assets = mutableListOf<ReadablePaperAsset>()
        val unavailableIds = linkedSetOf<String>()
        references.chunked(MAXIMUM_CONCURRENT_ASSET_REQUESTS).forEach { batch ->
            val fetched = batch.map { reference ->
                async { reference to fetchAsset(reference) }
            }.awaitAll()
            fetched.forEach { (reference, result) ->
                val safe = (result as? ReadableRemoteResult.Success)
                    ?.resource
                    ?.let(sanitizer::sanitizeAsset)
                if (safe == null) {
                    unavailableIds += reference.id
                    cache.assetCache.remove(assetGroupKey, reference.id)
                    return@forEach
                }
                val asset = ReadablePaperAsset(
                    id = reference.id,
                    mediaType = safe.mediaType,
                    sha256 = sha256(safe.bytes),
                    byteLength = safe.bytes.size.toLong(),
                )
                try {
                    cache.assetCache.write(assetGroupKey, asset, safe.bytes)
                    assets += asset
                } catch (_: IOException) {
                    unavailableIds += reference.id
                    cache.assetCache.remove(assetGroupKey, reference.id)
                }
            }
        }
        MaterializedAssets(assets, unavailableIds)
    }

    private suspend fun fetchAsset(reference: ReadablePaperAssetReference): ReadableRemoteResult {
        val request = ReadableResourceRequest(
            url = reference.sourceUrl,
            accept = "image/png, image/jpeg, image/webp, image/gif, image/svg+xml",
            maximumBytes = MAXIMUM_ASSET_BYTES,
            kind = ReadableResourceKind.ASSET,
        )
        var retries = 0
        while (true) {
            when (val result = fetcher.fetch(request)) {
                is ReadableRemoteResult.RateLimited -> {
                    if (retries >= MAXIMUM_ASSET_RETRIES) return result
                    delay(
                        (result.retryAfterMillis ?: ASSET_RETRY_BACKOFF_MILLIS)
                            .coerceIn(ASSET_RETRY_BACKOFF_MILLIS, MAXIMUM_ASSET_RETRY_DELAY_MILLIS),
                    )
                }
                ReadableRemoteResult.Unavailable -> {
                    if (retries >= MAXIMUM_ASSET_RETRIES) return result
                    delay(
                        (ASSET_RETRY_BACKOFF_MILLIS shl retries)
                            .coerceAtMost(MAXIMUM_ASSET_RETRY_DELAY_MILLIS),
                    )
                }
                else -> return result
            }
            retries += 1
        }
    }

    private fun bodyReferencesKnownAssets(bodyHtml: String, assets: List<ReadablePaperAsset>): Boolean {
        val known = assets.mapTo(hashSetOf(), ReadablePaperAsset::id)
        return ASSET_REFERENCE.findAll(bodyHtml).all { match -> match.groupValues[1] in known }
    }

    private data class MaterializedAssets(
        val assets: List<ReadablePaperAsset>,
        val unavailableIds: Set<String>,
    )
    private fun cacheKey(sourceUrl: String): String = ReadablePaperCache.keyFor(
        sourceUrl = sourceUrl,
        sanitizerPolicyVersion = ARXIV_READABLE_SANITIZER_POLICY_VERSION,
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
        private const val RENDERER_CONTRACT_VERSION = "mobile-html-8"
        private const val MAXIMUM_HTML_BYTES = 4L * 1024L * 1024L
        private const val MAXIMUM_ASSET_BYTES = MAXIMUM_READABLE_ASSET_BYTES
        private const val MAXIMUM_CONCURRENT_ASSET_REQUESTS = 4
        private const val MAXIMUM_ASSET_RETRIES = 3
        private const val ASSET_RETRY_BACKOFF_MILLIS = 3_000L
        private const val MAXIMUM_ASSET_RETRY_DELAY_MILLIS = 30_000L
        private val UNVERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})",
        )
        private val VERSIONED_ARXIV_ID = Regex(
            "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})v[1-9][0-9]*",
        )
        private val DOCUMENT_SHA256 = Regex("[0-9a-f]{64}")
        private val ASSET_REFERENCE = Regex("paperreader-asset://([0-9a-f]{64})")
    }
}
