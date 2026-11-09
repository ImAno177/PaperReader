package dev.paperreader.logic.data.repository

import androidx.room.withTransaction
import dev.paperreader.logic.backup.*
import dev.paperreader.logic.data.FileEntity
import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.local.hasPdfMagic
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RoomLibraryArchiveRepository(
    private val database: LibraryDatabase,
    private val metadata: RoomMetadataBackupRepository,
    filesDirectory: Path,
    sessionDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val filesRoot = filesDirectory.toAbsolutePath().normalize()
    private val papersRoot = filesRoot.resolve("papers")
    private val sessions = LibraryArchiveSessionStore(sessionDirectory, clock)
    private val dao = database.libraryDao()
    private val mutex = Mutex()

    suspend fun reconcileStaging() = operation(LibraryArchiveFailure.FILE_UNAVAILABLE) { Unit }

    private suspend fun recoverOrphans() {
        sessions.recoverTemporaryDirectories()
        if (!Files.isDirectory(papersRoot, NOFOLLOW_LINKS)) return
        Files.list(papersRoot).use { paths ->
            for (path in paths.iterator()) {
                currentCoroutineContext().ensureActive()
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || !path.fileName.toString().matches(RESTORED_PDF)) continue
                val relative = filesRoot.relativize(path).toString()
                if (dao.getFileByLocalPath(relative) == null) Files.deleteIfExists(path)
            }
        }
    }

    suspend fun prepareExport(): PreparedLibraryArchive = operation(LibraryArchiveFailure.EXPORT_FAILED) {
        val (proto, files) = database.withTransaction {
            buildMetadataBackupProto(database, clock) to dao.getStoredFiles()
        }
        require(files.size <= 1000)
        val metadataBytes = MetadataBackupCodec.encode(proto)
        val workByManifestation = proto.works.flatMap { work ->
            work.manifestations.map { it.sourceId to work.sourceId }
        }.toMap()
        val documents = files.sortedBy(FileEntity::manifestationId).mapIndexed { index, file ->
            val sha = requireNotNull(file.sha256).lowercase()
            val size = requireNotNull(file.byteLength)
            require(file.mimeType == "application/pdf" && sha.matches(SHA256) && size in 1..MAX_PDF_BYTES)
            val path = storedPath(requireNotNull(file.localPath))
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == size && path.hasPdfMagic())
            ArchiveDocument(requireNotNull(workByManifestation[file.manifestationId]), file.manifestationId,
                sha, size, "documents/$index-$sha.pdf") to path
        }
        require(documents.size <= 1000)
        val manifest = LibraryArchiveManifest(createdAtEpochMillis = proto.createdAtEpochMillis, documents = documents.map { it.first })
        val paths = documents.toMap()
        val token = sessions.create("export") { output ->
            LibraryArchiveCodec.write(metadataBytes, manifest, output) { document ->
                Files.newInputStream(requireNotNull(paths[document]))
            }
        }
        PreparedLibraryArchive(token, summary(proto), documents.size, documents.sumOf { it.first.byteLength },
            Instant.ofEpochMilli(proto.createdAtEpochMillis))
    }

    suspend fun writeExport(token: String, output: OutputStream) = operation(LibraryArchiveFailure.EXPORT_FAILED) {
        Files.newInputStream(sessions.load(token, "export")).use { LibraryArchiveSessionStore.copyBounded(it, output) }
        output.flush()
    }

    suspend fun prepareRestore(input: InputStream): LibraryRestoreCandidate = operation(LibraryArchiveFailure.INVALID_ARCHIVE) {
        val token = sessions.stage(input)
        try {
            inspect(token)
        } catch (error: Throwable) {
            sessions.discard(token)
            throw error
        }
    }

    suspend fun recoverPendingRestore(): LibraryRestoreCandidate? = operation(LibraryArchiveFailure.INVALID_ARCHIVE) {
        val token = sessions.pendingRestore() ?: return@operation null
        try {
            inspect(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            sessions.discard(token)
            throw error
        }
    }

    suspend fun discard(token: String) = operation(LibraryArchiveFailure.FILE_UNAVAILABLE) { sessions.discard(token) }

    suspend fun restore(token: String): LibraryRestoreReport = operation(LibraryArchiveFailure.RESTORE_FAILED) {
        withArchive(token) { archive, proto ->
            Files.createDirectories(papersRoot)
            val published = linkedMapOf<ArchiveDocument, Path>()
            var completed = false
            try {
                for ((document, staged) in archive.documents) {
                    currentCoroutineContext().ensureActive()
                    val target = papersRoot.resolve("restored-${UUID.randomUUID()}.pdf")
                    Files.move(staged, target, ATOMIC_MOVE)
                    published[document] = target
                }
                val report = database.withTransaction {
                    val plan = metadata.analyze(proto)
                    val bindings = bindings(plan)
                    val applied = metadata.apply(plan)
                    var restored = 0
                    for ((document, path) in published) {
                        val targetId = bindings[ManifestationRef(document.workSourceId, document.manifestationSourceId)] ?: continue
                        val existing = dao.getFile(targetId)
                        if (existing?.localPath != null) continue
                        dao.upsertFile(FileEntity(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            manifestationId = targetId,
                            localPath = filesRoot.relativize(path).toString(),
                            sha256 = document.sha256,
                            byteLength = document.byteLength,
                            mimeType = "application/pdf",
                            extractionStatus = "NOT_STARTED",
                            extractionManifestPath = null,
                            updatedAtEpochMillis = clock.millis(),
                        ))
                        restored++
                    }
                    LibraryRestoreReport(applied, restored, published.size - restored)
                }
                completed = true
                report
            } finally {
                withContext(NonCancellable) {
                    for (path in published.values) {
                        if (dao.getFileByLocalPath(filesRoot.relativize(path).toString()) == null) Files.deleteIfExists(path)
                    }
                    if (completed) sessions.discard(token)
                }
            }
        }
    }

    private suspend fun inspect(token: String): LibraryRestoreCandidate = withArchive(token) { archive, proto ->
        database.withTransaction {
            val plan = metadata.analyze(proto)
            val bindings = bindings(plan)
            var conflicts = 0
            for (document in archive.manifest.documents) {
                val target = bindings[ManifestationRef(document.workSourceId, document.manifestationSourceId)]
                val current = target?.let { dao.getFile(it) }
                if (target == null || current?.localPath != null && current.sha256 != document.sha256) conflicts++
            }
            LibraryRestoreCandidate(token, plan.preview, archive.documents.size,
                archive.manifest.documents.sumOf(ArchiveDocument::byteLength), conflicts)
        }
    }

    private fun bindings(plan: RestorePlan): Map<ManifestationRef, String> = plan.works.filter(WorkPlan::eligible)
        .flatMap { work -> work.manifestations.map { ManifestationRef(work.source.sourceId, it.source.sourceId) to it.targetId.value } }
        .toMap()

    private suspend fun <T> withArchive(token: String, block: suspend (DecodedLibraryArchive, MetadataBackupProto) -> T): T {
        val directory = sessions.temporaryDirectory()
        try {
            val archive = LibraryArchiveCodec.read(sessions.load(token, "restore"), directory)
            val proto = when (val decoded = MetadataBackupCodec.decode(archive.metadata)) {
                is DecodedBackup.Valid -> decoded.value
                is DecodedBackup.Invalid -> throw LibraryArchiveException(LibraryArchiveFailure.INVALID_ARCHIVE)
            }
            val identities = proto.works.flatMap { work -> work.manifestations.map { work.sourceId to it.sourceId } }.toSet()
            require(archive.manifest.documents.all { (it.workSourceId to it.manifestationSourceId) in identities })
            return block(archive, proto)
        } finally {
            sessions.removeTemporaryDirectory(directory)
        }
    }

    private fun storedPath(relative: String): Path {
        val relativePath = Paths.get(relative)
        require(!relativePath.isAbsolute)
        val resolved = filesRoot.resolve(relativePath).normalize()
        require(resolved.startsWith(papersRoot) && resolved != papersRoot)
        return resolved
    }

    private suspend fun <T> operation(failure: LibraryArchiveFailure, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { recoverOrphans(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: LibraryArchiveException) { throw error }
            catch (_: Exception) { throw LibraryArchiveException(failure) }
        }
    }

    private companion object {
        val RESTORED_PDF = Regex("restored-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val MAX_PDF_BYTES = 200L * 1024 * 1024
    }
}
