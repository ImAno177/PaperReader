package dev.paperreader.logic.reader

import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceGetReadableDocumentRequest
import dev.paperreader.extensions.api.SourceReadableWarning
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.plugin.RemoteReadableDocument
import dev.paperreader.logic.plugin.SourceExtensionRequestException
import dev.paperreader.logic.plugin.SourceExtensionTransport
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Host-side document coordinator. Structural HTML parsing belongs to the source extension;
 * this class owns only cache policy, the independent asset lane, and the final trust gate before
 * a fragment enters the network-blocked WebView.
 */
internal class PluginReadablePaperLoader(
    private val transportForProvider: (String) -> SourceExtensionTransport?,
    private val fetcher: ReadableResourceFetcher,
    private val cache: ReadablePaperCache,
    private val now: () -> Instant = Instant::now,
) : ReadablePaperLoader {
    private val figureProcessor = ArxivReadableFigureProcessor(
        maximumFigureCount = Int.MAX_VALUE,
        maximumFigureBytes = Long.MAX_VALUE,
        maximumSingleFigureBytes = MAXIMUM_READABLE_ASSET_BYTES,
    )

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
        if (cached != null) runCatching { cache.removeBySourceUrl(sourceUrl) }
        try {
            cache.removeBySourceUrl(sourceUrl, exceptKey = cacheKey)
        } catch (_: IOException) {
            // Cache cleanup is opportunistic; a valid new document can still be rendered.
        }
        if (retainDocumentSha256 != null) return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)

        val transport = transportForProvider(manifestation.sourceProvider)
            ?: return unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        if (SourceCapability.READABLE_DOCUMENT !in transport.descriptor.capabilities) {
            return unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        }
        val request = SourceGetReadableDocumentRequest(
            requestId = "readable-${UUID.randomUUID()}",
            providerRecordId = manifestation.sourceRecordId,
            version = versionedId.substringAfterLast('v').let { "v$it" },
        )
        val remote = try {
            transport.getReadableDocument(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SourceExtensionRequestException) {
            return failure.toReadableResult()
        } catch (_: Exception) {
            return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
        }
        val bodyBytes = remote.body
        val metadata = remote.metadata
        if (!isValidRemoteDocument(remote, request, sourceUrl)) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val sanitizedBody = bodyBytes.toString(Charsets.UTF_8)
        val references = metadata.assets.map { asset ->
            ReadablePaperAssetReference(asset.id, asset.sourceUrl)
        }
        val materialized = materializeAssets(cacheKey, references)
        val warnings = metadata.warnings.mapTo(linkedSetOf(), ::toReadableWarning)
        if (materialized.unavailableIds.isNotEmpty()) warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
        val bodyHtml = figureProcessor.replaceUnavailableAssetReferences(
            bodyHtml = sanitizedBody,
            unavailableIds = materialized.unavailableIds,
        )
        val record = CachedReadablePaper(
            bodyHtml = bodyHtml,
            sourceUrl = metadata.sourceUrl,
            sourceSha256 = metadata.sourceSha256,
            documentSha256 = sha256(bodyHtml.toByteArray(Charsets.UTF_8)),
            retrievedAt = now(),
            sourceLicense = metadata.license,
            sections = metadata.sections.map { ReadablePaperSection(it.anchor, it.title, it.level) },
            warnings = warnings,
            assetGroupKey = cacheKey.takeIf { materialized.assets.isNotEmpty() },
            assets = materialized.assets,
        )
        try {
            cache.write(cacheKey, record)
        } catch (_: IOException) {
            // The verified document remains available for this session.
        } catch (_: IllegalArgumentException) {
            // The verified document remains available for this session.
        }
        return ReadablePaperResult.Ready(
            record.toDocument(
                title = title,
                sourceProvider = manifestation.sourceProvider,
                sourceVersion = metadata.sourceVersion,
                license = manifestation.license,
                servedFromCache = false,
            ),
        )
    }

    override suspend fun retain(document: ReadablePaperDocument): Boolean {
        if (!document.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) return false
        if (!DOCUMENT_SHA256.matches(document.sourceSha256) || !DOCUMENT_SHA256.matches(document.documentSha256)) return false
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
        return cache.keepForOffline(cacheKey(sourceUrl(versionedId)), record)
    }

    suspend fun removeArtifacts(manifestation: PaperManifestation) {
        if (!manifestation.sourceProvider.equals(ARXIV_PROVIDER_ID, ignoreCase = true)) return
        versionedArxivId(manifestation)?.let { id ->
            withContext(Dispatchers.IO) { cache.removeBySourceUrl(sourceUrl(id)) }
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
            runCatching { cache.removeSourcesNotIn(sourceUrls) }
            sourceUrls.forEach { url -> runCatching { cache.removeBySourceUrl(url, exceptKey = cacheKey(url)) } }
            runCatching { cache.assetCache.removeGroupsNotIn(sourceUrls.map(::cacheKey).toSet()) }
        }
    }

    private suspend fun materializeAssets(
        assetGroupKey: String,
        references: List<ReadablePaperAssetReference>,
    ): MaterializedAssets = coroutineScope {
        val assets = mutableListOf<ReadablePaperAsset>()
        val unavailableIds = linkedSetOf<String>()
        references.chunked(MAXIMUM_CONCURRENT_ASSET_REQUESTS).forEach { batch ->
            val fetched = batch.map { reference -> async { reference to fetchAsset(reference) } }.awaitAll()
            fetched.forEach { (reference, result) ->
                val safe = (result as? ReadableRemoteResult.Success)?.resource?.let(figureProcessor::sanitizeAsset)
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
            maximumBytes = MAXIMUM_READABLE_ASSET_BYTES,
            kind = ReadableResourceKind.ASSET,
        )
        var retries = 0
        while (true) {
            when (val result = fetcher.fetch(request)) {
                is ReadableRemoteResult.RateLimited -> {
                    if (retries >= MAXIMUM_ASSET_RETRIES) return result
                    delay((result.retryAfterMillis ?: ASSET_RETRY_BACKOFF_MILLIS)
                        .coerceIn(ASSET_RETRY_BACKOFF_MILLIS, MAXIMUM_ASSET_RETRY_DELAY_MILLIS))
                }
                ReadableRemoteResult.Unavailable -> {
                    if (retries >= MAXIMUM_ASSET_RETRIES) return result
                    delay((ASSET_RETRY_BACKOFF_MILLIS shl retries).coerceAtMost(MAXIMUM_ASSET_RETRY_DELAY_MILLIS))
                }
                else -> return result
            }
            retries += 1
        }
    }

    private fun isValidRemoteDocument(
        remote: RemoteReadableDocument,
        request: SourceGetReadableDocumentRequest,
        expectedSourceUrl: String,
    ): Boolean {
        val metadata = remote.metadata
        if (
            metadata.requestId != request.requestId ||
            metadata.sourceUrl != expectedSourceUrl ||
            metadata.sourceVersion != request.version ||
            remote.body.isEmpty() ||
            remote.body.size.toLong() > MAXIMUM_PLUGIN_DOCUMENT_BYTES ||
            !DOCUMENT_SHA256.matches(metadata.sourceSha256) ||
            !DOCUMENT_SHA256.matches(metadata.documentSha256) ||
            sha256(remote.body) != metadata.documentSha256
        ) return false
        val body = remote.body.toString(Charsets.UTF_8)
        val parsed = Jsoup.parseBodyFragment(body, expectedSourceUrl)
        if (parsed.body().text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return false
        if (parsed.select("script, style, link, base, iframe, frame, object, embed, form, input, button, textarea, select, svg").isNotEmpty()) return false
        return parsed.allElements.none { element ->
            element.attributes().any { attribute -> attribute.key.startsWith("on", ignoreCase = true) }
        } && remote.metadata.assets.all { asset ->
            val uri = runCatching { URI(asset.sourceUrl) }.getOrNull()
            uri?.scheme == "https" && uri.host == "arxiv.org" && uri.userInfo == null && uri.fragment == null
        }
    }

    private fun bodyReferencesKnownAssets(bodyHtml: String, assets: List<ReadablePaperAsset>): Boolean {
        val known = assets.mapTo(hashSetOf(), ReadablePaperAsset::id)
        return ASSET_REFERENCE.findAll(bodyHtml).all { it.groupValues[1] in known }
    }

    private fun toReadableWarning(warning: SourceReadableWarning): ReadablePaperWarning = when (warning) {
        SourceReadableWarning.TABLE_OF_CONTENTS_MISSING -> ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING
        SourceReadableWarning.FIGURE_UNAVAILABLE -> ReadablePaperWarning.FIGURE_UNAVAILABLE
        SourceReadableWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED -> ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED
    }

    private fun SourceExtensionRequestException.toReadableResult(): ReadablePaperResult = when (failure.code) {
        ExtensionFailureCode.RATE_LIMITED -> ReadablePaperResult.Unavailable(
            ReadablePaperFailure.RATE_LIMITED,
            failure.retryAfterMillis,
        )
        ExtensionFailureCode.INVALID_RESPONSE,
        ExtensionFailureCode.INVALID_REQUEST,
        -> unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        ExtensionFailureCode.CANCELLED -> throw CancellationException(failure.message)
        ExtensionFailureCode.UNAVAILABLE,
        ExtensionFailureCode.INTERNAL_ERROR,
        -> unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
    }

    private fun cacheKey(sourceUrl: String): String = ReadablePaperCache.keyFor(
        sourceUrl = sourceUrl,
        sanitizerPolicyVersion = ARXIV_READABLE_SANITIZER_POLICY_VERSION,
        rendererContractVersion = RENDERER_CONTRACT_VERSION,
    )

    private fun versionedArxivId(manifestation: PaperManifestation): String? {
        val recordId = manifestation.sourceRecordId.trim().removePrefix("arXiv:")
        VERSIONED_ARXIV_ID.matchEntire(recordId)?.value?.let { return it }
        if (!UNVERSIONED_ARXIV_ID.matches(recordId)) return null
        val version = manifestation.version?.trim()?.removePrefix("v")
            ?.takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return null
        return "${recordId}v$version"
    }

    private fun sourceUrl(versionedId: String): String = "$ARXIV_HTML_PREFIX$versionedId"

    private fun unavailable(reason: ReadablePaperFailure) = ReadablePaperResult.Unavailable(reason)

    private data class MaterializedAssets(
        val assets: List<ReadablePaperAsset>,
        val unavailableIds: Set<String>,
    )

    private companion object {
        const val ARXIV_PROVIDER_ID = "arxiv"
        const val ARXIV_HTML_PREFIX = "https://arxiv.org/html/"
        const val RENDERER_CONTRACT_VERSION = "mobile-html-9"
        const val MAXIMUM_PLUGIN_DOCUMENT_BYTES = 4L * 1024L * 1024L
        const val MAXIMUM_CONCURRENT_ASSET_REQUESTS = 4
        const val MAXIMUM_ASSET_RETRIES = 3
        const val ASSET_RETRY_BACKOFF_MILLIS = 3_000L
        const val MAXIMUM_ASSET_RETRY_DELAY_MILLIS = 30_000L
        const val MINIMUM_ARTICLE_TEXT_LENGTH = 300
        val UNVERSIONED_ARXIV_ID = Regex("(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})")
        val VERSIONED_ARXIV_ID = Regex("(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z][A-Za-z0-9.-]*/[0-9]{7})v[1-9][0-9]*")
        val DOCUMENT_SHA256 = Regex("[0-9a-f]{64}")
        val ASSET_REFERENCE = Regex("paperreader-asset://([0-9a-f]{64})")
    }
}
