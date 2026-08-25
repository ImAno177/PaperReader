package dev.paperreader.extensions.api

import android.os.Bundle

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
