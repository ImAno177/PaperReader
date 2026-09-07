package dev.paperreader.extensions.api

import android.os.Bundle

const val LEGACY_READABLE_CONTRACT_VERSION = "legacy"

/** Request for a provider-owned, sanitized HTML document. */
data class SourceGetReadableDocumentRequest(
    val requestId: String,
    val providerRecordId: String,
    val version: String,
) {
    init {
        requireValidRequestId(requestId)
        require(providerRecordId.isNotBlank() && providerRecordId.length <= 256)
        require(version.matches(VERSION_PATTERN))
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putString(Keys.READABLE_PROVIDER_RECORD_ID, providerRecordId)
        putString(Keys.READABLE_VERSION, version)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceGetReadableDocumentRequest = SourceGetReadableDocumentRequest(
            requestId = bundle.requiredString(Keys.REQUEST_ID),
            providerRecordId = bundle.requiredString(Keys.READABLE_PROVIDER_RECORD_ID),
            version = bundle.requiredString(Keys.READABLE_VERSION),
        )

        private val VERSION_PATTERN = Regex("v[1-9][0-9]*")
    }
}

data class SourceReadableSection(
    val anchor: String,
    val title: String,
    val level: Int,
) {
    init {
        require(anchor.matches(ANCHOR_PATTERN))
        require(title.isNotBlank() && title.length <= 180)
        require(level in 1..3)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.READABLE_SECTION_ANCHOR, anchor)
        putString(Keys.READABLE_TITLE, title)
        putInt(Keys.READABLE_SECTION_LEVEL, level)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceReadableSection = SourceReadableSection(
            anchor = bundle.requiredString(Keys.READABLE_SECTION_ANCHOR),
            title = bundle.requiredString(Keys.READABLE_TITLE),
            level = bundle.getInt(Keys.READABLE_SECTION_LEVEL, -1),
        )

        private val ANCHOR_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}

data class SourceReadableAsset(
    val id: String,
    val sourceUrl: String,
    val mediaType: String,
) {
    init {
        require(id.matches(ASSET_ID_PATTERN))
        ExtensionPayloadValidator.requireSafeWebUrl(sourceUrl)
        require(mediaType in SAFE_ASSET_MEDIA_TYPES)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.READABLE_ASSET_ID, id)
        putString(Keys.READABLE_ASSET_URL, sourceUrl)
        putString(Keys.READABLE_ASSET_MEDIA_TYPE, mediaType)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceReadableAsset = SourceReadableAsset(
            id = bundle.requiredString(Keys.READABLE_ASSET_ID),
            sourceUrl = bundle.requiredString(Keys.READABLE_ASSET_URL),
            mediaType = bundle.requiredString(Keys.READABLE_ASSET_MEDIA_TYPE),
        )

        private val ASSET_ID_PATTERN = Regex("[0-9a-f]{64}")
    }
}

enum class SourceReadableWarning(val wireValue: String) {
    TABLE_OF_CONTENTS_MISSING("table_of_contents_missing"),
    FIGURE_UNAVAILABLE("figure_unavailable"),
    SOURCE_CONVERSION_ARTIFACT_NORMALIZED("source_conversion_artifact_normalized"),
}

