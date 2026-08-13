package dev.paperreader.logic.domain

import java.time.Instant

data class LocalPaperArtifact(
    val id: String,
    val manifestationId: ManifestationId,
    val storagePath: String,
    val sha256: String,
    val byteLength: Long,
    val mimeType: String,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank())
        require(storagePath.isNotBlank())
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(byteLength > 0)
        require(mimeType == "application/pdf")
    }
}
