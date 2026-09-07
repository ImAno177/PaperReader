package dev.paperreader.logic.reader

import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceGetReadableDocumentRequest
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourceReadableAsset
import dev.paperreader.extensions.api.SourceReadableDocumentMetadata
import dev.paperreader.extensions.api.SourceReadableSection
import dev.paperreader.extensions.api.SourceReadableWarning
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperManifestation
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.plugin.RemoteReadableDocument
import dev.paperreader.logic.plugin.SourceExtensionTransport
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginReadablePaperLoaderTest {
    @Test
    fun `loads every verified asset and reopens from cache without the plugin`() = runTest {
        val assetIds = (0 until 17).map { "%064x".format(it + 1) }
        val body = """
            <article><p>${"Readable scholarly content ".repeat(40)}</p>
            ${assetIds.joinToString("") { id -> "<figure><img src=\"paperreader-asset://$id\"><figcaption>Figure $id</figcaption></figure>" }}
            </article>
        """.trimIndent()
        val metadata = metadata(body, assets = assetIds.mapIndexed { index, id -> asset(id, index) })
        val directory = Files.createTempDirectory("plugin-readable-cache")
        var assetCalls = 0
        val transport = FakeTransport(RemoteReadableDocument(metadata, body.toByteArray()))
        val loader = PluginReadablePaperLoader(
            transportForProvider = { "sample".takeIf { it == transport.descriptor.providerId }?.let { transport } },
            fetcher = ReadableResourceFetcher { request ->
                assetCalls += 1
                assertEquals(ReadableResourceKind.ASSET, request.kind)
                ReadableRemoteResult.Success(ReadableRemoteResource(byteArrayOf(assetCalls.toByte()), "image/png"))
            },
            cache = ReadablePaperCache(directory),
            now = { Instant.parse("2026-09-07T00:00:00Z") },
        )
        val first = loader.load("Sample", manifestation(), null) as ReadablePaperResult.Ready

        assertEquals(17, first.document.assets.size)
        assertEquals(17, assetCalls)
        assertEquals(1, transport.readableCalls)
        assertFalse(first.document.servedFromCache)
        assertTrue(first.document.bodyHtml.contains("paperreader-asset://"))

        val cached = PluginReadablePaperLoader(
            transportForProvider = { error("cache must not use the plugin") },
            fetcher = ReadableResourceFetcher { error("cache must not fetch assets") },
            cache = ReadablePaperCache(directory),
        ).load("Sample", manifestation(), null) as ReadablePaperResult.Ready

        assertTrue(cached.document.servedFromCache)
        assertEquals(first.document.documentSha256, cached.document.documentSha256)
        assertEquals(first.document.assets, cached.document.assets)
    }

    @Test
    fun `replaces an unavailable asset while preserving its caption`() = runTest {
        val id = "1".repeat(64)
        val body = "<article><p>${"content ".repeat(60)}</p><figure><img src=\"paperreader-asset://$id\"><figcaption>Important caption</figcaption></figure></article>"
        val loader = PluginReadablePaperLoader(
            transportForProvider = { FakeTransport(RemoteReadableDocument(metadata(body, listOf(asset(id, 0))), body.toByteArray())) },
            fetcher = ReadableResourceFetcher { ReadableRemoteResult.Unavailable },
            cache = ReadablePaperCache(Files.createTempDirectory("plugin-readable-unavailable")),
        )

        val result = loader.load("Sample", manifestation(), null) as ReadablePaperResult.Ready

        assertTrue(result.document.warnings.contains(ReadablePaperWarning.FIGURE_UNAVAILABLE))
        assertTrue(result.document.bodyHtml.contains("Important caption"))
        assertTrue(result.document.bodyHtml.contains("paperreader-figure-unavailable"))
        assertFalse(result.document.bodyHtml.contains("paperreader-asset://"))
    }

    @Test
    fun `rejects an asset outside the verified document directory`() = runTest {
        val id = "2".repeat(64)
        val body = "<article><p>${"content ".repeat(60)}</p><img src=\"paperreader-asset://$id\"></article>"
        val unsafeMetadata = metadata(
            body = body,
            assets = listOf(SourceReadableAsset(id, "https://other.example/figure.png", "image/png")),
        )
        val loader = PluginReadablePaperLoader(
            transportForProvider = { FakeTransport(RemoteReadableDocument(unsafeMetadata, body.toByteArray())) },
            fetcher = ReadableResourceFetcher { error("unsafe metadata must not fetch") },
            cache = ReadablePaperCache(Files.createTempDirectory("plugin-readable-unsafe")),
        )

        assertEquals(
            ReadablePaperFailure.INVALID_RESPONSE,
            (loader.load("Sample", manifestation(), null) as ReadablePaperResult.Unavailable).reason,
        )
        val sanitizer = ReadableAssetSanitizer()
        assertFalse(sanitizer.isTrustedAssetUrl(SOURCE_URL, "$SOURCE_URL/../outside.png"))
        assertFalse(sanitizer.isTrustedAssetUrl(SOURCE_URL, "$SOURCE_URL/%2e%2e/outside.png"))
    }

    @Test
    fun `requires an exact manifestation version without provider-specific parsing`() = runTest {
        val loader = PluginReadablePaperLoader(
            transportForProvider = { error("versionless requests must not reach a provider") },
            fetcher = ReadableResourceFetcher { error("versionless requests must not fetch") },
            cache = ReadablePaperCache(Files.createTempDirectory("plugin-readable-version")),
        )

        assertEquals(
            ReadablePaperFailure.UNVERSIONED_SOURCE,
            (loader.load("Sample", manifestation().copy(version = null), null) as ReadablePaperResult.Unavailable).reason,
        )
    }

    private fun metadata(body: String, assets: List<SourceReadableAsset>) = SourceReadableDocumentMetadata(
        requestId = REQUEST_ID,
        title = "Sample paper",
        contractVersion = "sample-html-1",
        sourceUrl = SOURCE_URL,
        sourceVersion = "v1",
        license = "CC-BY",
        sourceSha256 = "a".repeat(64),
        documentSha256 = sha256(body.toByteArray()),
        sections = listOf(SourceReadableSection("intro", "Introduction", 1)),
        warnings = setOf(SourceReadableWarning.TABLE_OF_CONTENTS_MISSING),
        assets = assets,
    )

    private fun asset(id: String, index: Int) = SourceReadableAsset(
        id = id,
        sourceUrl = "$SOURCE_URL/figure-$index.png",
        mediaType = "image/png",
    )

    private fun manifestation() = PaperManifestation(
        id = ManifestationId("manifestation-1"),
        workId = WorkId("work-1"),
        type = ManifestationType.PREPRINT,
        sourceProvider = "sample",
        sourceRecordId = "record-1",
        version = "v1",
        updatedAt = Instant.EPOCH,
    )

    private class FakeTransport(
        private val readable: RemoteReadableDocument,
    ) : SourceExtensionTransport {
        override val descriptor = SourceExtensionDescriptor(
            packageName = "dev.example.source",
            providerId = "sample",
            displayName = "Sample",
            minimumRequestIntervalMillis = 0,
            capabilities = setOf(SourceCapability.READABLE_DOCUMENT),
            roles = setOf(SourceRole.CONTENT_SOURCE),
            identifierLookupTypes = setOf(SourceIdentifierType.DOI),
            supportedSorts = setOf(SourceSearchSort.RELEVANCE),
        )

        var readableCalls = 0

        override suspend fun search(request: SourceSearchRequest): SourceSearchPage = error("not used")

        override suspend fun getPaper(request: SourceGetPaperRequest): SourcePaperRecord? = error("not used")

        override suspend fun getReadableDocument(request: SourceGetReadableDocumentRequest): RemoteReadableDocument {
            readableCalls += 1
            return readable.copy(metadata = readable.metadata.copy(requestId = request.requestId))
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val REQUEST_ID = "request-1"
        const val SOURCE_URL = "https://example.org/paper/v1"
    }
}
