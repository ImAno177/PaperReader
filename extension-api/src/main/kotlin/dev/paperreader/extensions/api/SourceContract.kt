package dev.paperreader.extensions.api

import android.os.Bundle

enum class SourceCapability(val wireValue: String) {
    SEARCH("search"),
    DETAILS("details"),
    PDF_LINK("pdf_link"),
}

data class SourceExtensionDescriptor(
    val packageName: String,
    val providerId: String,
    val displayName: String,
    val apiVersion: Int = PaperExtensionContract.API_VERSION,
    val minimumRequestIntervalMillis: Long = 1_000,
    val capabilities: Set<SourceCapability> = setOf(SourceCapability.SEARCH, SourceCapability.DETAILS),
) {
    init {
        require(packageName.contains('.') && packageName.length <= 255)
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}")))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(apiVersion == PaperExtensionContract.API_VERSION)
        require(minimumRequestIntervalMillis in 0..86_400_000)
        require(capabilities.isNotEmpty())
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.PACKAGE_NAME, packageName)
        putString(Keys.PROVIDER_ID, providerId)
        putString(Keys.DISPLAY_NAME, displayName)
        putInt(Keys.API_VERSION, apiVersion)
        putLong(Keys.MINIMUM_REQUEST_INTERVAL, minimumRequestIntervalMillis)
        putStringArrayList(Keys.CAPABILITIES, ArrayList(capabilities.map(SourceCapability::wireValue)))
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
        )
    }
}

enum class SourceSearchSort(val wireValue: String) {
    RELEVANCE("relevance"),
    NEWEST("newest"),
    OLDEST("oldest"),
}

data class SourceSearchRequest(
    val requestId: String,
    val query: String,
    val limit: Int,
    val cursor: String? = null,
    val sort: SourceSearchSort = SourceSearchSort.RELEVANCE,
) {
    init {
        requireValidRequestId(requestId)
        require(query.isNotBlank() && query.length <= PaperExtensionContract.MAX_QUERY_CHARACTERS)
        require(limit in 1..PaperExtensionContract.MAX_RESULTS_PER_PAGE)
        require(cursor == null || cursor.length <= PaperExtensionContract.MAX_CURSOR_CHARACTERS)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putString(Keys.QUERY, query)
        putInt(Keys.LIMIT, limit)
        putString(Keys.CURSOR, cursor)
        putString(Keys.SORT, sort.wireValue)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceSearchRequest = SourceSearchRequest(
            requestId = bundle.requiredString(Keys.REQUEST_ID),
            query = bundle.requiredString(Keys.QUERY),
            limit = bundle.getInt(Keys.LIMIT, -1),
            cursor = bundle.getString(Keys.CURSOR),
            sort = SourceSearchSort.entries.firstOrNull {
                it.wireValue == bundle.getString(Keys.SORT)
            } ?: SourceSearchSort.RELEVANCE,
        )
    }
}

data class SourceGetPaperRequest(
    val requestId: String,
    val providerRecordId: String,
) {
    init {
        requireValidRequestId(requestId)
        require(providerRecordId.isNotBlank() && providerRecordId.length <= 256)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putString(Keys.RECORD_ID, providerRecordId)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceGetPaperRequest = SourceGetPaperRequest(
            requestId = bundle.requiredString(Keys.REQUEST_ID),
            providerRecordId = bundle.requiredString(Keys.RECORD_ID),
        )
    }
}

data class SourceManifestation(
    val type: String,
    val version: String? = null,
    val landingPageUrl: String? = null,
    val pdfUrl: String? = null,
    val license: String? = null,
    val publishedDate: String? = null,
) {
    init {
        require(type in setOf("preprint", "accepted_manuscript", "version_of_record", "other"))
        require(version == null || version.length <= 64)
        ExtensionPayloadValidator.requireSafeWebUrl(landingPageUrl)
        ExtensionPayloadValidator.requireSafeWebUrl(pdfUrl)
        require(license == null || license.length <= 512)
        require(publishedDate == null || ISO_DATE.matches(publishedDate))
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.TYPE, type)
        putString(Keys.VERSION, version)
        putString(Keys.LANDING_PAGE_URL, landingPageUrl)
        putString(Keys.PDF_URL, pdfUrl)
        putString(Keys.LICENSE, license)
        putString(Keys.PUBLISHED_DATE, publishedDate)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourceManifestation = SourceManifestation(
            type = bundle.requiredString(Keys.TYPE),
            version = bundle.getString(Keys.VERSION),
            landingPageUrl = bundle.getString(Keys.LANDING_PAGE_URL),
            pdfUrl = bundle.getString(Keys.PDF_URL),
            license = bundle.getString(Keys.LICENSE),
            publishedDate = bundle.getString(Keys.PUBLISHED_DATE),
        )
    }
}

