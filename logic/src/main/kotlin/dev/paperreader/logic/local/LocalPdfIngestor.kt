package dev.paperreader.logic.local

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class StagedLocalPdf(
    val path: Path,
    val sha256: String,
    val byteLength: Long,
)

internal sealed class LocalPdfIngestException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TooLarge(val maximumBytes: Long) :
        LocalPdfIngestException("PDF exceeds $maximumBytes bytes")

    class InvalidPdf : LocalPdfIngestException("Input is not a PDF file")

    class StalledStream : LocalPdfIngestException("PDF source returned an empty read")
}

internal class LocalPdfIngestor(private val maximumBytes: Long) {
    init {
        require(maximumBytes > 0)
    }

    suspend fun stage(input: InputStream, stagingDirectory: Path): StagedLocalPdf =
        withContext(Dispatchers.IO) {
            Files.createDirectories(stagingDirectory)
            val temporary = Files.createTempFile(stagingDirectory, ".paper-import-", ".part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                Files.newOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) throw LocalPdfIngestException.StalledStream()
                        total += read
                        if (total > maximumBytes) throw LocalPdfIngestException.TooLarge(maximumBytes)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
                if (total <= 0 || !temporary.hasPdfMagic()) throw LocalPdfIngestException.InvalidPdf()
                StagedLocalPdf(
                    path = temporary,
                    sha256 = digest.digest().toHexString(),
                    byteLength = total,
                )
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(temporary) }
                throw error
            }
        }
}

internal fun Path.hasPdfMagic(): Boolean {
    return Files.newInputStream(this).hasPdfMagic()
}

internal fun InputStream.hasPdfMagic(): Boolean {
    val prefix = ByteArray(PDF_MAGIC.size)
    return use { input ->
        var offset = 0
        while (offset < prefix.size) {
            val read = input.read(prefix, offset, prefix.size - offset)
            if (read < 0) return@use false
            if (read == 0) throw IOException("File stream returned an empty read")
            offset += read
        }
        prefix.contentEquals(PDF_MAGIC)
    }
}

internal fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) throw IOException("File stream returned an empty read")
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