/** Metadata is sent after all bounded body chunks have been delivered. */
data class SourceReadableDocumentMetadata(
    val requestId: String,
    val title: String,
    val sourceUrl: String,
    val sourceVersion: String,
    val license: String?,
    val sourceSha256: String,
    val documentSha256: String,
    val sections: List<SourceReadableSection>,
    val warnings: Set<SourceReadableWarning>,
    val assets: List<SourceReadableAsset>,
    val contractVersion: String = LEGACY_READABLE_CONTRACT_VERSION,
) {
    init {
        requireValidRequestId(requestId)
        require(title.isNotBlank() && title.length <= PaperExtensionContract.MAX_TITLE_CHARACTERS)
        require(contractVersion.matches(CONTRACT_VERSION_PATTERN))
        ExtensionPayloadValidator.requireSafeWebUrl(sourceUrl)
        require(sourceVersion.matches(Regex("v[1-9][0-9]*")))
        require(license == null || license.length <= 160)
        require(sourceSha256.matches(SHA256_PATTERN))
        require(documentSha256.matches(SHA256_PATTERN))
        require(sections.distinctBy(SourceReadableSection::anchor).size == sections.size)
        require(warnings.size == warnings.distinct().size)
        require(assets.distinctBy(SourceReadableAsset::id).size == assets.size)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putString(Keys.READABLE_TITLE, title)
        putString(Keys.READABLE_CONTRACT_VERSION, contractVersion)
        putString(Keys.READABLE_SOURCE_URL, sourceUrl)
        putString(Keys.READABLE_SOURCE_VERSION, sourceVersion)
        putString(Keys.LICENSE, license)
        putString(Keys.READABLE_SOURCE_SHA256, sourceSha256)
        putString(Keys.READABLE_DOCUMENT_SHA256, documentSha256)
        putParcelableArrayList(Keys.READABLE_SECTIONS, ArrayList(sections.map(SourceReadableSection::toBundle)))
        putStringArrayList(Keys.READABLE_WARNINGS, ArrayList(warnings.map(SourceReadableWarning::wireValue)))
        putParcelableArrayList(Keys.READABLE_ASSETS, ArrayList(assets.map(SourceReadableAsset::toBundle)))
    }.also(ExtensionPayloadValidator::requireBinderSafe)

    companion object {
        fun fromBundle(bundle: Bundle): SourceReadableDocumentMetadata {
            ExtensionPayloadValidator.requireBinderSafe(bundle)
            return SourceReadableDocumentMetadata(
                requestId = bundle.requiredString(Keys.REQUEST_ID),
                title = bundle.requiredString(Keys.READABLE_TITLE),
                contractVersion = bundle.getString(Keys.READABLE_CONTRACT_VERSION)
                    ?: LEGACY_READABLE_CONTRACT_VERSION,
                sourceUrl = bundle.requiredString(Keys.READABLE_SOURCE_URL),
                sourceVersion = bundle.requiredString(Keys.READABLE_SOURCE_VERSION),
                license = bundle.getString(Keys.LICENSE),
                sourceSha256 = bundle.requiredString(Keys.READABLE_SOURCE_SHA256),
                documentSha256 = bundle.requiredString(Keys.READABLE_DOCUMENT_SHA256),
                sections = bundle.bundleList(Keys.READABLE_SECTIONS).map(SourceReadableSection::fromBundle),
                warnings = bundle.getStringArrayList(Keys.READABLE_WARNINGS).orEmpty().mapTo(linkedSetOf()) { wire ->
                    requireNotNull(SourceReadableWarning.entries.firstOrNull { it.wireValue == wire }) {
                        "Unknown readable warning"
                    }
                },
                assets = bundle.bundleList(Keys.READABLE_ASSETS).map(SourceReadableAsset::fromBundle),
            )
        }

        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val CONTRACT_VERSION_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

data class SourceReadableDocumentChunk(
    val requestId: String,
    val sequence: Int,
    val bytes: ByteArray,
) {
    init {
        requireValidRequestId(requestId)
        require(sequence >= 0)
        require(bytes.isNotEmpty() && bytes.size <= PaperExtensionContract.MAX_READABLE_DOCUMENT_CHUNK_BYTES)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putInt(Keys.READABLE_CHUNK_SEQUENCE, sequence)
        putByteArray(Keys.READABLE_CHUNK_BYTES, bytes)
    }.also(ExtensionPayloadValidator::requireBinderSafe)

    companion object {
        fun fromBundle(bundle: Bundle): SourceReadableDocumentChunk {
            ExtensionPayloadValidator.requireBinderSafe(bundle)
            return SourceReadableDocumentChunk(
                requestId = bundle.requiredString(Keys.REQUEST_ID),
                sequence = bundle.getInt(Keys.READABLE_CHUNK_SEQUENCE, -1),
                bytes = requireNotNull(bundle.getByteArray(Keys.READABLE_CHUNK_BYTES)) {
                    "Missing readable document chunk"
                },
            )
        }
    }
}

private val SAFE_ASSET_MEDIA_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/svg+xml",
)
