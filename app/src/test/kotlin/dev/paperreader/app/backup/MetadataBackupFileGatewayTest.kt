package dev.paperreader.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBackupFileGatewayTest {
    @Test
    fun `bounded reader accepts the exact limit`() {
        val bytes = ByteArray(16_384) { index -> index.toByte() }

        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test
    fun `bounded reader rejects one byte beyond the limit`() {
        val bytes = ByteArray(16_385)

        assertThrows(BackupFileTooLargeException::class.java) {
            readBounded(ByteArrayInputStream(bytes), bytes.size - 1)
        }
    }

    @Test
    fun `writer emits the complete archive`() {
        val expected = ByteArray(32_000) { index -> (index * 17).toByte() }
        val output = ByteArrayOutputStream()

        writeFully(output, expected)

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun `bounded reader rejects a provider that returns empty reads`() {
        val emptyReadStream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }

        assertThrows(IOException::class.java) { readBounded(emptyReadStream, 100) }
    }

    @Test
    fun `failed write restores the exact previous destination contents`() {
        val original = "previous-valid-backup".toByteArray()
        val recovered = ByteArrayOutputStream()
        var openCount = 0
        val failure = assertThrows(BackupDestinationWriteException::class.java) {
            writeWithRecovery(
                originalBytes = original,
                openOutput = {
                    if (openCount++ == 0) failingOutput() else recovered
                },
                bytes = ByteArray(32),
            )
        }

        assertArrayEquals(original, recovered.toByteArray())
        assertTrue(failure.destinationRecovered)
    }

    @Test
    fun `failed write reports when previous destination cannot be recovered`() {
        val failure = assertThrows(BackupDestinationWriteException::class.java) {
            writeWithRecovery(
                originalBytes = "previous-valid-backup".toByteArray(),
                openOutput = { failingOutput() },
                bytes = ByteArray(32),
            )
        }

        assertFalse(failure.destinationRecovered)
    }

    @Test
    fun `cancellation is preserved after previous destination is recovered`() {
        val original = "previous-valid-backup".toByteArray()
        val recovered = ByteArrayOutputStream()
        var openCount = 0
        val cancellation = assertThrows(CancellationException::class.java) {
            writeWithRecovery(
                originalBytes = original,
                openOutput = {
                    if (openCount++ == 0) throw CancellationException("cancelled") else recovered
                },
                bytes = ByteArray(1),
            )
        }
        assertArrayEquals(original, recovered.toByteArray())
        assertTrue(cancellation.suppressed.isEmpty())
    }

    @Test
    fun `cancellation reports when previous destination cannot be recovered`() {
        val cancellation = assertThrows(BackupDestinationRecoveryCancellationException::class.java) {
            writeWithRecovery(
                originalBytes = "previous-valid-backup".toByteArray(),
                openOutput = { throw CancellationException("cancelled") },
                bytes = ByteArray(1),
            )
        }

        assertTrue(cancellation.cause is CancellationException)
    }

    private fun failingOutput() = object : OutputStream() {
        override fun write(value: Int) = throw IOException("storage stopped")
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            throw IOException("storage stopped")
    }
}
