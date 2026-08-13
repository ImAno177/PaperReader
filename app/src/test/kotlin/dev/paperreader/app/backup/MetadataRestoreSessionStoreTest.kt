package dev.paperreader.app.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataRestoreSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pending archive survives a new store instance and clears explicitly`() = runBlocking {
        val directory = temporaryFolder.newFolder("restore-session")
        val first = FileMetadataRestoreSessionStore(directory, maximumBytes = 1024)
        val bytes = ByteArray(700) { index -> (index * 31).toByte() }

        first.save(bytes)
        val recreated = FileMetadataRestoreSessionStore(directory, maximumBytes = 1024)
        assertArrayEquals(bytes, recreated.load())

        recreated.clear()
        assertNull(recreated.load())
    }

    @Test
    fun `new selection atomically replaces the previous pending archive`() = runBlocking {
        val directory = temporaryFolder.newFolder("replace-session")
        val store = FileMetadataRestoreSessionStore(directory, maximumBytes = 1024)
        store.save(byteArrayOf(1, 2, 3))

        store.save(byteArrayOf(7, 8))

        assertArrayEquals(byteArrayOf(7, 8), store.load())
    }

    @Test
    fun `oversized archive is rejected before it is persisted`() {
        val directory = temporaryFolder.newFolder("bounded-session")
        val store = FileMetadataRestoreSessionStore(directory, maximumBytes = 4)

        assertThrows(BackupFileTooLargeException::class.java) {
            runBlocking { store.save(ByteArray(5)) }
        }
        runBlocking { assertNull(store.load()) }
    }
}
