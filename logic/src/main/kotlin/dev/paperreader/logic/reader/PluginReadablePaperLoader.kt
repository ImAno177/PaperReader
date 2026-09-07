package dev.paperreader.logic.reader

import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceGetReadableDocumentRequest
import dev.paperreader.extensions.api.SourceReadableWarning
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.plugin.RemoteReadableDocument
import dev.paperreader.logic.plugin.SourceExtensionRequestException
import dev.paperreader.logic.plugin.SourceExtensionTransport
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Provider-neutral coordinator for the extension document lane and host asset lane. */
internal class PluginReadablePaperLoader(
    private val transportForProvider: (String) -> SourceExtensionTransport?,
    private val fetcher: ReadableResourceFetcher,
    private val cache: ReadablePaperCache,
    private val now: () -> Instant = Instant::now,
    private val assetSanitizer: ReadableAssetSanitizer = ReadableAssetSanitizer(),
) : ReadablePaperLoader {
    private val assetPermits = Semaphore(MAXIMUM_CONCURRENT_ASSET_REQUESTS)

    override suspend fun load(
        title: String,
        manifestation: PaperManifestation,
        retainDocumentSha256: String?,
    ): ReadablePaperResult {
        if (retainDocumentSha256 != null && !SHA256_PATTERN.matches(retainDocumentSha256)) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val identity = identityFor(manifestation)
            ?: return unavailable(ReadablePaperFailure.UNVERSIONED_SOURCE)
        val cached = cache.readForIdentity(
            identity = identity,
            rendererContractVersion = READABLE_RENDERER_CONTRACT_VERSION,
        )
        if (cached != null) {
            if (retainDocumentSha256 != null && cached.paper.documentSha256 != retainDocumentSha256) {
                return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
            }
            val retained = cached.paper.keptForOffline ||
                (retainDocumentSha256 != null && cache.keepForOffline(cached.key, cached.paper))
            return ReadablePaperResult.Ready(
                cached.paper.copy(keptForOffline = retained).toDocument(
                    title = title,
                    sourceProvider = identity.providerId,
                    sourceVersion = cached.paper.sourceVersion.ifBlank { identity.sourceVersion },
                    license = manifestation.license,
                    servedFromCache = true,
                    sourceRecordId = identity.providerRecordId,
                ),
            )
        }
        if (retainDocumentSha256 != null) return unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)

        val transport = transportForProvider(identity.providerId)
            ?: return unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        if (SourceCapability.READABLE_DOCUMENT !in transport.descriptor.capabilities) {
            return unavailable(ReadablePaperFailure.UNSUPPORTED_SOURCE)
        }
        val request = SourceGetReadableDocumentRequest(
            requestId = "readable-${UUID.randomUUID()}",
            providerRecordId = identity.providerRecordId,
            version = identity.sourceVersion,
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
        if (!isValidRemoteDocument(remote, request)) {
            return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        }
        val body = decodeUtf8(remote.body) ?: return unavailable(ReadablePaperFailure.INVALID_RESPONSE)
        val references = remote.metadata.assets.map { asset ->
            ReadablePaperAssetReference(asset.id, asset.sourceUrl)
        }
        val cacheKey = ReadablePaperCache.keyFor(
            providerId = identity.providerId,
            providerRecordId = identity.providerRecordId,
            sourceVersion = identity.sourceVersion,
            readableContractVersion = remote.metadata.contractVersion,
            rendererContractVersion = READABLE_RENDERER_CONTRACT_VERSION,
        )
        val materialized = materializeAssets(cacheKey, references)
        val warnings = remote.metadata.warnings.mapTo(linkedSetOf(), ::toReadableWarning)
        if (materialized.unavailableIds.isNotEmpty()) warnings += ReadablePaperWarning.FIGURE_UNAVAILABLE
        val bodyHtml = assetSanitizer.replaceUnavailableAssetReferences(
            bodyHtml = body,
            unavailableIds = materialized.unavailableIds,
        )
        val record = CachedReadablePaper(
            bodyHtml = bodyHtml,
            sourceUrl = remote.metadata.sourceUrl,
            sourceSha256 = remote.metadata.sourceSha256,
            documentSha256 = sha256(bodyHtml.toByteArray(Charsets.UTF_8)),
            retrievedAt = now(),
            sourceLicense = remote.metadata.license,
            sections = remote.metadata.sections.map { ReadablePaperSection(it.anchor, it.title, it.level) },
            warnings = warnings,
            assetGroupKey = cacheKey.takeIf { materialized.assets.isNotEmpty() },
            assets = materialized.assets,
            sourceProvider = identity.providerId,
            sourceRecordId = identity.providerRecordId,
            sourceVersion = identity.sourceVersion,
            readableContractVersion = remote.metadata.contractVersion,
            rendererContractVersion = READABLE_RENDERER_CONTRACT_VERSION,
        )
        runCatching {
            cache.removeByIdentity(identity)
            cache.write(cacheKey, record)
        }.onFailure { error ->
            if (error !is IOException && error !is IllegalArgumentException) throw error
        }
        return ReadablePaperResult.Ready(
            record.toDocument(
                title = title,
                sourceProvider = identity.providerId,
                sourceVersion = identity.sourceVersion,
                license = manifestation.license,
                servedFromCache = false,
                sourceRecordId = identity.providerRecordId,
            ),
        )
    }

    override suspend fun retain(document: ReadablePaperDocument): Boolean {
        val identity = ReadablePaperCacheIdentity(
            providerId = document.sourceProvider,
            providerRecordId = document.sourceRecordId,
            sourceVersion = document.sourceVersion,
        )
        if (
            identity.providerId.isBlank() || identity.providerRecordId.isBlank() ||
            !VERSION_PATTERN.matches(identity.sourceVersion) ||
            !CONTRACT_VERSION_PATTERN.matches(document.readableContractVersion) ||
            document.rendererContractVersion != READABLE_RENDERER_CONTRACT_VERSION ||
            !assetSanitizer.isSafeDocumentUrl(document.sourceUrl) ||
            !SHA256_PATTERN.matches(document.sourceSha256) ||
            !SHA256_PATTERN.matches(document.documentSha256) ||
            sha256(document.bodyHtml.toByteArray(Charsets.UTF_8)) != document.documentSha256
        ) return false
        val key = ReadablePaperCache.keyFor(
            providerId = identity.providerId,
            providerRecordId = identity.providerRecordId,
            sourceVersion = identity.sourceVersion,
            readableContractVersion = document.readableContractVersion,
            rendererContractVersion = document.rendererContractVersion,
        )
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
            sourceProvider = identity.providerId,
            sourceRecordId = identity.providerRecordId,
            sourceVersion = identity.sourceVersion,
            readableContractVersion = document.readableContractVersion,
            rendererContractVersion = document.rendererContractVersion,
        )
        return cache.keepForOffline(key, record)
    }

    suspend fun removeArtifacts(manifestation: PaperManifestation) {
        identityFor(manifestation)?.let { identity ->
            withContext(Dispatchers.IO) { cache.removeByIdentity(identity) }
        }
    }

    fun openAsset(document: ReadablePaperDocument, assetId: String): ReadablePaperAssetContent? {
        val asset = document.assets.firstOrNull { it.id == assetId } ?: return null
        return cache.assetCache.open(document.assetGroupKey, asset)
    }

    suspend fun reconcileArtifacts(manifestations: Collection<PaperManifestation>) {
        val identities = manifestations.mapNotNull(::identityFor).toSet()
        withContext(Dispatchers.IO) { cache.reconcileIdentities(identities) }
    }

    private suspend fun materializeAssets(
        assetGroupKey: String,
        references: List<ReadablePaperAssetReference>,
    ): MaterializedAssets = coroutineScope {
        val fetched = references.map { reference ->
            async {
                assetPermits.withPermit { reference to fetchAsset(reference) }
            }
        }.awaitAll()
        val assets = mutableListOf<ReadablePaperAsset>()
        val unavailableIds = linkedSetOf<String>()
        fetched.forEach { (reference, result) ->
            val safe = (result as? ReadableRemoteResult.Success)?.resource?.let(assetSanitizer::sanitize)
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
    ): Boolean {
        val metadata = remote.metadata
        if (
            metadata.requestId != request.requestId ||
            metadata.sourceVersion != request.version ||
            !CONTRACT_VERSION_PATTERN.matches(metadata.contractVersion) ||
            remote.body.isEmpty() ||
            remote.body.size.toLong() > PaperExtensionContract.MAX_READABLE_DOCUMENT_BYTES ||
            !SHA256_PATTERN.matches(metadata.sourceSha256) ||
            !SHA256_PATTERN.matches(metadata.documentSha256) ||
            sha256(remote.body) != metadata.documentSha256 ||
            !assetSanitizer.isSafeDocumentUrl(metadata.sourceUrl)
        ) return false
        val body = decodeUtf8(remote.body) ?: return false
        val parsed = Jsoup.parseBodyFragment(body, metadata.sourceUrl)
        if (parsed.body().text().length < MINIMUM_ARTICLE_TEXT_LENGTH) return false
        if (parsed.select(EXECUTABLE_SELECTORS).isNotEmpty()) return false
        if (parsed.allElements.any { element ->
                element.attributes().any { attribute -> attribute.key.startsWith("on", ignoreCase = true) }
            }
        ) return false
        if (parsed.select("img[src]").any { image -> !ASSET_REFERENCE.matches(image.attr("src")) }) return false
        val assetIds = remote.metadata.assets.mapTo(hashSetOf(), dev.paperreader.extensions.api.SourceReadableAsset::id)
        if (ASSET_REFERENCE.findAll(body).any { it.groupValues[1] !in assetIds }) return false
        if (remote.metadata.assets.any { asset ->
                !assetSanitizer.isTrustedAssetUrl(metadata.sourceUrl, asset.sourceUrl)
            }
        ) return false
        return parsed.select("a[href]").all(::isSafeLink)
    }

    private fun isSafeLink(link: org.jsoup.nodes.Element): Boolean {
        val href = link.attr("href")
        if (href.startsWith("#")) return true
        val uri = runCatching { URI(href) }.getOrNull() ?: return false
        return uri.scheme in setOf("https", "mailto") && uri.userInfo == null
    }

    private fun identityFor(manifestation: PaperManifestation): ReadablePaperCacheIdentity? {
        val providerId = manifestation.sourceProvider.trim().takeIf(String::isNotBlank) ?: return null
        val recordId = manifestation.sourceRecordId.trim()
            .takeIf { it.isNotBlank() && it.length <= MAXIMUM_PROVIDER_RECORD_ID_LENGTH }
            ?: return null
        val version = manifestation.version?.trim()?.takeIf(VERSION_PATTERN::matches) ?: return null
        return ReadablePaperCacheIdentity(providerId, recordId, version)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun toReadableWarning(warning: SourceReadableWarning): ReadablePaperWarning = when (warning) {
        SourceReadableWarning.TABLE_OF_CONTENTS_MISSING -> ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING
        SourceReadableWarning.FIGURE_UNAVAILABLE -> ReadablePaperWarning.FIGURE_UNAVAILABLE
        SourceReadableWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED ->
            ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED
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

    private fun unavailable(reason: ReadablePaperFailure) = ReadablePaperResult.Unavailable(reason)

    private data class MaterializedAssets(
        val assets: List<ReadablePaperAsset>,
        val unavailableIds: Set<String>,
    )

    private companion object {
        const val MAXIMUM_CONCURRENT_ASSET_REQUESTS = 4
        const val MAXIMUM_ASSET_RETRIES = 3
        const val ASSET_RETRY_BACKOFF_MILLIS = 3_000L
        const val MAXIMUM_ASSET_RETRY_DELAY_MILLIS = 30_000L
        const val MINIMUM_ARTICLE_TEXT_LENGTH = 300
        const val MAXIMUM_PROVIDER_RECORD_ID_LENGTH = 256
        val VERSION_PATTERN = Regex("v[1-9][0-9]*")
        val CONTRACT_VERSION_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val ASSET_REFERENCE = Regex("paperreader-asset://([0-9a-f]{64})")
        const val EXECUTABLE_SELECTORS =
            "script, style, link, base, iframe, frame, object, embed, form, input, button, textarea, select, svg"
    }
}
