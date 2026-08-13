package dev.paperreader.extensions.api

import android.os.Bundle
import android.os.Parcel
import java.net.URI

object PaperExtensionContract {
    const val API_VERSION: Int = 1

    const val SOURCE_SERVICE_ACTION: String =
        "dev.paperreader.extensions.api.action.PAPER_SOURCE"
    const val THEME_SERVICE_ACTION: String =
        "dev.paperreader.extensions.api.action.PAPER_THEME"

    const val MAX_BINDER_PAYLOAD_BYTES: Int = 512 * 1024
    const val MAX_ICON_BYTES: Int = 64 * 1024
    const val ICON_VIEWPORT: Int = 2_400
    const val MAX_QUERY_CHARACTERS: Int = 512
    const val MAX_CURSOR_CHARACTERS: Int = 512
    const val MAX_RESULTS_PER_PAGE: Int = 50
    const val MAX_TITLE_CHARACTERS: Int = 512
    const val MAX_ABSTRACT_CHARACTERS: Int = 32 * 1024
    const val MAX_AUTHORS: Int = 100
    const val MAX_SUBJECTS: Int = 100
    const val MAX_REQUEST_ID_CHARACTERS: Int = 64

    const val META_API_VERSION: String =
        "dev.paperreader.extensions.API_VERSION"
    const val META_EXTENSION_KIND: String =
        "dev.paperreader.extensions.KIND"
    const val EXTENSION_KIND_SOURCE: String = "source"
    const val EXTENSION_KIND_THEME: String = "theme"
}

enum class ExtensionFailureCode(val wireValue: Int) {
    INVALID_REQUEST(1),
    UNAVAILABLE(2),
    RATE_LIMITED(3),
    INVALID_RESPONSE(4),
    CANCELLED(5),
    INTERNAL_ERROR(6),
    ;

    companion object {
        fun fromWireValue(value: Int): ExtensionFailureCode =
            entries.firstOrNull { it.wireValue == value } ?: INVALID_RESPONSE
    }
}

data class ExtensionFailure(
    val requestId: String,
    val code: ExtensionFailureCode,
    val message: String,
    val retryAfterMillis: Long? = null,
) {
    init {
        requireValidRequestId(requestId)
        require(message.isNotBlank() && message.length <= 512)
        require(retryAfterMillis == null || retryAfterMillis >= 0)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(Keys.REQUEST_ID, requestId)
        putInt(Keys.FAILURE_CODE, code.wireValue)
        putString(Keys.MESSAGE, message)
        retryAfterMillis?.let { putLong(Keys.RETRY_AFTER_MILLIS, it) }
    }

    companion object {
        fun fromBundle(bundle: Bundle): ExtensionFailure = ExtensionFailure(
            requestId = bundle.requiredString(Keys.REQUEST_ID),
            code = ExtensionFailureCode.fromWireValue(bundle.getInt(Keys.FAILURE_CODE, -1)),
            message = bundle.requiredString(Keys.MESSAGE),
            retryAfterMillis = bundle.optionalLong(Keys.RETRY_AFTER_MILLIS),
        )
    }
}

object ExtensionPayloadValidator {
    fun requireBinderSafe(bundle: Bundle, maximumBytes: Int = PaperExtensionContract.MAX_BINDER_PAYLOAD_BYTES) {
        require(maximumBytes in 1..PaperExtensionContract.MAX_BINDER_PAYLOAD_BYTES)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            require(parcel.dataSize() <= maximumBytes) {
                "Extension payload exceeds $maximumBytes bytes"
            }
        } finally {
            parcel.recycle()
        }
    }

    fun requireSafeWebUrl(value: String?) {
        if (value == null) return
        require(value.length <= 2_048)
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
            "URL must use HTTP(S) with a host"
        }
    }
}

internal object Keys {
    const val REQUEST_ID = "request_id"
    const val API_VERSION = "api_version"
    const val PACKAGE_NAME = "package_name"
    const val DISPLAY_NAME = "display_name"
    const val PROVIDER_ID = "provider_id"
    const val MINIMUM_REQUEST_INTERVAL = "minimum_request_interval"
    const val CAPABILITIES = "capabilities"
    const val QUERY = "query"
    const val LIMIT = "limit"
    const val CURSOR = "cursor"
    const val SORT = "sort"
    const val RECORD_ID = "record_id"
    const val RESULTS = "results"
    const val RESULT = "result"
    const val NEXT_CURSOR = "next_cursor"
    const val TITLE = "title"
    const val ABSTRACT = "abstract"
    const val AUTHORS = "authors"
    const val SUBJECTS = "subjects"
    const val DOI = "doi"
    const val ARXIV_ID = "arxiv_id"
    const val PMID = "pmid"
    const val PMCID = "pmcid"
    const val CITATION_COUNT = "citation_count"
    const val PUBLISHED_DATE = "published_date"
    const val UPDATED_AT = "updated_at"
    const val ROLES = "roles"
    const val IDENTIFIER_TYPES = "identifier_types"
    const val SUPPORTED_SORTS = "supported_sorts"
    const val MANIFESTATIONS = "manifestations"
    const val TYPE = "type"
    const val VERSION = "version"
    const val LANDING_PAGE_URL = "landing_page_url"
    const val PDF_URL = "pdf_url"
    const val LICENSE = "license"
    const val FAILURE_CODE = "failure_code"
    const val MESSAGE = "message"
    const val RETRY_AFTER_MILLIS = "retry_after_millis"
    const val THEMES = "themes"
    const val THEME_ID = "theme_id"
    const val LIGHT_PALETTE = "light_palette"
    const val DARK_PALETTE = "dark_palette"
    const val CORNER_RADIUS_DP = "corner_radius_dp"
    const val BORDER_WIDTH_DP = "border_width_dp"
    const val SHADOW_OFFSET_DP = "shadow_offset_dp"
    const val TITLE_FONT = "title_font"
    const val BODY_FONT = "body_font"
    const val LABEL_FONT = "label_font"
    const val DECORATION = "decoration"
    const val ICON_KEYS = "icon_keys"
}

internal fun requireValidRequestId(value: String) {
    require(value.isNotBlank() && value.length <= PaperExtensionContract.MAX_REQUEST_ID_CHARACTERS)
    require(value.all { it.isLetterOrDigit() || it in "-_.:" })
}

internal fun Bundle.requiredString(key: String): String =
    requireNotNull(getString(key)) { "Missing extension field: $key" }

internal fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null

internal fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

@Suppress("DEPRECATION")
internal fun Bundle.bundleList(key: String): List<Bundle> =
    getParcelableArrayList<Bundle>(key)?.toList().orEmpty()
