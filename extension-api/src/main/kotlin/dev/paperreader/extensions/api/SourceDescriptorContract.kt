package dev.paperreader.extensions.api

import android.os.Bundle

enum class SourceCapability(val wireValue: String) {
    SEARCH("search"),
    DETAILS("details"),
    PDF_LINK("pdf_link"),
}

enum class SourceRole(val wireValue: String) {
    SEARCH_ENGINE("search_engine"),
    CONTENT_SOURCE("content_source"),
    METADATA_ENGINE("metadata_engine"),
}

enum class SourceIdentifierType(val wireValue: String) {
    DOI("doi"),
    ARXIV("arxiv"),
    PMID("pmid"),
    PMCID("pmcid"),
}

data class SourceExtensionDescriptor(
    val packageName: String,
    val providerId: String,
    val displayName: String,
    val apiVersion: Int = PaperExtensionContract.API_VERSION,
    val minimumRequestIntervalMillis: Long = 1_000,
    val capabilities: Set<SourceCapability> = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS),
    val roles: Set<SourceRole> = setOf(SourceRole.CONTENT_SOURCE),
    val identifierLookupTypes: Set<SourceIdentifierType> = SourceIdentifierType.entries.toSet(),
    val supportedSorts: Set<SourceSearchSort> = SourceSearchSort.entries.toSet(),
) {
    init {
        require(packageName.contains('.') && packageName.length <= 255)
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}")))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(apiVersion == PaperExtensionContract.API_VERSION)
        require(minimumRequestIntervalMillis in 0..86_400_000)
        require(capabilities.isNotEmpty())
        require(roles.isNotEmpty())
        require(supportedSorts.isNotEmpty())
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.PACKAGE_NAME, packageName)
        putString(Keys.PROVIDER_ID, providerId)
        putString(Keys.DISPLAY_NAME, displayName)
        putInt(Keys.API_VERSION, apiVersion)
        putLong(Keys.MINIMUM_REQUEST_INTERVAL, minimumRequestIntervalMillis)
        putStringArrayList(Keys.CAPABILITIES, ArrayList(capabilities.map(SourceCapability::wireValue)))
        putStringArrayList(Keys.ROLES, ArrayList(roles.map(SourceRole::wireValue)))
        putStringArrayList(Keys.IDENTIFIER_TYPES, ArrayList(identifierLookupTypes.map(SourceIdentifierType::wireValue)))
        putStringArrayList(Keys.SUPPORTED_SORTS, ArrayList(supportedSorts.map(SourceSearchSort::wireValue)))
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceExtensionDescriptor = SourceExtensionDescriptor(
            packageName = bundle.requiredString(Keys.PACKAGE_NAME),
            providerId = bundle.requiredString(Keys.PROVIDER_ID),
            displayName = bundle.requiredString(Keys.DISPLAY_NAME),
            apiVersion = bundle.getInt(Keys.API_VERSION, -1),
            minimumRequestIntervalMillis = bundle.getLong(Keys.MINIMUM_REQUEST_INTERVAL, -1),
            capabilities = bundle.getStringArrayList(Keys.CAPABILITIES)
                .orEmpty()
                .mapTo(linkedSetOf()) { wire ->
                    requireNotNull(SourceCapability.entries.firstOrNull { it.wireValue == wire }) {
                        "Unknown source capability"
                    }
                },
            roles = bundle.getStringArrayList(Keys.ROLES)?.mapTo(linkedSetOf()) { wire ->
                requireNotNull(SourceRole.entries.firstOrNull { it.wireValue == wire }) { "Unknown source role" }
            } ?: setOf(SourceRole.CONTENT_SOURCE),
            identifierLookupTypes = bundle.getStringArrayList(Keys.IDENTIFIER_TYPES)?.mapTo(linkedSetOf()) { wire ->
                requireNotNull(SourceIdentifierType.entries.firstOrNull { it.wireValue == wire }) {
                    "Unknown source identifier type"
                }
            } ?: SourceIdentifierType.entries.toSet(),
            supportedSorts = bundle.getStringArrayList(Keys.SUPPORTED_SORTS)?.mapTo(linkedSetOf()) { wire ->
                requireNotNull(SourceSearchSort.entries.firstOrNull { it.wireValue == wire }) {
                    "Unknown source sort"
                }
            } ?: SourceSearchSort.entries.toSet(),
        )
    }
}

enum class SourceSearchSort(val wireValue: String) {
    RELEVANCE("relevance"),
    NEWEST("newest"),
    OLDEST("oldest"),
}
