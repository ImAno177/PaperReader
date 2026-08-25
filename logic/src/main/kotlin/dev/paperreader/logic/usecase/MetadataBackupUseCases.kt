package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.repository.MetadataBackupRepository
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult

class CreateMetadataBackup(private val repository: MetadataBackupRepository) {
    suspend fun await(): MetadataBackupExportResult = repository.export()
}

class PreviewMetadataRestore(private val repository: MetadataBackupRepository) {
    suspend fun await(bytes: ByteArray): MetadataRestorePreviewResult = repository.preview(bytes)
}

class RestoreMetadataBackup(private val repository: MetadataBackupRepository) {
    suspend fun await(bytes: ByteArray): MetadataRestoreResult = repository.restore(bytes)
}
