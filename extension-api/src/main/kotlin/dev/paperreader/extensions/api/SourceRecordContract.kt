package dev.paperreader.extensions.api

import android.os.Bundle

data class SourcePaperRecord(
    val providerRecordId: String,
    val title: String,
    val abstractText: String? = null,
    val authors: List<String> = emptyList(),
    val subjects: Set<String> = emptySet(),
    val doi: String? = null,
    val arxivId: String? = null,
    val pmid: String? = null,
    val pmcid: String? = null,
    val citationCount: Int? = null,
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
        require(pmid == null || PMID.matches(pmid))
        require(pmcid == null || PMCID.matches(pmcid))
        require(citationCount == null || citationCount >= 0)
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
        putString(Keys.PMID, pmid)
        putString(Keys.PMCID, pmcid)
        citationCount?.let { putInt(Keys.CITATION_COUNT, it) }
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
            pmid = bundle.getString(Keys.PMID),
            pmcid = bundle.getString(Keys.PMCID),
            citationCount = bundle.optionalInt(Keys.CITATION_COUNT),
            publishedDate = bundle.getString(Keys.PUBLISHED_DATE),
            updatedAt = bundle.getString(Keys.UPDATED_AT),
            manifestations = bundle.bundleList(Keys.MANIFESTATIONS).map(SourceManifestation::fromBundle),
        )
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
