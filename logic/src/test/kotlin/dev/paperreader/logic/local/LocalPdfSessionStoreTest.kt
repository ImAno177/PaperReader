package dev.paperreader.logic.local

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPdfSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `create load recover and discard preserve one durable pending session`() = runTest {
        val root = temporaryFolder.newFolder("round-trip").toPath()
        val store = LocalPdfSessionStore(root, MAX_BYTES)

        val created = create(store)
        assertEquals(PDF.size.toLong(), created.candidate.byteLength)
        assertTrue(Files.isRegularFile(created.path))

        val loaded = store.load(created.candidate.importToken)
        assertEquals(created, loaded)
        assertEquals(created, store.recoverPending())

        store.discard(created.candidate.importToken)
        assertNull(store.load(created.candidate.importToken))
        assertEquals(0L, countFiles(root))
    }

    @Test
    fun `recover pending returns null for absent directory and removes owned orphans`() = runTest {
        val root = temporaryFolder.newFolder("orphans").toPath()
        val store = LocalPdfSessionStore(root.resolve("missing"), MAX_BYTES)
        assertNull(store.recoverPending())

        Files.createDirectories(root)
        Files.write(root.resolve("orphan.pdf"), PDF)
        writeText(root.resolve("orphan.json"), "{}")
        writeText(root.resolve(".paper-import-abandoned"), "partial")
        writeText(root.resolve("notes.txt"), "keep")

        assertNull(LocalPdfSessionStore(root, MAX_BYTES).recoverPending())
        assertFalse(Files.exists(root.resolve("orphan.pdf")))
        assertFalse(Files.exists(root.resolve("orphan.json")))
        assertFalse(Files.exists(root.resolve(".paper-import-abandoned")))
        assertTrue(Files.exists(root.resolve("notes.txt")))
    }

    @Test
    fun `load rejects invalid token without touching a valid session`() = runTest {
        val root = temporaryFolder.newFolder("invalid-token").toPath()
        val store = LocalPdfSessionStore(root, MAX_BYTES)
        val created = create(store)

        assertNull(store.load("not-a-uuid"))
        assertTrue(Files.exists(created.path))
        assertNotNull(store.load(created.candidate.importToken))
    }

    @Test
    fun `corrupt or incomplete metadata is discarded fail closed`() = runTest {
        val mutations = listOf<(String) -> String>(
            { it.replaceJson("formatVersion", "2") },
            { it.replaceJson("importToken", "\"${UUID.randomUUID()}\"") },
            { it.replaceJson("sourceKey", "\"not-a-sha\"") },
            { it.replaceJson("displayName", "\"   \"") },
            { it.replaceJson("suggestedTitle", "\"\"") },
            { it.replaceJson("documentSha256", "\"${"b".repeat(64)}\"") },
            { it.replaceJson("byteLength", "0") },
            { "not-json" },
        )

        mutations.forEachIndexed { index, mutate ->
            val root = temporaryFolder.newFolder("corrupt-$index").toPath()
            val store = LocalPdfSessionStore(root, MAX_BYTES)
            val created = create(store)
            val metadata = metadataFile(root)
            writeText(metadata, mutate(readText(metadata)))

            assertNull("mutation $index", store.load(created.candidate.importToken))
            assertFalse("metadata mutation $index", Files.exists(metadata))
            assertFalse("document mutation $index", Files.exists(created.path))
        }
    }

    @Test
    fun `load rejects oversized metadata missing document wrong size bad magic and bad hash`() = runTest {
        val oversizedRoot = temporaryFolder.newFolder("oversized-metadata").toPath()
        val oversizedStore = LocalPdfSessionStore(oversizedRoot, MAX_BYTES)
        val oversized = create(oversizedStore)
        writeText(metadataFile(oversizedRoot), "x".repeat(4_097))
        assertNull(oversizedStore.load(oversized.candidate.importToken))

        val missingRoot = temporaryFolder.newFolder("missing-document").toPath()
        val missingStore = LocalPdfSessionStore(missingRoot, MAX_BYTES)
        val missing = create(missingStore)
        Files.delete(missing.path)
        assertNull(missingStore.load(missing.candidate.importToken))

        val wrongSizeRoot = temporaryFolder.newFolder("wrong-size").toPath()
        val wrongSizeStore = LocalPdfSessionStore(wrongSizeRoot, MAX_BYTES)
        val wrongSize = create(wrongSizeStore)
        val wrongSizeMetadata = metadataFile(wrongSizeRoot)
        writeText(wrongSizeMetadata, readText(wrongSizeMetadata).replace("\"byteLength\":${PDF.size}", "\"byteLength\":1"))
        assertNull(wrongSizeStore.load(wrongSize.candidate.importToken))

        val badMagicRoot = temporaryFolder.newFolder("bad-magic").toPath()
        val badMagicStore = LocalPdfSessionStore(badMagicRoot, MAX_BYTES)
        val badMagic = create(badMagicStore)
        Files.write(badMagic.path, "not pdf".toByteArray())
        assertNull(badMagicStore.load(badMagic.candidate.importToken))

        val badHashRoot = temporaryFolder.newFolder("bad-hash").toPath()
        val badHashStore = LocalPdfSessionStore(badHashRoot, MAX_BYTES)
        val badHash = create(badHashStore)
        val badHashMetadata = metadataFile(badHashRoot)
        writeText(badHashMetadata, readText(badHashMetadata).replace(badHash.documentSha256, "b".repeat(64)))
        assertNull(badHashStore.load(badHash.candidate.importToken))
    }

    @Test
    fun `create cleans stale session artifacts and keeps unrelated files`() = runTest {
        val root = temporaryFolder.newFolder("cleanup").toPath()
        Files.write(root.resolve("old.pdf"), PDF)
        writeText(root.resolve("old.json"), "{}")
        writeText(root.resolve(".old.json.part"), "partial")
        writeText(root.resolve("keep.txt"), "keep")
        val store = LocalPdfSessionStore(root, MAX_BYTES)

        val created = create(store)

        assertTrue(Files.exists(created.path))
        assertFalse(Files.exists(root.resolve("old.pdf")))
        assertFalse(Files.exists(root.resolve("old.json")))
        assertFalse(Files.exists(root.resolve(".old.json.part")))
        assertTrue(Files.exists(root.resolve("keep.txt")))
    }

    @Test
    fun `constructor and create enforce positive limits and safe durable metadata`() = runTest {
        val root = temporaryFolder.newFolder("limits").toPath()
        assertThrowsIllegalArgument { LocalPdfSessionStore(root, 0) }
        assertThrowsIllegalArgument { LocalPdfSessionStore(root, -1) }

        val store = LocalPdfSessionStore(root, MAX_BYTES)
        val pending = store.create(
            input = ByteArrayInputStream(PDF),
            sourceKey = "a".repeat(64),
            displayName = "folder\\paper.pdf",
            suggestedTitle = "A title",
        )
        assertEquals("folder\\paper.pdf", pending.candidate.displayName)
        assertEquals("A title", pending.candidate.suggestedTitle)
    }

    @Test
    fun `create failure removes the moved document and temporary metadata`() = runTest {
        val root = temporaryFolder.newFolder("create-failure").toPath()
        val store = LocalPdfSessionStore(root, MAX_BYTES)

        val error = runCatching {
            store.create(
                input = ByteArrayInputStream(PDF),
                sourceKey = SOURCE_KEY,
                displayName = "paper.pdf",
                suggestedTitle = "x".repeat(200_000),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0L, countFiles(root))
    }

    @Test
    fun `recover skips an invalid newest session and returns the next valid one`() = runTest {
        val root = temporaryFolder.newFolder("recover-order").toPath()
        val store = LocalPdfSessionStore(root, MAX_BYTES)
        val valid = create(store)
        val validMetadata = metadataFile(root)
        val invalidToken = UUID.randomUUID().toString()
        Files.copy(valid.path, root.resolve("$invalidToken.pdf"))
        writeText(root.resolve("$invalidToken.json"), "not-json")
        Files.setLastModifiedTime(root.resolve("$invalidToken.json"), java.nio.file.attribute.FileTime.fromMillis(Long.MAX_VALUE))

        val recovered = store.recoverPending()

        assertEquals(valid.candidate.importToken, recovered?.candidate?.importToken)
        assertTrue(Files.exists(valid.path))
        assertFalse(Files.exists(root.resolve("$invalidToken.pdf")))
        assertFalse(Files.exists(root.resolve("$invalidToken.json")))
        assertTrue(Files.exists(validMetadata))
    }

    private suspend fun create(store: LocalPdfSessionStore): PendingLocalPdfSession = store.create(
        input = ByteArrayInputStream(PDF),
        sourceKey = SOURCE_KEY,
        displayName = "paper.pdf",
        suggestedTitle = "Paper",
    )

    private fun metadataFile(root: Path): Path = Files.list(root).use { paths ->
        paths.iterator().asSequence().first { it.fileName.toString().endsWith(".json") }
    }

    private fun countFiles(root: Path): Long = Files.list(root).use { it.count() }

    private fun writeText(path: Path, value: String) = Files.write(path, value.toByteArray(Charsets.UTF_8))

    private fun readText(path: Path): String = Files.readAllBytes(path).toString(Charsets.UTF_8)

    private fun String.replaceJson(key: String, replacement: String): String =
        replace(Regex("\\\"$key\\\":(\\\"[^\\\"]*\\\"|-?[0-9]+)"), "\\\"$key\\\":$replacement")

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Expected IllegalArgumentException", thrown)
    }

    private companion object {
        const val MAX_BYTES = 4_096L
        val SOURCE_KEY = "a".repeat(64)
        val PDF = "%PDF-1.7\ncontent".toByteArray()
    }
}
