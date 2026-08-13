package dev.paperreader.logic.local

import dev.paperreader.logic.data.repository.normalizeLocalPdfTitle
import dev.paperreader.logic.data.repository.safeLocalPdfDisplayName
import dev.paperreader.logic.data.repository.suggestedLocalPdfTitle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPdfIngestorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid PDF is staged with exact hash and byte count`() = runTest {
        val bytes = "%PDF-1.7\nreal bytes".toByteArray()
        val root = temporaryFolder.newFolder("valid").toPath()

        val staged = LocalPdfIngestor(1_024).stage(ByteArrayInputStream(bytes), root)

        assertEquals(bytes.size.toLong(), staged.byteLength)
        assertEquals(sha256(bytes), staged.sha256)
        assertEquals(bytes.toList(), Files.readAllBytes(staged.path).toList())
    }

    @Test
    fun `invalid input is rejected and leaves no staged file`() {
        val root = temporaryFolder.newFolder("invalid").toPath()

        assertThrows(LocalPdfIngestException.InvalidPdf::class.java) {
            runTest { LocalPdfIngestor(1_024).stage(ByteArrayInputStream("not a pdf".toByteArray()), root) }
        }

        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `oversized input is rejected and leaves no staged file`() {
        val root = temporaryFolder.newFolder("oversized").toPath()

        assertThrows(LocalPdfIngestException.TooLarge::class.java) {
            runTest { LocalPdfIngestor(8).stage(ByteArrayInputStream("%PDF-1234".toByteArray()), root) }
        }

        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `cancellation propagates and removes the partial file`() {
        val root = temporaryFolder.newFolder("cancelled").toPath()
        val cancellingInput = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw CancellationException("cancelled")
        }

        assertThrows(CancellationException::class.java) {
            runTest { LocalPdfIngestor(1_024).stage(cancellingInput, root) }
        }

        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `zero length reads are rejected instead of spinning forever`() {
        val root = temporaryFolder.newFolder("stalled").toPath()
        val hostileInput = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }

        assertThrows(LocalPdfIngestException.StalledStream::class.java) {
            runTest { LocalPdfIngestor(1_024).stage(hostileInput, root) }
        }

        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `partial reads still validate complete PDF magic`() = runTest {
        val bytes = "%PDF-1.7\npartial reads".toByteArray()
        val root = temporaryFolder.newFolder("partial-magic").toPath()
        val partialInput = object : InputStream() {
            var offset = 0

            override fun read(): Int = if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 0xff

            override fun read(buffer: ByteArray, destinationOffset: Int, length: Int): Int {
                if (offset >= bytes.size) return -1
                val count = minOf(2, length, bytes.size - offset)
                bytes.copyInto(buffer, destinationOffset, offset, offset + count)
                offset += count
                return count
            }
        }

        val staged = LocalPdfIngestor(1_024).stage(partialInput, root)
        val partialMagicInput = object : InputStream() {
            var offset = 0

            override fun read(): Int = if (offset >= bytes.size) -1 else bytes[offset++].toInt() and 0xff

            override fun read(buffer: ByteArray, destinationOffset: Int, length: Int): Int {
                if (offset >= bytes.size) return -1
                val count = minOf(1, length, bytes.size - offset)
                bytes.copyInto(buffer, destinationOffset, offset, offset + count)
                offset += count
                return count
            }
        }

        assertEquals(bytes.toList(), Files.readAllBytes(staged.path).toList())
        assertTrue(partialMagicInput.hasPdfMagic())
    }

    @Test
    fun `display name and title normalization are bounded and explicit`() {
        assertEquals("paper.pdf", safeLocalPdfDisplayName("folder\\paper.pdf"))
        assertEquals("Selected PDF", safeLocalPdfDisplayName("\u0000\n"))
        assertEquals("Attention Is All You Need", suggestedLocalPdfTitle("Attention Is All You Need.PDF"))
        assertEquals("Untitled local PDF", suggestedLocalPdfTitle(".pdf"))
        assertEquals("A clear title", normalizeLocalPdfTitle("  A\nclear   title "))
        assertNull(normalizeLocalPdfTitle("bad\u0001title"))
        assertFalse(checkNotNull(normalizeLocalPdfTitle("x".repeat(300))).isBlank())
        assertNull(normalizeLocalPdfTitle("x".repeat(301)))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
