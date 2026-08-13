package dev.paperreader.app.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface MetadataRestoreSessionStore {
    suspend fun save(bytes: ByteArray)
    suspend fun load(): ByteArray?
    suspend fun clear()
}

/**
 * Keeps only the user-selected, bounded archive needed to survive process recreation between
 * preview and confirmation. The directory is app-private and excluded from Android Auto Backup.
 */
internal class FileMetadataRestoreSessionStore(
    private val directory: File,
    private val maximumBytes: Int,
) : MetadataRestoreSessionStore {
    private val archive = File(directory, ARCHIVE_NAME)
    private val temporary = File(directory, TEMPORARY_NAME)

    override suspend fun save(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.size > maximumBytes) throw BackupFileTooLargeException(maximumBytes)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("The restore session directory could not be created")
        }
        try {
            FileOutputStream(temporary).use { output ->
                writeFully(output, bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    archive.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
        Unit
    }

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        if (!archive.isFile) return@withContext null
        archive.inputStream().use { readBounded(it, maximumBytes) }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        val archiveRemoved = !archive.exists() || archive.delete()
        val temporaryRemoved = !temporary.exists() || temporary.delete()
        if (!archiveRemoved || !temporaryRemoved) throw IOException("The restore session could not be cleared")
        if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
    }

    private companion object {
        const val ARCHIVE_NAME = "pending-metadata-restore.backup"
        const val TEMPORARY_NAME = "pending-metadata-restore.tmp"
    }
}
