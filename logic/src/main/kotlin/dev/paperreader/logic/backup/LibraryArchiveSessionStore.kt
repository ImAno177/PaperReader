package dev.paperreader.logic.backup

import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LibraryArchiveSessionStore(
    directory: Path,
    private val clock: Clock,
    private val maximumStoredBytes: Long = 2 * MAX_ARCHIVE_BYTES,
) {
    private val root = directory.toAbsolutePath().normalize()

    suspend fun create(kind: String, write: suspend (OutputStream) -> Unit): String {
        require(kind == "export" || kind == "restore")
        Files.createDirectories(root)
        cleanupExpired()
        val storedBytes = Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(OWNED_NAME) }
                .mapToLong(Files::size).sum()
        }
        val available = minOf(MAX_ARCHIVE_BYTES, maximumStoredBytes - storedBytes)
        if (available <= 0) throw LibraryArchiveException(LibraryArchiveFailure.LIMIT_EXCEEDED)
        val token = UUID.randomUUID().toString()
        val temporary = root.resolve("$token.$kind.part")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                var written = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) {
                        if (written >= available) throw LibraryArchiveException(LibraryArchiveFailure.LIMIT_EXCEEDED)
                        output.write(value)
                        written++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length.toLong() > available - written) throw LibraryArchiveException(LibraryArchiveFailure.LIMIT_EXCEEDED)
                        output.write(bytes, offset, length)
                        written += length
                    }
                }
                write(bounded)
                output.fd.sync()
            }
            require(Files.size(temporary) in 1..MAX_ARCHIVE_BYTES)
            Files.move(temporary, path(token, kind), ATOMIC_MOVE)
            return token
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun stage(input: InputStream): String = coroutineScope {
        val closeSource = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { runCatching { input.close() } }
        }
        try {
            create("restore") { output -> copyBounded(input, output) }
        } finally {
            closeSource.cancelAndJoin()
        }
    }

    fun load(token: String, kind: String): Path {
        val file = path(token, kind)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) !in 1..MAX_ARCHIVE_BYTES ||
            clock.millis() - Files.getLastModifiedTime(file).toMillis() > MAX_AGE_MILLIS
        ) throw LibraryArchiveException(LibraryArchiveFailure.FILE_UNAVAILABLE)
        return file
    }

    fun pendingRestore(): String? {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
        cleanupExpired()
        return Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(RESTORE_NAME) }
                .max(compareBy { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)?.fileName?.toString()?.removeSuffix(".restore.zip")
        }
    }

    fun discard(token: String) {
        if (!token.matches(TOKEN)) return
        Files.deleteIfExists(path(token, "export"))
        Files.deleteIfExists(path(token, "restore"))
    }

    fun temporaryDirectory(): Path {
        Files.createDirectories(root)
        return Files.createDirectory(root.resolve("unpack-${UUID.randomUUID()}"))
    }

    fun removeTemporaryDirectory(directory: Path) {
        require(directory.parent == root && directory.fileName.toString().startsWith("unpack-"))
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    fun recoverTemporaryDirectories() {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
        Files.list(root).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(UNPACK_NAME) }
                .forEach(::removeTemporaryDirectory)
        }
        cleanupExpired()
    }

    private fun path(token: String, kind: String): Path {
        require(token.matches(TOKEN))
        require(kind == "export" || kind == "restore")
        return root.resolve("$token.$kind.zip")
    }

    private fun cleanupExpired() {
        Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().matches(OWNED_NAME) }
                .filter { clock.millis() - Files.getLastModifiedTime(it).toMillis() > MAX_AGE_MILLIS }
                .forEach(Files::deleteIfExists)
        }
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val UNPACK_NAME = Regex("unpack-(?:${TOKEN.pattern}|[0-9]+)")
        private val RESTORE_NAME = Regex("${TOKEN.pattern}\\.restore\\.zip")
        private val OWNED_NAME = Regex("${TOKEN.pattern}\\.(?:export|restore)\\.(?:zip|part)")

        suspend fun copyBounded(input: InputStream, output: OutputStream) {
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "Archive stream stalled" }
                total += count
                if (total > MAX_ARCHIVE_BYTES) throw LibraryArchiveException(LibraryArchiveFailure.LIMIT_EXCEEDED)
                output.write(buffer, 0, count)
            }
        }
    }
}
