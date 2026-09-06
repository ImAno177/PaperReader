package dev.paperreader.logic.reader

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.attribute.FileTime

/**
 * File-backed cache for one readable document's raster and SVG assets.
 *
 * Asset files are deliberately independent from the HTML body so WebView never receives a
 * base64-expanded copy of every figure at once.
 */
internal class ReadablePaperAssetCache(
    private val directory: Path,
    private val maximumAssetBytes: Long = DEFAULT_MAXIMUM_ASSET_BYTES,
    private val maximumTotalCacheBytes: Long = DEFAULT_MAXIMUM_TOTAL_CACHE_BYTES,
    private val maximumRetainedBytes: Long = maximumTotalCacheBytes * 3L / 4L,
) {
    init {
        require(maximumAssetBytes > 0)
        require(maximumTotalCacheBytes > maximumAssetBytes)
        require(maximumRetainedBytes > 0)
        require(maximumRetainedBytes <= maximumTotalCacheBytes)
    }

    @Synchronized
    fun write(groupKey: String, asset: ReadablePaperAsset, bytes: ByteArray) {
        val group = groupDirectory(groupKey)
        require(bytes.isNotEmpty() && bytes.size.toLong() <= maximumAssetBytes)
        if (
            Files.exists(group, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Readable asset group is not a directory")
        }
        require(sha256(bytes) == asset.sha256)
        Files.createDirectories(group)
        val destination = assetPath(group, asset.id)
        val temporary = Files.createTempFile(group, ".${asset.id}-", TEMPORARY_SUFFIX)
        try {
            Files.newOutputStream(temporary).use { output -> output.write(bytes) }
            moveAtomically(temporary, destination)
            pruneToBudget(protectedGroupKey = groupKey)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun open(groupKey: String?, asset: ReadablePaperAsset): ReadablePaperAssetContent? {
        if (groupKey == null || !isValidAssetId(asset.id)) return null
        val group = runCatching { groupDirectory(groupKey) }.getOrNull() ?: return null
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return null
        val path = assetPath(group, asset.id)
        if (!isValidFile(path, asset)) return null
        touch(path, group)
        return runCatching {
            ReadablePaperAssetContent(
                mediaType = asset.mediaType,
                inputStream = Files.newInputStream(path),
            )
        }.getOrNull()
    }

    @Synchronized
    fun readBytes(groupKey: String?, asset: ReadablePaperAsset): ByteArray? {
        if (groupKey == null || !isValidAssetId(asset.id)) return null
        val group = runCatching { groupDirectory(groupKey) }.getOrNull() ?: return null
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return null
        val path = assetPath(group, asset.id)
        if (!isValidFile(path, asset)) return null
        return runCatching {
            val bytes = Files.readAllBytes(path)
            if (bytes.size.toLong() == asset.byteLength && sha256(bytes) == asset.sha256) {
                touch(path, group)
                bytes
            } else {
                null
            }
        }.getOrNull()
    }

    @Synchronized
    fun remove(groupKey: String, assetId: String): Boolean {
        if (!isValidGroupKey(groupKey) || !isValidAssetId(assetId)) return false
        val group = groupDirectory(groupKey)
        if (!Files.exists(group, LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return false
        val path = assetPath(group, assetId)
        return runCatching { Files.deleteIfExists(path) }.getOrDefault(false) || !Files.exists(path)
    }

    @Synchronized
    fun allPresent(groupKey: String?, assets: List<ReadablePaperAsset>): Boolean {
        if (assets.isEmpty()) return true
        if (groupKey == null || !isValidGroupKey(groupKey)) return false
        val group = runCatching { groupDirectory(groupKey) }.getOrNull() ?: return false
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return false
        val present = assets.distinctBy(ReadablePaperAsset::id).all { asset ->
            isValidFile(assetPath(group, asset.id), asset)
        }
        if (present) touch(group = group)
        return present
    }

    @Synchronized
    fun keepForOffline(groupKey: String?, assets: List<ReadablePaperAsset>): Boolean {
        if (assets.isEmpty()) return true
        if (!allPresent(groupKey, assets)) return false
        val key = checkNotNull(groupKey)
        if (hasValidOfflineMarker(key)) return true
        val groupBytes = groupBytes(key) ?: return false
        val retainedBytes = runCatching {
            groups()
                .asSequence()
                .filter(::hasValidOfflineMarker)
                .filter { it != key }
                .mapNotNull(::groupBytes)
                .sum()
        }.getOrNull() ?: return false
        if (retainedBytes + groupBytes > maximumRetainedBytes) {
            runCatching { pruneToBudget(protectedGroupKey = null) }
            return false
        }
        val group = groupDirectory(key)
        return runCatching {
            Files.createDirectories(group)
            Files.newBufferedWriter(group.resolve(OFFLINE_MARKER), Charsets.UTF_8).use { it.write(OFFLINE_HEADER) }
            pruneToBudget(protectedGroupKey = key)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun isKeptForOffline(groupKey: String?, assets: List<ReadablePaperAsset>): Boolean {
        if (assets.isEmpty()) return true
        return groupKey != null && hasValidOfflineMarker(groupKey) && allPresent(groupKey, assets)
    }

    @Synchronized
    fun removeGroup(groupKey: String): Boolean {
        if (!isValidGroupKey(groupKey)) return false
        val root = directory.toAbsolutePath().normalize()
        val group = root.resolve(groupKey).normalize()
        if (!group.startsWith(root)) return false
        if (Files.isSymbolicLink(group)) return runCatching { Files.deleteIfExists(group) }.getOrDefault(false)
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) {
            return !Files.exists(group, LinkOption.NOFOLLOW_LINKS)
        }
        var complete = true
        runCatching {
            Files.newDirectoryStream(group).use { paths ->
                paths.forEach { path ->
                    if (Files.isSymbolicLink(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.deleteIfExists(path)
                        } catch (_: IOException) {
                            complete = false
                        }
                    }
                }
            }
            if (complete && Files.exists(group, LinkOption.NOFOLLOW_LINKS)) {
                complete = Files.deleteIfExists(group)
            }
        }.onFailure { complete = false }
        return complete
    }

    @Synchronized
    fun removeGroupsNotIn(groupKeys: Set<String>): Int {
        if (!Files.isDirectory(directory)) return 0
        var removed = 0
        groups().forEach { key ->
            if (key !in groupKeys && removeGroup(key)) removed += 1
        }
        return removed
    }

    private fun isValidFile(path: Path, asset: ReadablePaperAsset): Boolean {
        if (asset.byteLength > maximumAssetBytes) return false
        return runCatching {
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(path) == asset.byteLength &&
                fileSha256(path) == asset.sha256
        }.getOrDefault(false)
    }

    private fun groupDirectory(groupKey: String): Path {
        require(isValidGroupKey(groupKey))
        val root = directory.toAbsolutePath().normalize()
        val group = root.resolve(groupKey).normalize()
        require(group.startsWith(root))
        return group
    }

    private fun assetPath(group: Path, assetId: String): Path {
        require(isValidAssetId(assetId))
        val path = group.resolve("$assetId$ASSET_SUFFIX").normalize()
        require(path.parent == group)
        return path
    }

    private fun groups(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory).use { paths ->
            paths.mapNotNull { path ->
                path.fileName.toString().takeIf {
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && isValidGroupKey(it)
                }
            }.toList()
        }
    }

    private fun groupBytes(groupKey: String): Long? {
        val group = runCatching { groupDirectory(groupKey) }.getOrNull() ?: return null
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            Files.newDirectoryStream(group).use { paths ->
                paths.filter { it.fileName.toString().endsWith(ASSET_SUFFIX) }
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map(Files::size)
                    .sum()
            }
        }.getOrNull()
    }

    private fun hasValidOfflineMarker(groupKey: String): Boolean {
        if (!isValidGroupKey(groupKey)) return false
        val group = groupDirectory(groupKey)
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return false
        val marker = group.resolve(OFFLINE_MARKER)
        val valid = runCatching {
            Files.isRegularFile(marker) &&
                Files.size(marker) <= MAXIMUM_MARKER_BYTES &&
                Files.newBufferedReader(marker, Charsets.UTF_8).use { it.readText() } == OFFLINE_HEADER
        }.getOrDefault(false)
        if (!valid) runCatching { Files.deleteIfExists(marker) }
        return valid
    }

    private fun pruneToBudget(protectedGroupKey: String?) {
        if (!Files.isDirectory(directory)) return
        val entries = groups().mapNotNull { key ->
            val group = groupDirectory(key)
            val bytes = groupBytes(key) ?: return@mapNotNull null
            runCatching {
                AssetGroupEntry(
                    key = key,
                    bytes = bytes,
                    lastUsedMillis = Files.newDirectoryStream(group).use { paths ->
                        paths.map { Files.getLastModifiedTime(it).toMillis() }.maxOrNull() ?: 0L
                    },
                    keptForOffline = hasValidOfflineMarker(key),
                )
            }.getOrNull()
        }
        var totalBytes = entries.sumOf(AssetGroupEntry::bytes)
        entries.sortedBy(AssetGroupEntry::lastUsedMillis).forEach { entry ->
            if (totalBytes <= maximumTotalCacheBytes) return
            if (entry.key == protectedGroupKey || entry.keptForOffline) return@forEach
            if (removeGroup(entry.key)) totalBytes -= entry.bytes
        }
    }

    private fun touch(path: Path? = null, group: Path) {
        runCatching {
            val now = FileTime.fromMillis(System.currentTimeMillis())
            path?.let { Files.setLastModifiedTime(it, now) }
            Files.setLastModifiedTime(group, now)
        }
    }

    private data class AssetGroupEntry(
        val key: String,
        val bytes: Long,
        val lastUsedMillis: Long,
        val keptForOffline: Boolean,
    )

    companion object {
        private const val DEFAULT_MAXIMUM_ASSET_BYTES = MAXIMUM_READABLE_ASSET_BYTES
        private const val DEFAULT_MAXIMUM_TOTAL_CACHE_BYTES = 160L * 1024L * 1024L
        private const val MAXIMUM_MARKER_BYTES = 64L
        private const val OFFLINE_MARKER = ".offline"
        private const val OFFLINE_HEADER = "PAPERREADER-READABLE-ASSETS-OFFLINE-1\n"
        private const val ASSET_SUFFIX = ".asset"
        private const val TEMPORARY_SUFFIX = ".part"
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")

        private fun isValidGroupKey(value: String): Boolean = value.matches(KEY_PATTERN)
        private fun isValidAssetId(value: String): Boolean = value.matches(KEY_PATTERN)
    }
}

private fun fileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
