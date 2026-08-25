package dev.paperreader.logic.data

data class LocalDocumentAnchorRow(
    val manifestationId: String,
    val documentSha256: String,
)

data class LocalPdfOwnerRow(
    val workId: String,
    val manifestationId: String,
    val localPath: String,
    val documentSha256: String,
    val byteLength: Long,
)
