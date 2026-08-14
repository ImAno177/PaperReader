package dev.paperreader.logic.reader

import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadablePaperCacheTest {
    @Test
    fun `key is deterministic and changes with every cache contract input`() {
        val first = ReadablePaperCache.keyFor("https://arxiv.org/html/1", "policy-1", "renderer-1")

        assertEquals(first, ReadablePaperCache.keyFor("https://arxiv.org/html/1", "policy-1", "renderer-1"))
        assertFalse(first == ReadablePaperCache.keyFor("https://arxiv.org/html/2", "policy-1", "renderer-1"))
        assertFalse(first == ReadablePaperCache.keyFor("https://arxiv.org/html/1", "policy-2", "renderer-1"))
        assertFalse(first == ReadablePaperCache.keyFor("https://arxiv.org/html/1", "policy-1", "renderer-2"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `read rejects invalid keys without touching valid entries`() {
        val directory = Files.createTempDirectory("paper-cache-key")
        val cache = ReadablePaperCache(directory)
        val key = "a".repeat(64)
        cache.write(key, record("kept"))

        assertNull(cache.read("../not-a-key"))
        assertTrue(Files.exists(directory.resolve("$key.body.html")))
    }

    @Test
    fun `read deletes malformed header missing fields and invalid body hash`() {
        val directory = Files.createTempDirectory("paper-cache-corrupt")
        val cache = ReadablePaperCache(directory)
        val key = "b".repeat(64)
        val body = directory.resolve("$key.body.html")
        val manifest = directory.resolve("$key.manifest")

        write(body, "<article>bad</article>")
        write(manifest, "WRONG-HEADER\n")
        assertNull(cache.read(key))
        assertFalse(Files.exists(body))
        assertFalse(Files.exists(manifest))

        write(body, "<article>bad</article>")
        write(manifest, "PAPERREADER-READABLE-CACHE-2\nsource_url=***\n")
        assertNull(cache.read(key))
        assertFalse(Files.exists(body))
        assertFalse(Files.exists(manifest))

        write(body, "<article>bad</article>")
        write(
            manifest,
            "PAPERREADER-READABLE-CACHE-2\n" +
                "source_url=aHR0cHM6Ly9leGFtcGxlLm9yZw\n" +
                "source_sha256=${"a".repeat(64)}\n" +
                "document_sha256=${"c".repeat(64)}\n" +
                "retrieved_at=2026-08-12T00:00:00Z\n",
        )
        assertNull(cache.read(key))
        assertFalse(Files.exists(body))
        assertFalse(Files.exists(manifest))
    }

    @Test
    fun `read tolerates unknown warning and malformed section records`() {
        val directory = Files.createTempDirectory("paper-cache-fields")
        val cache = ReadablePaperCache(directory)
        val key = "c".repeat(64)
        cache.write(key, record("fields"))
        val manifest = directory.resolve("$key.manifest")
        val encodedSections = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("bad\nYW5jaG9y\tdGl0bGU\t2".toByteArray())
        val encodedWarnings = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("FIGURE_UNAVAILABLE\nUNKNOWN_WARNING".toByteArray())
        write(
            manifest,
            String(Files.readAllBytes(manifest))
                .replace(Regex("sections=.*"), "sections=$encodedSections")
                .replace(Regex("warnings=.*"), "warnings=$encodedWarnings"),
        )

        val result = cache.read(key)

        assertEquals(listOf(ReadablePaperSection("anchor", "title", 2)), result?.sections)
        assertEquals(setOf(ReadablePaperWarning.FIGURE_UNAVAILABLE), result?.warnings)
    }

    @Test
    fun `round trip preserves source license and document mapping`() {
        val directory = Files.createTempDirectory("paper-cache-roundtrip")
        val cache = ReadablePaperCache(directory)
        val key = "d".repeat(64)
        val written = record("round-trip").copy(
            sourceLicense = "CC-BY-4.0",
            sections = listOf(ReadablePaperSection("S1", "Intro", 1)),
            warnings = setOf(ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING),
        )
        cache.write(key, written)

        val read = checkNotNull(cache.read(key))
        val document = read.toDocument(
            title = "Paper",
            sourceProvider = "arxiv",
            sourceVersion = "v1",
            license = null,
            servedFromCache = true,
        )

        assertEquals(written, read)
        assertEquals("CC-BY-4.0", document.license)
        assertTrue(document.servedFromCache)
        assertEquals(listOf(ReadablePaperSection("S1", "Intro", 1)), document.sections)
    }

    @Test
    fun `write rejects invalid key body and total budgets`() {
        val directory = Files.createTempDirectory("paper-cache-limits")
        val cache = ReadablePaperCache(directory, maximumCachedBodyBytes = 8, maximumTotalCacheBytes = 64)

        assertThrowsIllegalArgument { cache.write("not-a-key", record("x")) }
        assertThrowsIllegalArgument { cache.write("e".repeat(64), record("123456789")) }
        assertThrowsIllegalArgument {
            cache.write("f".repeat(64), record("12345678").copy(sourceUrl = "https://example.org/${"x".repeat(100)}"))
        }
    }

    @Test
    fun `pruning removes orphan oversized and temporary entries`() {
        val directory = Files.createTempDirectory("paper-cache-prune")
        val cache = ReadablePaperCache(directory, maximumCachedBodyBytes = 1_024, maximumTotalCacheBytes = 2_000)
        val orphanBody = directory.resolve("${"1".repeat(64)}.body.html")
        val oversizedBody = directory.resolve("${"2".repeat(64)}.body.html")
        val oversizedManifest = directory.resolve("${"2".repeat(64)}.manifest")
        Files.write(orphanBody, ByteArray(10))
        Files.write(oversizedBody, ByteArray(2_000))
        Files.write(oversizedManifest, ByteArray(1))
        Files.write(directory.resolve(".stale.body.part"), ByteArray(1))
        Files.write(directory.resolve(".stale.manifest.part"), ByteArray(1))

        cache.write("3".repeat(64), record("usable"))

        assertFalse(Files.exists(orphanBody))
        assertFalse(Files.exists(oversizedBody))
        assertFalse(Files.exists(oversizedManifest))
        assertFalse(Files.exists(directory.resolve(".stale.body.part")))
        assertFalse(Files.exists(directory.resolve(".stale.manifest.part")))
        assertEquals("usable", cache.read("3".repeat(64))?.bodyHtml?.removePrefix("<article>")?.removeSuffix("</article>"))
    }

    private fun record(label: String): CachedReadablePaper {
        val body = "<article>$label</article>"
        return CachedReadablePaper(
            bodyHtml = body,
            sourceUrl = "https://arxiv.org/html/$label",
            sourceSha256 = "a".repeat(64),
            documentSha256 = sha256(body),
            retrievedAt = Instant.parse("2026-08-12T00:00:00Z"),
            sourceLicense = null,
            sections = emptyList(),
            warnings = emptySet(),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun write(path: java.nio.file.Path, value: String) {
        Files.write(path, value.toByteArray())
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