data class SourcePaperRecord(
    val providerRecordId: String,
    val title: String,
    val abstractText: String? = null,
    val authors: List<String> = emptyList(),
    val subjects: Set<String> = emptySet(),
    val doi: String? = null,
    val arxivId: String? = null,
    val publishedDate: String? = null,
    val updatedAt: String? = null,
    val manifestations: List<SourceManifestation> = emptyList(),
) {
    init {
        require(providerRecordId.isNotBlank() && providerRecordId.length <= 256)
        require(title.isNotBlank() && title.length <= PaperExtensionContract.MAX_TITLE_CHARACTERS)
        require(abstractText == null || abstractText.length <= PaperExtensionContract.MAX_ABSTRACT_CHARACTERS)
        require(authors.size <= PaperExtensionContract.MAX_AUTHORS)
        require(authors.all { it.isNotBlank() && it.length <= 256 })
        require(subjects.size <= PaperExtensionContract.MAX_SUBJECTS)
        require(subjects.all { it.isNotBlank() && it.length <= 256 })
        require(doi == null || DOI.matches(doi))
        require(arxivId == null || ARXIV_ID.matches(arxivId))
        require(publishedDate == null || ISO_DATE.matches(publishedDate))
        require(updatedAt == null || updatedAt.length <= 64)
        require(manifestations.size <= 20)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.RECORD_ID, providerRecordId)
        putString(Keys.TITLE, title)
        putString(Keys.ABSTRACT, abstractText)
        putStringArrayList(Keys.AUTHORS, ArrayList(authors))
        putStringArrayList(Keys.SUBJECTS, ArrayList(subjects.sorted()))
        putString(Keys.DOI, doi)
        putString(Keys.ARXIV_ID, arxivId)
        putString(Keys.PUBLISHED_DATE, publishedDate)
        putString(Keys.UPDATED_AT, updatedAt)
        putParcelableArrayList(Keys.MANIFESTATIONS, ArrayList(manifestations.map(SourceManifestation::toBundle)))
    }

    companion object {
        fun fromBundle(bundle: Bundle): SourcePaperRecord = SourcePaperRecord(
            providerRecordId = bundle.requiredString(Keys.RECORD_ID),
            title = bundle.requiredString(Keys.TITLE),
            abstractText = bundle.getString(Keys.ABSTRACT),
            authors = bundle.getStringArrayList(Keys.AUTHORS).orEmpty(),
            subjects = bundle.getStringArrayList(Keys.SUBJECTS).orEmpty().toSet(),
            doi = bundle.getString(Keys.DOI),
            arxivId = bundle.getString(Keys.ARXIV_ID),
            publishedDate = bundle.getString(Keys.PUBLISHED_DATE),
            updatedAt = bundle.getString(Keys.UPDATED_AT),
            manifestations = bundle.bundleList(Keys.MANIFESTATIONS).map(SourceManifestation::fromBundle),
        )
    }
}

data class SourceSearchPage(
    val requestId: String,
    val records: List<SourcePaperRecord>,
    val nextCursor: String? = null,
) {
    init {
        requireValidRequestId(requestId)
        require(records.size <= PaperExtensionContract.MAX_RESULTS_PER_PAGE)
        require(nextCursor == null || nextCursor.length <= PaperExtensionContract.MAX_CURSOR_CHARACTERS)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putParcelableArrayList(Keys.RESULTS, ArrayList(records.map(SourcePaperRecord::toBundle)))
        putString(Keys.NEXT_CURSOR, nextCursor)
    }.also(ExtensionPayloadValidator::requireBinderSafe)

    companion object {
        fun fromBundle(bundle: Bundle): SourceSearchPage {
            ExtensionPayloadValidator.requireBinderSafe(bundle)
            return SourceSearchPage(
                requestId = bundle.requiredString(Keys.REQUEST_ID),
                records = bundle.bundleList(Keys.RESULTS).map(SourcePaperRecord::fromBundle),
                nextCursor = bundle.getString(Keys.NEXT_CURSOR),
            )
        }
    }
}

data class SourcePaperResponse(
    val requestId: String,
    val record: SourcePaperRecord?,
) {
    init {
        requireValidRequestId(requestId)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        record?.let { putBundle(Keys.RESULT, it.toBundle()) }
    }.also(ExtensionPayloadValidator::requireBinderSafe)

    companion object {
        fun fromBundle(bundle: Bundle): SourcePaperResponse {
            ExtensionPayloadValidator.requireBinderSafe(bundle)
            return SourcePaperResponse(
                requestId = bundle.requiredString(Keys.REQUEST_ID),
                record = bundle.getBundle(Keys.RESULT)?.let(SourcePaperRecord::fromBundle),
            )
        }
    }
}

private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
private val DOI = Regex("10\\.\\d{4,9}/\\S+", RegexOption.IGNORE_CASE)
private val ARXIV_ID = Regex(
    "(?:\\d{4}\\.\\d{4,5}|[a-z][a-z0-9.-]*/\\d{7})(?:v\\d+)?",
    RegexOption.IGNORE_CASE,
)
