package dev.paperreader.logic.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryArchiveCodecTest {
    @Test
    fun sessionQuotaIncludesPreviouslyPreparedArchivesAndRejectsPartialWrites() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("archive-sessions-")
        try {
            val store = LibraryArchiveSessionStore(directory, java.time.Clock.systemUTC(), maximumStoredBytes = 1024)
            val token = store.create("export") { it.write(ByteArray(700)) }
            try {
                store.create("restore") { it.write(ByteArray(400)) }
                org.junit.Assert.fail("Aggregate staging quota must reject the second file")
            } catch (error: LibraryArchiveException) {
                org.junit.Assert.assertEquals(LibraryArchiveFailure.LIMIT_EXCEEDED, error.reason)
            }
            org.junit.Assert.assertEquals(700L, java.nio.file.Files.size(store.load(token, "export")))
            org.junit.Assert.assertEquals(1L, java.nio.file.Files.list(directory).use { it.count() })
        } finally {
            java.nio.file.Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) }
        }
    }

    @Test
    fun `write and read round trip without closing caller output`() = runBlocking {
        val pdf = "%PDF-1.7\npaper".toByteArray()
        val document = archiveDocument(pdf)
        val manifest = LibraryArchiveManifest(createdAtEpochMillis = 123, documents = listOf(document))
        val output = TrackingOutputStream()
        val metadata = "metadata backup".toByteArray()

        LibraryArchiveCodec.write(metadata, manifest, output) { ByteArrayInputStream(pdf) }

        assertFalse(output.closed)
        val destination = Files.createTempDirectory("library-archive-round-trip")
        try {
            val decoded = LibraryArchiveCodec.read(ByteArrayInputStream(output.toByteArray()), destination)
            assertEquals(manifest, decoded.manifest)
            assertArrayEquals(metadata, decoded.metadata)
            assertEquals(setOf(document), decoded.documents.keys)
            assertArrayEquals(pdf, Files.readAllBytes(requireNotNull(decoded.documents[document])))
        } finally {
            deleteTree(destination)
        }
    }

    @Test
    fun `read rejects a truncated central directory`() {
        val archive = archiveBytes(LibraryArchiveManifest(createdAtEpochMillis = 1), byteArrayOf(1), emptyList())
        val destination = Files.createTempDirectory("library-archive-truncated")
        try {
            val truncated = archive.copyOf(archive.size - 8)
            val failure = assertThrowsArchive { LibraryArchiveCodec.read(ByteArrayInputStream(truncated), destination) }
            assertEquals(LibraryArchiveFailure.INVALID_ARCHIVE, failure.reason)
            assertTrue(directoryIsEmpty(destination))
        } finally {
            deleteTree(destination)
        }
    }

    @Test
    fun `path overload reads the staged archive without deleting it`() = runBlocking {
        val archive = archiveBytes(LibraryArchiveManifest(createdAtEpochMillis = 1), byteArrayOf(1), emptyList())
        val staging = Files.createTempDirectory("library-archive-staged")
        val destination = Files.createTempDirectory("library-archive-path")
        val archivePath = staging.resolve("selected.zip")
        try {
            Files.write(archivePath, archive)
            val decoded = LibraryArchiveCodec.read(archivePath, destination)
            assertEquals(byteArrayOf(1).toList(), decoded.metadata.toList())
            assertTrue(Files.isRegularFile(archivePath))
        } finally {
            deleteTree(staging)
            deleteTree(destination)
        }
    }

    @Test
    fun `read rejects unsafe and unreferenced entries`() {
        val pdf = "%PDF-1.7\npaper".toByteArray()
        val document = archiveDocument(pdf, entryName = "../paper.pdf")
        val manifest = LibraryArchiveManifest(createdAtEpochMillis = 1, documents = listOf(document))
        val unsafeArchive = archiveBytes(manifest, byteArrayOf(1), listOf(document.entryName to pdf))
        val destination = Files.createTempDirectory("library-archive-unsafe")
        try {
            val unsafeFailure = assertThrowsArchive { LibraryArchiveCodec.read(ByteArrayInputStream(unsafeArchive), destination) }
            assertEquals(LibraryArchiveFailure.INVALID_ARCHIVE, unsafeFailure.reason)
            assertTrue(directoryIsEmpty(destination))

            val validManifest = LibraryArchiveManifest(createdAtEpochMillis = 1)
            val withExtra = archiveBytes(validManifest, byteArrayOf(1), listOf("unexpected.bin" to byteArrayOf(2)))
            val extraFailure = assertThrowsArchive { LibraryArchiveCodec.read(ByteArrayInputStream(withExtra), destination) }
            assertEquals(LibraryArchiveFailure.INVALID_ARCHIVE, extraFailure.reason)
            assertTrue(directoryIsEmpty(destination))
        } finally {
            deleteTree(destination)
        }
    }

    @Test
    fun `write fails when a document changes after the export pre-check`() {
        val recorded = "%PDF-1.7\npaper".toByteArray()
        val changedSameSize = "%PDF-1.7\npapes".toByteArray()
        val manifest = LibraryArchiveManifest(createdAtEpochMillis = 1, documents = listOf(archiveDocument(recorded)))

        val failure = assertThrowsArchive {
            LibraryArchiveCodec.write(byteArrayOf(1), manifest, ByteArrayOutputStream()) {
                ByteArrayInputStream(changedSameSize)
            }
        }

        assertEquals(LibraryArchiveFailure.EXPORT_FAILED, failure.reason)
    }

    @Test
    fun `read validates document size hash and PDF magic and cleans partial extraction`() {
        val pdf = "%PDF-1.7\npaper".toByteArray()
        val wrongHash = archiveDocument(pdf).copy(sha256 = "0".repeat(64), entryName = "documents/0-${"0".repeat(64)}.pdf")
        val wrongHashArchive = archiveBytes(
            LibraryArchiveManifest(createdAtEpochMillis = 1, documents = listOf(wrongHash)),
            byteArrayOf(1), listOf(wrongHash.entryName to pdf),
        )
        val wrongSize = archiveDocument(pdf).copy(byteLength = pdf.size.toLong() + 1)
        val wrongSizeArchive = archiveBytes(
            LibraryArchiveManifest(createdAtEpochMillis = 1, documents = listOf(wrongSize)),
            byteArrayOf(1), listOf(wrongSize.entryName to pdf),
        )
        val nonPdf = "not a pdf".toByteArray()
        val nonPdfDocument = archiveDocument(nonPdf)
        val nonPdfArchive = archiveBytes(
            LibraryArchiveManifest(createdAtEpochMillis = 1, documents = listOf(nonPdfDocument)),
            byteArrayOf(1), listOf(nonPdfDocument.entryName to nonPdf),
        )
        val destination = Files.createTempDirectory("library-archive-invalid-document")
        try {
            for (archive in listOf(wrongHashArchive, wrongSizeArchive, nonPdfArchive)) {
                val failure = assertThrowsArchive { LibraryArchiveCodec.read(ByteArrayInputStream(archive), destination) }
                assertEquals(LibraryArchiveFailure.INVALID_ARCHIVE, failure.reason)
                assertTrue("failed extraction left files behind", directoryIsEmpty(destination))
            }
        } finally {
            deleteTree(destination)
        }
    }

    @Test
    fun `read observes cancellation and removes its temporary archive`() = runBlocking {
        val archive = archiveBytes(LibraryArchiveManifest(createdAtEpochMillis = 1), byteArrayOf(1), emptyList())
        val destination = Files.createTempDirectory("library-archive-cancelled")
        val operation = Job()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val input = object : ByteArrayInputStream(archive) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) operation.cancel()
                return count
            }
        }
        try {
            val task = scope.async(operation) { LibraryArchiveCodec.read(input, destination) }
            val failure = runCatching { task.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(directoryIsEmpty(destination))
        } finally {
            scope.coroutineContext[Job]?.cancel()
            deleteTree(destination)
        }
    }

    private fun archiveDocument(
        bytes: ByteArray,
        entryName: String = "documents/0-${bytes.sha256()}.pdf",
    ) = ArchiveDocument(
        workSourceId = "work:1",
        manifestationSourceId = "manifestation:1",
        sha256 = bytes.sha256(),
        byteLength = bytes.size.toLong(),
        entryName = entryName,
    )

    private fun archiveBytes(
        manifest: LibraryArchiveManifest,
        metadata: ByteArray,
        documents: List<Pair<String, ByteArray>>,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(Json { encodeDefaults = true }.encodeToString(LibraryArchiveManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("metadata.backup"))
            zip.write(metadata)
            zip.closeEntry()
            for ((name, bytes) in documents) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun assertThrowsArchive(block: suspend () -> Unit): LibraryArchiveException {
        val failure = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertNotNull("expected archive rejection", failure)
        assertTrue("expected LibraryArchiveException, got $failure", failure is LibraryArchiveException)
        return failure as LibraryArchiveException
    }

    private fun directoryIsEmpty(directory: Path): Boolean =
        Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun ByteArray.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "0123456789abcdef"[value ushr 4].toString() + "0123456789abcdef"[value and 0x0f]
        }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
