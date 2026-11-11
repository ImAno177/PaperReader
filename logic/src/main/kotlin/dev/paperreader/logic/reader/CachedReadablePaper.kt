package dev.paperreader.logic.reader

import java.time.Instant

internal data class CachedReadablePaper(
    val bodyHtml: String,
    val sourceUrl: String,
    val sourceSha256: String,
    val documentSha256: String,
    val retrievedAt: Instant,
    val sourceLicense: String?,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
    val keptForOffline: Boolean = false,
    val assetGroupKey: String? = null,
    val assets: List<ReadablePaperAsset> = emptyList(),
    val sourceProvider: String = "",
    val sourceRecordId: String = "",
    val sourceVersion: String = "",
    val readableContractVersion: String = "legacy",
    val rendererContractVersion: String = READABLE_RENDERER_CONTRACT_VERSION,
) {
    fun toDocument(
        title: String,
        sourceProvider: String,
        sourceVersion: String,
        license: String?,
        servedFromCache: Boolean,
        sourceRecordId: String = this.sourceRecordId,
    ) = ReadablePaperDocument(
        bodyHtml = bodyHtml,
        title = title,
        sourceUrl = sourceUrl,
        sourceProvider = sourceProvider,
        sourceVersion = sourceVersion,
        license = license ?: sourceLicense,
        sourceSha256 = sourceSha256,
        documentSha256 = documentSha256,
        retrievedAt = retrievedAt,
        servedFromCache = servedFromCache,
        keptForOffline = keptForOffline,
        sections = sections,
        warnings = warnings,
        assetGroupKey = assetGroupKey,
        assets = assets,
        sourceRecordId = sourceRecordId,
        readableContractVersion = readableContractVersion,
        rendererContractVersion = rendererContractVersion,
    )
}

internal data class ReadablePaperCacheIdentity(
    val providerId: String,
    val providerRecordId: String,
    val sourceVersion: String,
)

internal data class CachedReadablePaperEntry(
    val key: String,
    val paper: CachedReadablePaper,
)
