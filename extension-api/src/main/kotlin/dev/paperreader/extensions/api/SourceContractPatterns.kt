package dev.paperreader.extensions.api

internal val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
internal val DOI = Regex("10\\.\\d{4,9}/\\S+", RegexOption.IGNORE_CASE)
internal val ARXIV_ID = Regex(
    "(?:\\d{4}\\.\\d{4,5}|[a-z][a-z0-9.-]*/\\d{7})(?:v\\d+)?",
    RegexOption.IGNORE_CASE,
)
internal val PMID = Regex("[1-9]\\d{0,9}")
internal val PMCID = Regex("PMC[1-9]\\d{0,9}", RegexOption.IGNORE_CASE)
