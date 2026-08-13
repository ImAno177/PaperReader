package dev.paperreader.logic.domain

import java.security.MessageDigest

const val LOCAL_PDF_SOURCE_ID = "local-pdf"
const val MAX_LOCAL_PDF_TITLE_LENGTH = 300

data class LocalPdfCandidate(
    /** Opaque, app-private staging token. The source URI is never reopened after preparation. */
    val importToken: String,
    /** One-way correlation key used to consume a redelivered Android intent after process death. */
    val sourceKey: String,
    val displayName: String,
    val suggestedTitle: String,
    val byteLength: Long,
)

fun localPdfSourceKey(sourceUri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(sourceUri.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

enum class LocalPdfImportFailure {
    INVALID_URI,
    SOURCE_UNAVAILABLE,
    TOO_LARGE,
    INVALID_PDF,
    INVALID_TITLE,
    IO_FAILURE,
    COMMIT_FAILED,
}

sealed interface PrepareLocalPdfResult {
    data class Ready(val candidate: LocalPdfCandidate) : PrepareLocalPdfResult
    data class Rejected(val reason: LocalPdfImportFailure) : PrepareLocalPdfResult
}

data class ImportedLocalPdf(
    val workId: WorkId,
    val manifestationId: ManifestationId,
    val documentSha256: String,
    val byteLength: Long,
)

sealed interface LocalPdfImportResult {
    data class Imported(val document: ImportedLocalPdf) : LocalPdfImportResult
    data class AlreadyImported(val document: ImportedLocalPdf) : LocalPdfImportResult
    data class Rejected(val reason: LocalPdfImportFailure) : LocalPdfImportResult
}
