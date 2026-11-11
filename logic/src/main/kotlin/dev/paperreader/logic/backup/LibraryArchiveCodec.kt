package dev.paperreader.logic.backup

import dev.paperreader.logic.local.hasPdfMagic
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Enumeration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class DecodedLibraryArchive(
    val manifest: LibraryArchiveManifest,
    val metadata: ByteArray,
    val documents: Map<ArchiveDocument, Path>,
)

internal object LibraryArchiveCodec {
    private const val MANIFEST_ENTRY = "library.json"
    private const val METADATA_ENTRY = "metadata.backup"
    private const val DOCUMENTS_DIRECTORY = "documents"
    private const val MAX_PDFS = 1_000
    private const val MAX_PDF_BYTES = 200L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 512L * 1024
    private const val MAX_METADATA_BYTES = 10L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    private val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val manifestKeys = setOf("formatMarker", "version", "createdAtEpochMillis", "documents")
    private val documentKeys = setOf("workSourceId", "manifestationSourceId", "sha256", "byteLength", "entryName")
    private val json = Json { encodeDefaults = true }

    suspend fun write(
        metadata: ByteArray,
        manifest: LibraryArchiveManifest,
        output: OutputStream,
        openDocument: (ArchiveDocument) -> InputStream,
    ) = withContext(Dispatchers.IO) {
        validateManifest(manifest, LibraryArchiveFailure.EXPORT_FAILED)
        if (metadata.isEmpty()) archiveFailure(LibraryArchiveFailure.EXPORT_FAILED)
        if (metadata.size.toLong() > MAX_METADATA_BYTES) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)

