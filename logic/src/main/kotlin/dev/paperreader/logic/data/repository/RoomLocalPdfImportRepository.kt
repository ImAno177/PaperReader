package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.LocalPdfOwnerRow
import dev.paperreader.logic.data.ManifestationEntity
import dev.paperreader.logic.data.WorkEntity
import dev.paperreader.logic.domain.ImportedLocalPdf
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.MAX_LOCAL_PDF_TITLE_LENGTH
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LocalPdfImportRepository
import dev.paperreader.logic.domain.localPdfSourceKey
import dev.paperreader.logic.local.LocalPdfIngestException
import dev.paperreader.logic.local.LocalPdfSessionStore
import dev.paperreader.logic.local.LocalPdfSourceException
import dev.paperreader.logic.local.LocalPdfSourceResolver
import dev.paperreader.logic.local.sha256
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RoomLocalPdfImportRepository(
    private val database: LibraryDatabase,
    private val sourceResolver: LocalPdfSourceResolver,
    filesDirectory: Path,
    sessionDirectory: Path,
    maximumBytes: Long,
    private val clock: Clock = Clock.systemUTC(),
) : LocalPdfImportRepository {
    private val dao = database.libraryDao()
    private val filesRoot = filesDirectory.toAbsolutePath().normalize()
    private val papersRoot = filesRoot.resolve(PAPERS_DIRECTORY).normalize()
    private val sessionStore = LocalPdfSessionStore(sessionDirectory, maximumBytes)
    private val maximumBytes = maximumBytes
    private val importMutex = Mutex()
    private var publishedStoreChecked = false

    init {
        require(maximumBytes > 0)
        require(papersRoot.startsWith(filesRoot))
    }

    override suspend fun prepare(sourceUri: String): PrepareLocalPdfResult = importMutex.withLock {
        try {
            ensurePublishedStoreIntegrity()
            val metadata = withContext(Dispatchers.IO) { sourceResolver.inspect(sourceUri) }
            if (metadata.declaredByteLength != null && metadata.declaredByteLength > maximumBytes) {
                return@withLock PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.TOO_LARGE)
            }
            val displayName = safeLocalPdfDisplayName(metadata.displayName)
            val pending = withContext(Dispatchers.IO) {
                sourceResolver.open(sourceUri).use { input ->
                    sessionStore.create(
                        input = input,
                        sourceKey = localPdfSourceKey(sourceUri),
                        displayName = displayName,
                        suggestedTitle = suggestedLocalPdfTitle(displayName),
                    )
                }
            }
            PrepareLocalPdfResult.Ready(pending.candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LocalPdfSourceException.InvalidUri) {
            PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.INVALID_URI)
        } catch (_: LocalPdfSourceException.Unavailable) {
            PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.SOURCE_UNAVAILABLE)
        } catch (_: LocalPdfIngestException.TooLarge) {
            PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.TOO_LARGE)
        } catch (_: LocalPdfIngestException.InvalidPdf) {
            PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.INVALID_PDF)
        } catch (_: IOException) {
            PrepareLocalPdfResult.Rejected(LocalPdfImportFailure.IO_FAILURE)
        }
    }

    override suspend fun recoverPending(): LocalPdfCandidate? = importMutex.withLock {
        ensurePublishedStoreIntegrity()
        sessionStore.recoverPending()?.candidate
    }

    override suspend fun discard(importToken: String) = importMutex.withLock {
        sessionStore.discard(importToken)
    }

    override suspend fun import(importToken: String, title: String): LocalPdfImportResult = importMutex.withLock {
        ensurePublishedStoreIntegrity()
        val normalizedTitle = normalizeLocalPdfTitle(title)
            ?: return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.INVALID_TITLE)
        val pending = sessionStore.load(importToken)
            ?: return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.SOURCE_UNAVAILABLE)
        var discardSession = false
        var databaseCommitted = false
        var publishedByThisImport = false
        var verifiedTemporary: Path? = null
        val manifestationId = ManifestationId("m-local-${pending.documentSha256}")
        val destination = papersRoot.resolve("local").resolve("${pending.documentSha256}.pdf").normalize()
        check(destination.startsWith(papersRoot))
        val relativePath = filesRoot.relativize(destination).toString().replace('\\', '/')

        try {
            val existing = try {
                findExistingLocalDocument(pending.documentSha256)
            } catch (_: LocalPdfIdentityConflict) {
                discardSession = true
                return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.COMMIT_FAILED)
            }
            if (existing != null) {
                discardSession = true
                return@withLock LocalPdfImportResult.AlreadyImported(existing)
            }

            val workId = WorkId("w-local-${pending.documentSha256}")
            val destinationAlreadyValid = withContext(Dispatchers.IO) {
                Files.isRegularFile(destination) &&
                    Files.size(destination) == pending.candidate.byteLength &&
                    destination.sha256() == pending.documentSha256
            }
            if (!destinationAlreadyValid) {
                try {
                    verifiedTemporary = createVerifiedCopy(
                        source = pending.path,
                        destinationDirectory = checkNotNull(destination.parent),
                        expectedSha256 = pending.documentSha256,
                        expectedByteLength = pending.candidate.byteLength,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.IO_FAILURE)
                }
            }

            try {
                val now = clock.instant().toEpochMilli()
                withContext(NonCancellable + Dispatchers.IO) {
                    verifiedTemporary?.let { temporary ->
                        moveAtomically(temporary, destination)
                        verifiedTemporary = null
                        publishedByThisImport = true
                    }
                    database.withTransaction {
                        val sourceIdentityOwners = dao.getManifestationsBySourceIdentity(
                            LOCAL_PDF_SOURCE_ID,
                            pending.documentSha256,
                        )
                        if (sourceIdentityOwners.any { owner ->
                                owner.id != manifestationId.value || owner.workId != workId.value
                            }
                        ) {
                            throw LocalPdfIdentityConflict()
                        }
                        val existingWork = dao.getWork(workId.value)
                        val existingManifestation = dao.getManifestations(workId.value)
                            .firstOrNull { it.id == manifestationId.value }
                        if (existingWork != null && existingManifestation == null) {
                            throw LocalPdfIdentityConflict()
                        }
                        if (existingManifestation != null && (
                                existingManifestation.sourceProvider != LOCAL_PDF_SOURCE_ID ||
                                    !existingManifestation.sourceRecordId.equals(
                                        pending.documentSha256,
                                        ignoreCase = true,
                                    )
                                )
                        ) {
                            throw LocalPdfIdentityConflict()
                        }
                        if (existingWork == null) {
                            dao.upsertWork(
                                WorkEntity(
                                    id = workId.value,
                                    title = normalizedTitle,
                                    abstractText = null,
                                    subjects = "",
                                    publishedDateEpochDay = null,
                                    createdAtEpochMillis = now,
                                    updatedAtEpochMillis = now,
                                ),
                            )
                        }
                        if (existingManifestation == null) {
                            dao.upsertManifestations(
                                listOf(
                                    ManifestationEntity(
                                        id = manifestationId.value,
                                        workId = workId.value,
                                        type = ManifestationType.OTHER.name,
                                        sourceProvider = LOCAL_PDF_SOURCE_ID,
                                        sourceRecordId = pending.documentSha256,
                                        version = null,
                                        landingPageUrl = null,
                                        pdfUrl = null,
                                        license = null,
                                        publishedDateEpochDay = null,
                                        updatedAtEpochMillis = now,
                                    ),
                                ),
                            )
                        }
                        dao.upsertFile(
                            FileEntity(
                                id = "file-local-${pending.documentSha256}",
                                manifestationId = manifestationId.value,
                                localPath = relativePath,
                                sha256 = pending.documentSha256,
                                byteLength = pending.candidate.byteLength,
                                mimeType = PDF_MIME_TYPE,
                                extractionStatus = "NOT_STARTED",
                                extractionManifestPath = null,
                                updatedAtEpochMillis = now,
                            ),
                        )
                    }
                    databaseCommitted = true
                }
            } catch (cancelled: CancellationException) {
                databaseCommitted = withContext(NonCancellable) {
                    dao.getFile(manifestationId.value)?.let { file ->
                        file.sha256.equals(pending.documentSha256, ignoreCase = true) &&
                            file.localPath == relativePath &&
                            file.byteLength == pending.candidate.byteLength
                    } == true
                }
                throw cancelled
            } catch (_: LocalPdfIdentityConflict) {
                if (publishedByThisImport) deleteNonCancellable(destination)
                discardSession = true
                return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.COMMIT_FAILED)
            } catch (_: Exception) {
                if (publishedByThisImport) deleteNonCancellable(destination)
                discardSession = true
                return@withLock LocalPdfImportResult.Rejected(LocalPdfImportFailure.COMMIT_FAILED)
            }

            LocalPdfImportResult.Imported(
                ImportedLocalPdf(
                    workId = workId,
                    manifestationId = manifestationId,
                    documentSha256 = pending.documentSha256,
                    byteLength = pending.candidate.byteLength,
                ),
            ).also { discardSession = true }
        } catch (cancelled: CancellationException) {
            discardSession = databaseCommitted
            if (publishedByThisImport && !databaseCommitted) deleteNonCancellable(destination)
            throw cancelled
        } finally {
            verifiedTemporary?.let { temporary -> deleteNonCancellable(temporary) }
            if (discardSession) {
                runCatching { withContext(NonCancellable) { sessionStore.discard(importToken) } }
            }
        }
    }

    private suspend fun findExistingLocalDocument(documentSha256: String): ImportedLocalPdf? {
        val owners = dao.getLocalPdfOwners(documentSha256, LOCAL_PDF_SOURCE_ID)
        if (owners.size > 1) throw LocalPdfIdentityConflict()
        val row = owners.singleOrNull() ?: return null
        if (
            row.workId != "w-local-$documentSha256" ||
            row.manifestationId != "m-local-$documentSha256"
        ) {
            throw LocalPdfIdentityConflict()
        }
        val path = resolveStoredPath(row.localPath)
        val valid = path != null && withContext(Dispatchers.IO) {
            runCatching {
                Files.isRegularFile(path) &&
                    Files.size(path) == row.byteLength &&
                    path.sha256() == documentSha256
            }.getOrDefault(false)
        }
        if (valid) return row.toImportedLocalPdf()
        dao.deleteFile(row.manifestationId)
        if (path != null) runCatching { withContext(Dispatchers.IO) { Files.deleteIfExists(path) } }
        return null
    }

    /** Removes only unpublished files left by process death; any Room-owned path is preserved. */
    private suspend fun ensurePublishedStoreIntegrity() {
        if (publishedStoreChecked) return
        val localRoot = papersRoot.resolve("local")
        val candidates = withContext(Dispatchers.IO) {
            if (!Files.isDirectory(localRoot, LinkOption.NOFOLLOW_LINKS)) return@withContext emptyList()
            Files.list(localRoot).use { paths ->
                paths.iterator().asSequence()
                    .filter { path ->
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                            (path.fileName.toString().matches(LOCAL_PDF_FILE_NAME) ||
                                path.fileName.toString().matches(LOCAL_PDF_TEMPORARY_NAME))
                    }
                    .toList()
            }
        }
        candidates.forEach { path ->
            val name = path.fileName.toString()
            val relativePath = filesRoot.relativize(path).toString().replace('\\', '/')
            val owned = !name.matches(LOCAL_PDF_TEMPORARY_NAME) &&
                dao.getFileByLocalPath(relativePath) != null
            if (!owned) withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
        }
        publishedStoreChecked = true
    }

    private suspend fun createVerifiedCopy(
        source: Path,
        destinationDirectory: Path,
        expectedSha256: String,
        expectedByteLength: Long,
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(destinationDirectory)
        val temporary = Files.createTempFile(destinationDirectory, ".local-pdf-", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newInputStream(source).buffered().use { input ->
                FileOutputStream(temporary.toFile()).use { fileOutput ->
                    val output = fileOutput.buffered()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) throw IOException("Staged PDF returned an empty read")
                        total += read
                        if (total > expectedByteLength) throw IOException("Staged PDF size changed")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            if (total != expectedByteLength || digest.digest().toHexString() != expectedSha256) {
                throw IOException("Staged PDF identity changed")
            }
            temporary
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temporary) }
            throw error
        }
    }

    private fun resolveStoredPath(storagePath: String): Path? = runCatching {
        val relative = Paths.get(storagePath)
        if (relative.isAbsolute) return null
        filesRoot.resolve(relative).normalize().takeIf { it.startsWith(filesRoot) }
    }.getOrNull()

    private fun LocalPdfOwnerRow.toImportedLocalPdf() = ImportedLocalPdf(
        workId = WorkId(workId),
        manifestationId = ManifestationId(manifestationId),
        documentSha256 = documentSha256,
        byteLength = byteLength,
    )

    private suspend fun deleteNonCancellable(path: Path) {
        withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(path) }
    }

    private class LocalPdfIdentityConflict : IllegalStateException("Local PDF identity belongs to incompatible data")

    private companion object {
        const val PAPERS_DIRECTORY = "papers"
        const val PDF_MIME_TYPE = "application/pdf"
        val LOCAL_PDF_FILE_NAME = Regex("[0-9a-f]{64}\\.pdf")
        val LOCAL_PDF_TEMPORARY_NAME = Regex("\\.local-pdf-[A-Za-z0-9._-]+\\.part")
    }
}

internal fun safeLocalPdfDisplayName(value: String?): String {
    val leaf = value.orEmpty().replace('\\', '/').substringAfterLast('/').trim()
    val cleaned = leaf.map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DISPLAY_NAME_LENGTH)
        .trimEnd()
    return cleaned.ifBlank { "Selected PDF" }
}

internal fun suggestedLocalPdfTitle(displayName: String): String {
    val withoutExtension = if (displayName.endsWith(".pdf", ignoreCase = true)) {
        displayName.dropLast(4)
    } else {
        displayName
    }
    return withoutExtension.trim().take(MAX_LOCAL_PDF_TITLE_LENGTH).trimEnd()
        .ifBlank { "Untitled local PDF" }
}

internal fun normalizeLocalPdfTitle(value: String): String? {
    if (value.any { it.isISOControl() && !it.isWhitespace() }) return null
    val normalized = value.replace(Regex("\\s+"), " ").trim()
    return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_LOCAL_PDF_TITLE_LENGTH }
}

private fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private const val MAX_DISPLAY_NAME_LENGTH = 240
