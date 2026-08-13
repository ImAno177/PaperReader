package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.LocalPaperArtifact
import dev.paperreader.logic.domain.ManifestationId

interface LocalFileRepository {
    suspend fun get(manifestationId: ManifestationId): LocalPaperArtifact?

    suspend fun upsert(artifact: LocalPaperArtifact)

    suspend fun remove(manifestationId: ManifestationId)
}
