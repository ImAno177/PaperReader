package dev.paperreader.logic.reader

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

internal data class CachedReadablePaper(
    val bodyHtml: String,
    val sourceUrl: String,
    val sourceSha256: String,
    val documentSha256: String,
    val retrievedAt: Instant,
    val sourceLicense: String?,
    val sections: List<ReadablePaperSection>,
    val warnings: Set<ReadablePaperWarning>,
) {
    fun toDocument(
        title: String,
        sourceProvider: String,
        sourceVersion: String,
        license: String?,
        servedFromCache: Boolean,
    ) = ReadablePaperDocument(
        bodyHtml = bodyHtml,
        title = title,
        sourceUrl = sourceUrl,
        sourceProvider = sourceProvider,
        sourceVersion = sourceVersion,
        license = license ?: sourceLicense,
        sourceSha256 = sourceSha256,
        documentSha256 = documentSha256,
        retrievedAt = retrievedAt,
        servedFromCache = servedFromCache,
        sections = sections,
        warnings = warnings,
    )
}

internal class ReadablePaperCache(
    private val directory: Path,
    private val maximumCachedBodyBytes: Long = 20L * 1024L * 1024L,
    private val maximumTotalCacheBytes: Long = 160L * 1024L * 1024L,
) {
    init {
        require(maximumCachedBodyBytes > 0)
        require(maximumTotalCacheBytes > maximumCachedBodyBytes)
    }

    @Synchronized
    fun read(key: String): CachedReadablePaper? {
        if (!key.matches(SHA256_PATTERN)) return null
        try {
            pruneToBudget(protectedKey = key)
        } catch (_: IOException) {
            // A missing or temporarily unavailable cache directory is an ordinary cache miss.
        }
        val manifestPath = directory.resolve("$key$MANIFEST_SUFFIX")
        val bodyPath = directory.resolve("$key$BODY_SUFFIX")
        val record = runCatching {
            if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(bodyPath)) return@runCatching null
            if (Files.size(manifestPath) > MAXIMUM_MANIFEST_BYTES || Files.size(bodyPath) > maximumCachedBodyBytes) {
                return@runCatching null
            }
            val fields = Files.readAllLines(manifestPath, Charsets.UTF_8)
            if (fields.firstOrNull() != CACHE_HEADER) return@runCatching null
            val values = fields.drop(1).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
            val body = Files.newBufferedReader(bodyPath, Charsets.UTF_8).use { it.readText() }
            val documentSha = values.getValue("document_sha256")
            if (!documentSha.matches(SHA256_PATTERN) || sha256(body.toByteArray(Charsets.UTF_8)) != documentSha) {
                return@runCatching null
            }
            val sourceSha = values.getValue("source_sha256")
            if (!sourceSha.matches(SHA256_PATTERN)) return@runCatching null
            CachedReadablePaper(
                bodyHtml = body,
                sourceUrl = decode(values.getValue("source_url")),
                sourceSha256 = sourceSha,
                documentSha256 = documentSha,
                retrievedAt = Instant.parse(values.getValue("retrieved_at")),
                sourceLicense = values["source_license"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::decode)
                    ?.takeIf(String::isNotBlank),
                sections = decodeSections(values["sections"].orEmpty()),
                warnings = decode(values["warnings"].orEmpty())
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .mapNotNull { runCatching { ReadablePaperWarning.valueOf(it) }.getOrNull() }
                    .toSet(),
            )
        }.getOrNull()
        if (record == null) {
            deleteEntry(key)
            return null
        }
        runCatching {
            val now = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())
            Files.setLastModifiedTime(bodyPath, now)
            Files.setLastModifiedTime(manifestPath, now)
        }
        return record
    }

    @Synchronized
    fun write(key: String, record: CachedReadablePaper) {
        require(key.matches(SHA256_PATTERN))
        val bodyBytes = record.bodyHtml.toByteArray(Charsets.UTF_8)
        require(bodyBytes.size <= maximumCachedBodyBytes)
        val manifest = listOf(
            CACHE_HEADER,
            "source_url=${encode(record.sourceUrl)}",
            "source_sha256=${record.sourceSha256}",
            "document_sha256=${record.documentSha256}",
            "retrieved_at=${record.retrievedAt}",
            "source_license=${record.sourceLicense?.let(::encode).orEmpty()}",
            "sections=${encodeSections(record.sections)}",
            "warnings=${encode(record.warnings.sortedBy { it.name }.joinToString("\n") { it.name })}",
        ).joinToString("\n", postfix = "\n")
        val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAXIMUM_MANIFEST_BYTES)
        require(bodyBytes.size.toLong() + manifestBytes.size <= maximumTotalCacheBytes)
        Files.createDirectories(directory)
        val bodyPath = directory.resolve("$key$BODY_SUFFIX")
        val manifestPath = directory.resolve("$key$MANIFEST_SUFFIX")
        val bodyTemporary = Files.createTempFile(directory, ".$key-", BODY_TEMP_SUFFIX)
        val manifestTemporary = Files.createTempFile(directory, ".$key-", MANIFEST_TEMP_SUFFIX)
        try {
            Files.newBufferedWriter(bodyTemporary, Charsets.UTF_8).use { it.write(record.bodyHtml) }
            Files.newBufferedWriter(manifestTemporary, Charsets.UTF_8).use { it.write(manifest) }
            moveAtomically(bodyTemporary, bodyPath)
            moveAtomically(manifestTemporary, manifestPath)
            try {
                pruneToBudget(protectedKey = key)
            } catch (_: IOException) {
                // A later write retries pruning; a successful cache publication must remain usable.
            }
        } finally {
            Files.deleteIfExists(bodyTemporary)
            Files.deleteIfExists(manifestTemporary)
        }
    }

    private fun pruneToBudget(protectedKey: String) {
        val keys = linkedSetOf<String>()
        Files.newDirectoryStream(directory).use { paths ->
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name.endsWith(BODY_TEMP_SUFFIX) || name.endsWith(MANIFEST_TEMP_SUFFIX)) {
                    runCatching { Files.deleteIfExists(path) }
                    return@forEach
                }
                val key = when {
                    name.endsWith(BODY_SUFFIX) -> name.removeSuffix(BODY_SUFFIX)
                    name.endsWith(MANIFEST_SUFFIX) -> name.removeSuffix(MANIFEST_SUFFIX)
                    else -> null
                }
                if (key != null && key.matches(SHA256_PATTERN)) keys += key
            }
        }
        val entries = keys.mapNotNull { key ->
            val body = directory.resolve("$key$BODY_SUFFIX")
            val manifest = directory.resolve("$key$MANIFEST_SUFFIX")
            if (!Files.isRegularFile(body) || !Files.isRegularFile(manifest)) {
                deleteEntry(key)
                return@mapNotNull null
            }
            val bodySize = runCatching { Files.size(body) }.getOrNull()
            val manifestSize = runCatching { Files.size(manifest) }.getOrNull()
            if (bodySize == null || manifestSize == null) {
                deleteEntry(key)
                return@mapNotNull null
            }
            if (bodySize > maximumCachedBodyBytes || manifestSize > MAXIMUM_MANIFEST_BYTES) {
                deleteEntry(key)
                return@mapNotNull null
            }
            runCatching {
                CacheEntry(
                    key = key,
                    bytes = bodySize + manifestSize,
                    lastUsedMillis = maxOf(
                        Files.getLastModifiedTime(body).toMillis(),
                        Files.getLastModifiedTime(manifest).toMillis(),
                    ),
                )
            }.getOrElse {
                deleteEntry(key)
                null
            }
        }
        var totalBytes = entries.sumOf(CacheEntry::bytes)
        entries.sortedBy(CacheEntry::lastUsedMillis).forEach { entry ->
            if (totalBytes <= maximumTotalCacheBytes) return
            if (entry.key == protectedKey) return@forEach
            deleteEntry(entry.key)
            totalBytes -= entry.bytes
        }
    }

    private fun deleteEntry(key: String) {
        runCatching { Files.deleteIfExists(directory.resolve("$key$BODY_SUFFIX")) }
        runCatching { Files.deleteIfExists(directory.resolve("$key$MANIFEST_SUFFIX")) }
    }

    private data class CacheEntry(
        val key: String,
        val bytes: Long,
        val lastUsedMillis: Long,
    )

    companion object {
        private const val CACHE_HEADER = "PAPERREADER-READABLE-CACHE-2"
        private const val MAXIMUM_MANIFEST_BYTES = 16L * 1024L
        private const val BODY_SUFFIX = ".body.html"
        private const val MANIFEST_SUFFIX = ".manifest"
        private const val BODY_TEMP_SUFFIX = ".body.part"
        private const val MANIFEST_TEMP_SUFFIX = ".manifest.part"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun keyFor(
            sourceUrl: String,
            sanitizerPolicyVersion: String,
            rendererContractVersion: String,
        ): String = sha256(
            listOf(sourceUrl, sanitizerPolicyVersion, rendererContractVersion)
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8),
        )

        private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decode(value: String): String = String(
            Base64.getUrlDecoder().decode(value),
            Charsets.UTF_8,
        )

        private fun encodeSections(sections: List<ReadablePaperSection>): String = encode(
            sections.joinToString("\n") { section ->
                "${encode(section.anchor)}\t${encode(section.title)}\t${section.level}"
            },
        )

        private fun decodeSections(value: String): List<ReadablePaperSection> = runCatching {
            decode(value).lineSequence().filter(String::isNotBlank).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                runCatching {
                    ReadablePaperSection(
                        anchor = decode(parts[0]),
                        title = decode(parts[1]),
                        level = parts[2].toInt(),
                    )
                }.getOrNull()
            }.toList()
        }.getOrDefault(emptyList())
    }
}
