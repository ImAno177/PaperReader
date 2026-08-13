package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult

interface MetadataBackupRepository {
    suspend fun export(): MetadataBackupExportResult
    suspend fun preview(bytes: ByteArray): MetadataRestorePreviewResult
    suspend fun restore(bytes: ByteArray): MetadataRestoreResult
}
