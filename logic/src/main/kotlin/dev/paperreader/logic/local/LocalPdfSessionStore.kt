package dev.paperreader.logic.local

import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.MAX_LOCAL_PDF_TITLE_LENGTH
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class PendingLocalPdfSession(
    val candidate: LocalPdfCandidate,
    val documentSha256: String,
    val path: Path,
)

/** Durable, single-user-flow staging. A source grant is no longer needed once [create] returns. */
internal class LocalPdfSessionStore(
    directory: Path,
    maximumBytes: Long,
) {
    private val root = directory.toAbsolutePath().normalize()
    private val maximumBytes = maximumBytes
    private val ingestor = LocalPdfIngestor(maximumBytes)
    private val json = Json { ignoreUnknownKeys = false }

    init {
        require(maximumBytes > 0)
        require(root.parent != null && root.fileName != null)
    }

    suspend fun create(
        input: InputStream,
        sourceKey: String,
        displayName: String,
        suggestedTitle: String,
    ): PendingLocalPdfSession = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val staged = ingestor.stage(input, root)
        val token = UUID.randomUUID().toString()
        val document = documentPath(token)
        val metadata = metadataPath(token)
        val metadataTemporary = root.resolve(".$token.json.part")
        val stored = StoredLocalPdfSession(
            formatVersion = SESSION_FORMAT_VERSION,
            importToken = token,
            sourceKey = sourceKey,
            displayName = displayName,
            suggestedTitle = suggestedTitle,
            documentSha256 = staged.sha256,
            byteLength = staged.byteLength,
        )
        try {
            moveReplacing(staged.path, document)
            val encoded = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
            check(encoded.size <= MAX_SESSION_METADATA_BYTES)
            FileOutputStream(metadataTemporary.toFile()).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            moveReplacing(metadataTemporary, metadata)
            cleanupExcept(token)
            stored.toPending(document)
        } catch (error: Throwable) {
            Files.deleteIfExists(staged.path)
            Files.deleteIfExists(document)
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(metadataTemporary)
            throw error
        }
    }

    suspend fun recoverPending(): PendingLocalPdfSession? = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@withContext null
        val tokens = Files.list(root).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> path.fileName.toString().endsWith(METADATA_SUFFIX) }
                .sortedByDescending(::modifiedAt)
                .map { path -> path.fileName.toString().removeSuffix(METADATA_SUFFIX) }
                .toList()
        }
        for (token in tokens) {
            loadInternal(token)?.let { pending ->
                cleanupExcept(token)
                return@withContext pending
            }
            discardInternal(token)
        }
        cleanupExcept(null)
        null
    }

    suspend fun load(importToken: String): PendingLocalPdfSession? = withContext(Dispatchers.IO) {
        val pending = loadInternal(importToken)
        if (pending == null && importToken.isValidToken()) discardInternal(importToken)
        pending
    }

    suspend fun discard(importToken: String) = withContext(Dispatchers.IO) {
        if (importToken.isValidToken()) discardInternal(importToken)
    }

    private fun loadInternal(importToken: String): PendingLocalPdfSession? {
        if (!importToken.isValidToken()) return null
        val metadata = metadataPath(importToken)
        val document = documentPath(importToken)
        return runCatching {
            if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(metadata) !in 1..MAX_SESSION_METADATA_BYTES.toLong()
            ) {
                return@runCatching null
            }
            val stored = json.decodeFromString<StoredLocalPdfSession>(
                Files.readAllBytes(metadata).toString(Charsets.UTF_8),
            )
            if (!stored.isValid(importToken)) return@runCatching null
            if (!Files.isRegularFile(document, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(document) != stored.byteLength ||
                !document.hasPdfMagic() ||
                document.sha256() != stored.documentSha256
            ) {
                return@runCatching null
            }
            stored.toPending(document)
        }.getOrNull()
    }

    private fun StoredLocalPdfSession.isValid(expectedToken: String): Boolean =
        formatVersion == SESSION_FORMAT_VERSION &&
            importToken == expectedToken &&
            importToken.isValidToken() &&
            sourceKey.matches(SHA_256) &&
            documentSha256.matches(SHA_256) &&
            byteLength in 1..maximumBytes &&
            displayName.isSafeText(MAX_DISPLAY_NAME_LENGTH) &&
            suggestedTitle.isSafeText(MAX_LOCAL_PDF_TITLE_LENGTH)

    private fun StoredLocalPdfSession.toPending(document: Path) = PendingLocalPdfSession(
        candidate = LocalPdfCandidate(
            importToken = importToken,
            sourceKey = sourceKey,
            displayName = displayName,
            suggestedTitle = suggestedTitle,
            byteLength = byteLength,
        ),
        documentSha256 = documentSha256,
        path = document,
    )

    private fun cleanupExcept(importToken: String?) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
        val keep = importToken?.let { setOf(documentPath(it), metadataPath(it)) }.orEmpty()
        Files.list(root).use { paths ->
            paths.filter { path -> path !in keep && path.isOwnedSessionArtifact() }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun discardInternal(importToken: String) {
        Files.deleteIfExists(metadataPath(importToken))
        Files.deleteIfExists(documentPath(importToken))
        Files.deleteIfExists(root.resolve(".$importToken.json.part"))
    }

    private fun Path.isOwnedSessionArtifact(): Boolean {
        val name = fileName.toString()
        return Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS) &&
            (name.endsWith(DOCUMENT_SUFFIX) ||
                name.endsWith(METADATA_SUFFIX) ||
                name.startsWith(".paper-import-") ||
                name.endsWith(".json.part"))
    }

    private fun documentPath(importToken: String) = root.resolve("$importToken$DOCUMENT_SUFFIX").normalize()
    private fun metadataPath(importToken: String) = root.resolve("$importToken$METADATA_SUFFIX").normalize()

    private fun modifiedAt(path: Path): Long = runCatching {
        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
    }.getOrDefault(Long.MIN_VALUE)

    private fun String.isValidToken(): Boolean = runCatching {
        UUID.fromString(this).toString() == this
    }.getOrDefault(false)

    private fun String.isSafeText(maximumLength: Int): Boolean =
        isNotBlank() && length <= maximumLength && trim() == this && none { it.isISOControl() }

    @Serializable
    private data class StoredLocalPdfSession(
        val formatVersion: Int,
        val importToken: String,
        val sourceKey: String,
        val displayName: String,
        val suggestedTitle: String,
        val documentSha256: String,
        val byteLength: Long,
    )

    private companion object {
        const val SESSION_FORMAT_VERSION = 1
        const val MAX_SESSION_METADATA_BYTES = 4_096
        const val MAX_DISPLAY_NAME_LENGTH = 240
        const val DOCUMENT_SUFFIX = ".pdf"
        const val METADATA_SUFFIX = ".json"
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

private fun moveReplacing(source: Path, destination: Path) {
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: IOException) {
        throw error
    }
}