        val manifestBytes = try {
            json.encodeToString(LibraryArchiveManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8)
        } catch (error: SerializationException) {
            throw LibraryArchiveException(LibraryArchiveFailure.EXPORT_FAILED)
        }
        if (manifestBytes.size.toLong() > MAX_MANIFEST_BYTES) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
        var expandedBytes = checkedAdd(manifestBytes.size.toLong(), metadata.size.toLong(), LibraryArchiveFailure.LIMIT_EXCEEDED)
        for (document in manifest.documents) {
            expandedBytes = checkedAdd(expandedBytes, document.byteLength, LibraryArchiveFailure.LIMIT_EXCEEDED)
        }
        if (expandedBytes > MAX_ARCHIVE_BYTES) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)

        ZipOutputStream(NonClosingOutputStream(CappedOutputStream(output, MAX_ARCHIVE_BYTES))).use { zip ->
            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestBytes)
            zip.closeEntry()

            currentCoroutineContext().ensureActive()
            zip.putNextEntry(ZipEntry(METADATA_ENTRY))
            zip.write(metadata)
            zip.closeEntry()

            for (document in manifest.documents) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(document.entryName))
                openDocument(document).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val prefix = ByteArray(PDF_MAGIC.size)
                    var prefixLength = 0
                    var total = 0L
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("Document stream stalled")
                        if (total > document.byteLength - count) archiveFailure(LibraryArchiveFailure.EXPORT_FAILED)
                        total += count
                        digest.update(buffer, 0, count)
                        if (prefixLength < prefix.size) {
                            val copyLength = minOf(prefix.size - prefixLength, count)
                            buffer.copyInto(prefix, prefixLength, 0, copyLength)
                            prefixLength += copyLength
                        }
                        zip.write(buffer, 0, count)
                    }
                    if (total != document.byteLength || prefixLength != prefix.size ||
                        !prefix.contentEquals(PDF_MAGIC) || digest.digest().toHexString() != document.sha256
                    ) {
                        archiveFailure(LibraryArchiveFailure.EXPORT_FAILED)
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
    }

    suspend fun read(input: InputStream, destination: Path): DecodedLibraryArchive = withContext(Dispatchers.IO) {
        val root = validateDestination(destination)
        var temporaryArchive: Path? = null
        try {
            val archiveFile = Files.createTempFile(root, ".library-archive-", ".zip")
            temporaryArchive = archiveFile
            Files.newOutputStream(archiveFile, WRITE).use { output -> copyInputBounded(input, output) }
            if (Files.size(archiveFile) > MAX_ARCHIVE_BYTES) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
            extractArchive(archiveFile, root)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LibraryArchiveException) {
            throw error
        } catch (_: Exception) {
            throw LibraryArchiveException(LibraryArchiveFailure.INVALID_ARCHIVE)
        } finally {
            temporaryArchive?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    suspend fun read(archive: Path, destination: Path): DecodedLibraryArchive = withContext(Dispatchers.IO) {
        try {
            val archiveFile = archive.toAbsolutePath().normalize()
            if (!Files.isRegularFile(archiveFile, NOFOLLOW_LINKS)) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            val archiveSize = Files.size(archiveFile)
            if (archiveSize !in 1..MAX_ARCHIVE_BYTES) {
                archiveFailure(if (archiveSize > MAX_ARCHIVE_BYTES) LibraryArchiveFailure.LIMIT_EXCEEDED
                    else LibraryArchiveFailure.INVALID_ARCHIVE)
            }
            extractArchive(archiveFile, validateDestination(destination))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LibraryArchiveException) {
            throw error
        } catch (_: Exception) {
            throw LibraryArchiveException(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
    }

    private suspend fun extractArchive(archiveFile: Path, root: Path): DecodedLibraryArchive {
        val extractedFiles = mutableListOf<Path>()
        var documentsDirectory: Path? = null
        var completed = false
        return try {
            val decoded = ZipFile(archiveFile.toFile()).use { zip ->
                val entries = readCentralEntries(zip)
                val manifestEntry = entries.firstOrNull() ?: archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                val metadataEntry = entries.getOrNull(1) ?: archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                if (manifestEntry.name != MANIFEST_ENTRY || metadataEntry.name != METADATA_ENTRY) {
                    archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                }
                if (manifestEntry.size !in 1..MAX_MANIFEST_BYTES || metadataEntry.size !in 1..MAX_METADATA_BYTES) {
                    archiveFailure(if (manifestEntry.size > MAX_MANIFEST_BYTES || metadataEntry.size > MAX_METADATA_BYTES)
                        LibraryArchiveFailure.LIMIT_EXCEEDED else LibraryArchiveFailure.INVALID_ARCHIVE)
                }

                val manifestBytes = readEntryBytes(zip, manifestEntry, MAX_MANIFEST_BYTES)
                val manifest = decodeManifest(manifestBytes)
                validateManifest(manifest, LibraryArchiveFailure.INVALID_ARCHIVE)
                if (entries.size != manifest.documents.size + 2) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                manifest.documents.forEachIndexed { index, document ->
                    val entry = entries[index + 2]
                    if (entry.name != document.entryName || entry.size != document.byteLength) {
                        archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                    }
                }

                var expandedBytes = checkedAdd(manifestEntry.size, metadataEntry.size, LibraryArchiveFailure.LIMIT_EXCEEDED)
                for (document in manifest.documents) {
                    expandedBytes = checkedAdd(expandedBytes, document.byteLength, LibraryArchiveFailure.LIMIT_EXCEEDED)
                }
                if (expandedBytes > MAX_ARCHIVE_BYTES) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
                val metadata = readEntryBytes(zip, metadataEntry, MAX_METADATA_BYTES)

                val directory = root.resolve(DOCUMENTS_DIRECTORY).normalize()
                if (!directory.startsWith(root) || directory == root) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                Files.createDirectory(directory)
                documentsDirectory = directory
                val documents = linkedMapOf<ArchiveDocument, Path>()
                for ((index, document) in manifest.documents.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries[index + 2]
                    val relative = Paths.get(document.entryName)
                    val target = root.resolve(relative).normalize()
                    if (relative.isAbsolute || target == root || !target.startsWith(root) || target.parent != directory) {
                        archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                    }
                    extractedFiles.add(target)
                    Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                        copyEntry(zip, entry, output, document.byteLength, digestDocuments = true)
                    }
                    if (!target.hasPdfMagic()) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                    documents[document] = target
                }
                DecodedLibraryArchive(manifest, metadata, documents)
            }
            completed = true
            decoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LibraryArchiveException) {
            throw error
        } catch (_: Exception) {
            throw LibraryArchiveException(LibraryArchiveFailure.INVALID_ARCHIVE)
        } finally {
            if (!completed) {
                extractedFiles.forEach { runCatching { Files.deleteIfExists(it) } }
                documentsDirectory?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun validateDestination(destination: Path): Path {
        val root = destination.toAbsolutePath().normalize()
        if (root != destination.toAbsolutePath() || !Files.isDirectory(root, NOFOLLOW_LINKS) || !isEmpty(root)) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
        return root
    }

    private suspend fun copyInputBounded(input: InputStream, output: OutputStream) {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Archive stream stalled")
            if (total > MAX_ARCHIVE_BYTES - count) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
            total += count
            output.write(buffer, 0, count)
        }
        if (total == 0L) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
    }

    private fun readCentralEntries(zip: ZipFile): List<ZipEntry> {
        if (zip.size() < 2) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        if (zip.size() > MAX_PDFS + 2) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
        val names = mutableSetOf<String>()
        val entries = mutableListOf<ZipEntry>()
        var compressedBytes = 0L
        val enumeration: Enumeration<out ZipEntry> = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (!isSafeEntryName(entry.name) || entry.isDirectory || !names.add(entry.name)) {
                archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            }
            if (entry.size < 0 || entry.compressedSize < 0) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            if (entry.method != ZipEntry.DEFLATED && entry.method != ZipEntry.STORED) {
                archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            }
            compressedBytes = checkedAdd(compressedBytes, entry.compressedSize, LibraryArchiveFailure.LIMIT_EXCEEDED)
            entries += entry
        }
        if (entries.size != zip.size()) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        return entries
    }

    private suspend fun readEntryBytes(zip: ZipFile, entry: ZipEntry, maximumBytes: Long): ByteArray {
        if (entry.size !in 1..maximumBytes) {
            archiveFailure(if (entry.size > maximumBytes) LibraryArchiveFailure.LIMIT_EXCEEDED
                else LibraryArchiveFailure.INVALID_ARCHIVE)
        }
        val output = ByteArrayOutputStream(entry.size.toInt())
        copyEntry(zip, entry, output, entry.size, digestDocuments = false)
        return output.toByteArray()
    }

    private suspend fun copyEntry(
        zip: ZipFile,
        entry: ZipEntry,
        output: OutputStream,
        expectedBytes: Long,
        digestDocuments: Boolean,
    ) {
        val digest = if (digestDocuments) MessageDigest.getInstance("SHA-256") else null
        val crc = CRC32()
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        val input = zip.getInputStream(entry) ?: archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        input.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                if (total > expectedBytes - count) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
                total += count
                crc.update(buffer, 0, count)
                digest?.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        if (total != expectedBytes || total != entry.size || crc.value != entry.crc) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
        if (digest != null && digest.digest().toHexString() != entry.name.documentSha256()) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
    }

    private fun decodeManifest(bytes: ByteArray): LibraryArchiveManifest {
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
        return try {
            val element = json.parseToJsonElement(source) as? JsonObject
                ?: archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            if (element.keys != manifestKeys) archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            val documents = element["documents"] as? JsonArray ?: archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            if (documents.any { (it as? JsonObject)?.keys != documentKeys }) {
                archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
            }
            json.decodeFromString(LibraryArchiveManifest.serializer(), source)
        } catch (error: LibraryArchiveException) {
            throw error
        } catch (_: SerializationException) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        } catch (_: IllegalArgumentException) {
            archiveFailure(LibraryArchiveFailure.INVALID_ARCHIVE)
        }
    }

    private fun validateManifest(manifest: LibraryArchiveManifest, failure: LibraryArchiveFailure) {
        if (manifest.formatMarker != "paperreader-library" || manifest.version != 1 ||
            manifest.documents.size > MAX_PDFS
        ) archiveFailure(if (manifest.documents.size > MAX_PDFS) LibraryArchiveFailure.LIMIT_EXCEEDED else failure)
        manifest.documents.forEachIndexed { index, document ->
            val expectedName = "$DOCUMENTS_DIRECTORY/$index-${document.sha256}.pdf"
            if (!sha256Pattern.matches(document.sha256) || document.byteLength !in 1..MAX_PDF_BYTES ||
                document.entryName != expectedName || !isSafeEntryName(document.entryName)
            ) {
                archiveFailure(if (document.byteLength > MAX_PDF_BYTES) LibraryArchiveFailure.LIMIT_EXCEEDED else failure)
            }
        }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains('\u0000')) return false
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        return runCatching {
            val path = Paths.get(name)
            !path.isAbsolute && path.normalize().toString().replace('\\', '/') == name
        }.getOrDefault(false)
    }

    private fun String.documentSha256(): String =
        substringAfterLast('/').removeSuffix(".pdf").substringAfter('-')

    private fun checkedAdd(left: Long, right: Long, failure: LibraryArchiveFailure): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) archiveFailure(failure)
        val result = left + right
        if (result > MAX_ARCHIVE_BYTES) archiveFailure(failure)
        return result
    }

    private fun isEmpty(directory: Path): Boolean = Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }

    private fun archiveFailure(failure: LibraryArchiveFailure): Nothing = throw LibraryArchiveException(failure)

    private fun ByteArray.toHexString(): String = joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "0123456789abcdef"[value ushr 4].toString() + "0123456789abcdef"[value and 0x0f]
    }

    private class CappedOutputStream(output: OutputStream, private val maximumBytes: Long) : FilterOutputStream(output) {
        private var total = 0L

        override fun write(value: Int) {
            if (total >= maximumBytes) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
            out.write(value)
            total++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length < 0 || total > maximumBytes - length) archiveFailure(LibraryArchiveFailure.LIMIT_EXCEEDED)
            out.write(buffer, offset, length)
            total += length
        }
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)

        override fun close() = flush()
    }
}
