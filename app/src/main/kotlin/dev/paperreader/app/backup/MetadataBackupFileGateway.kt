package dev.paperreader.app.backup

import android.content.ContentResolver
import android.net.Uri
import dev.paperreader.logic.backup.MAX_METADATA_BACKUP_ARCHIVE_BYTES
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MetadataBackupFileGateway(
    private val contentResolver: ContentResolver,
) {
    suspend fun read(uri: Uri, maximumBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("The selected backup could not be opened")
        input.use { readBounded(it, maximumBytes) }
    }

    suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val originalBytes = try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("The selected backup destination could not be inspected safely")
            input.use { readBounded(it, MAX_METADATA_BACKUP_ARCHIVE_BYTES) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // No output stream has been opened, so the selected document is still untouched.
            throw BackupDestinationWriteException(destinationRecovered = true, cause = failure)
        }
        writeWithRecovery(
            originalBytes = originalBytes,
            openOutput = {
                contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("The selected backup destination could not be opened")
            },
            bytes = bytes,
        )
    }
}

internal fun readBounded(input: InputStream, maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        if (read == 0) throw IOException("The selected backup returned an empty read")
        total += read
        if (total > maximumBytes) throw BackupFileTooLargeException(maximumBytes)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun writeFully(output: OutputStream, bytes: ByteArray) {
    output.write(bytes)
    output.flush()
}

internal fun writeWithRecovery(
    originalBytes: ByteArray,
    openOutput: () -> OutputStream,
    bytes: ByteArray,
) {
    try {
        openOutput().use { writeFully(it, bytes) }
    } catch (cancelled: CancellationException) {
        val recovered = recoverDestination(openOutput, originalBytes)
        if (!recovered) throw BackupDestinationRecoveryCancellationException(cancelled)
        throw cancelled
    } catch (failure: Exception) {
        throw BackupDestinationWriteException(
            destinationRecovered = recoverDestination(openOutput, originalBytes),
            cause = failure,
        )
    }
}

private fun recoverDestination(openOutput: () -> OutputStream, originalBytes: ByteArray): Boolean =
    runCatching { openOutput().use { writeFully(it, originalBytes) } }.isSuccess

internal class BackupFileTooLargeException(maximumBytes: Int) : IOException(
    "The selected backup exceeds the $maximumBytes byte limit",
)

internal class BackupDestinationWriteException(
    val destinationRecovered: Boolean,
    cause: Exception,
) : IOException(
    if (destinationRecovered) {
        "The backup could not be written; the previous destination contents were restored"
    } else {
        "The backup could not be written and the previous destination contents could not be restored"
    },
    cause,
)

internal class BackupDestinationRecoveryCancellationException(
    cause: CancellationException,
) : CancellationException(
    "The write was cancelled and the previous destination contents could not be restored",
) {
    init {
        initCause(cause)
    }
}
