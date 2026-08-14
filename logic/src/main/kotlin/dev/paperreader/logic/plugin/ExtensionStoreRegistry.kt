package dev.paperreader.logic.plugin

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

data class ExtensionStoreRecord(
    val indexUrl: String,
    val index: VerifiedExtensionStoreIndex,
    val pinned: Boolean = false,
)

data class ExtensionStoreIssue(
    val storeId: String?,
    val message: String,
)

data class ExtensionStoreRegistryState(
    val stores: List<ExtensionStoreRecord> = emptyList(),
    val issues: List<ExtensionStoreIssue> = emptyList(),
    val refreshingStoreIds: Set<String> = emptySet(),
)

data class ExtensionStorePreview(
    val token: String,
    val indexUrl: String,
    val index: VerifiedExtensionStoreIndex,
)

class ExtensionStoreRegistry(
    directory: Path,
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = "PaperReader/0.1 (Android)",
    private val clock: Clock = Clock.systemUTC(),
    private val envelopeFetcher: (suspend (String) -> ByteArray)? = null,
) {
    private val registryFile = directory.resolve("stores.json")
    private val verifier = ExtensionStoreIndexVerifier(clock)
    private val mutex = Mutex()
    private val pendingPreviews = linkedMapOf<String, PendingPreview>()
    private val pinnedStoreIds = mutableSetOf<String>()
    private var cachedStores: List<CachedStore>
    private val mutableState: MutableStateFlow<ExtensionStoreRegistryState>
    val state: StateFlow<ExtensionStoreRegistryState>

    init {
        require(userAgent.isNotBlank())
        val loaded = loadRegistry()
        cachedStores = loaded.first
        mutableState = MutableStateFlow(
            ExtensionStoreRegistryState(
                stores = cachedStores.toRecords(),
                issues = loaded.second,
            ),
        )
        state = mutableState.asStateFlow()
    }

    suspend fun preview(indexUrl: String, publicKeyBase64: String): ExtensionStorePreview {
        val normalizedUrl = requireIndexUrl(indexUrl)
        val normalizedKey = normalizePublicKey(publicKeyBase64)
        val envelope = fetchEnvelope(normalizedUrl)
        val index = withContext(Dispatchers.Default) { verifier.verify(envelope, normalizedKey) }
        val token = UUID.randomUUID().toString()
        mutex.withLock {
            prunePreviews()
            pendingPreviews[token] = PendingPreview(
                createdAt = clock.instant(),
                indexUrl = normalizedUrl,
                publicKeyBase64 = normalizedKey,
                envelope = envelope,
                index = index,
            )
            while (pendingPreviews.size > MAX_PENDING_PREVIEWS) pendingPreviews.remove(pendingPreviews.keys.first())
        }
        return ExtensionStorePreview(token, normalizedUrl, index)
    }

    /** Adds the app's immutable default store or refreshes its last-known-good signed index. */
    suspend fun ensurePinned(
        indexUrl: String,
        publicKeyBase64: String,
        expectedStoreId: String,
    ): ExtensionStoreRecord {
        val normalizedUrl = requireIndexUrl(indexUrl)
        val normalizedKey = normalizePublicKey(publicKeyBase64)
        val existing = mutex.withLock {
            pinnedStoreIds += expectedStoreId
            cachedStores.firstOrNull { it.index.storeId == expectedStoreId }?.also { cached ->
                require(cached.indexUrl == normalizedUrl) { "Pinned extension store URL changed" }
                require(cached.publicKeyBase64 == normalizedKey) { "Pinned extension store key changed" }
            }
        }
        if (existing != null) return refresh(expectedStoreId)

        return try {
            val envelope = fetchEnvelope(normalizedUrl)
            val index = withContext(Dispatchers.Default) {
                verifier.verify(envelope, normalizedKey, expectedStoreId = expectedStoreId)
            }
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    cachedStores.firstOrNull { it.index.storeId == expectedStoreId }
                        ?.let { return@withLock it.toRecord() }
                    val existingPackages = cachedStores.flatMapTo(mutableSetOf()) { store ->
                        store.index.releases.map(VerifiedExtensionRelease::packageName)
                    }
                    require(index.releases.none { it.packageName in existingPackages }) {
                        "Extension package is already owned by another store"
                    }
                    val cached = CachedStore(normalizedUrl, normalizedKey, envelope, index)
                    val updated = cachedStores + cached
                    persist(updated)
                    cachedStores = updated
                    publish()
                    cached.toRecord()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(
                    issues = mutableState.value.issues.filterNot { it.storeId == expectedStoreId } +
                        ExtensionStoreIssue(
                            expectedStoreId,
                            error.message?.take(180)?.takeIf(String::isNotBlank)
                                ?: "Pinned extension store refresh failed",
                        ),
                )
            }
            throw error
        }
    }

    suspend fun addPreview(token: String): ExtensionStoreRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            prunePreviews()
            val preview = requireNotNull(pendingPreviews.remove(token)) { "Extension store preview expired" }
            require(cachedStores.none { it.index.storeId == preview.index.storeId }) { "Extension store is already added" }
            require(cachedStores.none { it.indexUrl == preview.indexUrl }) { "Extension store URL is already added" }
            val existingPackages = cachedStores.flatMapTo(mutableSetOf()) { store ->
                store.index.releases.map(VerifiedExtensionRelease::packageName)
            }
            require(preview.index.releases.none { it.packageName in existingPackages }) {
                "Extension package is already owned by another store"
            }
            val cached = CachedStore(
                indexUrl = preview.indexUrl,
                publicKeyBase64 = preview.publicKeyBase64,
                envelope = preview.envelope,
                index = preview.index,
            )
            val updated = cachedStores + cached
            persist(updated)
            cachedStores = updated
            publish()
            cached.toRecord()
        }
    }

    suspend fun refresh(storeId: String): ExtensionStoreRecord {
        val original = mutex.withLock {
            require(storeId !in mutableState.value.refreshingStoreIds) { "Extension store is already refreshing" }
            requireNotNull(cachedStores.firstOrNull { it.index.storeId == storeId }) { "Unknown extension store" }
                .also {
                    mutableState.value = mutableState.value.copy(
                        refreshingStoreIds = mutableState.value.refreshingStoreIds + storeId,
                        issues = mutableState.value.issues.filterNot { issue -> issue.storeId == storeId },
                    )
                }
        }
        return try {
            val envelope = fetchEnvelope(original.indexUrl)
            var verified = withContext(Dispatchers.Default) {
                verifier.verify(
                    envelopeBytes = envelope,
                    publicKeyBase64 = original.publicKeyBase64,
                    expectedStoreId = original.index.storeId,
                    minimumSequence = original.index.sequence,
                )
            }
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val current = requireNotNull(cachedStores.firstOrNull { it.index.storeId == storeId }) {
                        "Extension store was removed while refreshing"
                    }
                    if (current.index.sequence != original.index.sequence) {
                        verified = verifier.verify(
                            envelopeBytes = envelope,
                            publicKeyBase64 = current.publicKeyBase64,
                            expectedStoreId = current.index.storeId,
                            minimumSequence = current.index.sequence,
                        )
                    }
                    if (verified.sequence == current.index.sequence) {
                        require(verified.signedPayloadSha256 == current.index.signedPayloadSha256) {
                            "Extension store reused a sequence with different content"
                        }
                        current.toRecord()
                    } else {
                        val otherPackages = cachedStores
                            .filterNot { it.index.storeId == storeId }
                            .flatMapTo(mutableSetOf()) { store ->
                                store.index.releases.map(VerifiedExtensionRelease::packageName)
                            }
                        require(verified.releases.none { it.packageName in otherPackages }) {
                            "Extension package is already owned by another store"
                        }
                        val replacement = current.copy(envelope = envelope, index = verified)
                        val updated = cachedStores.map { if (it.index.storeId == storeId) replacement else it }
                        persist(updated)
                        cachedStores = updated
                        replacement.toRecord()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutex.withLock {
                if (cachedStores.any { it.index.storeId == storeId }) {
                    mutableState.value = mutableState.value.copy(
                        issues = mutableState.value.issues.filterNot { it.storeId == storeId } + ExtensionStoreIssue(
                            storeId,
                            error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Extension store refresh failed",
                        ),
                    )
                }
            }
            throw error
        } finally {
            mutex.withLock {
                publish(refreshingStoreIds = mutableState.value.refreshingStoreIds - storeId)
            }
        }
    }

    /** Refreshes every current store independently and preserves each last-known-good index on failure. */
    suspend fun refreshAll(excludedStoreIds: Set<String> = emptySet()): Boolean {
        val storeIds = mutex.withLock {
            cachedStores.map { it.index.storeId }.filterNot(excludedStoreIds::contains)
        }
        var successful = true
        storeIds.forEach { storeId ->
            try {
                refresh(storeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                successful = false
            }
        }
        return successful
    }

    suspend fun remove(storeId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(storeId !in pinnedStoreIds) { "Pinned extension store cannot be removed" }
            require(storeId !in mutableState.value.refreshingStoreIds) { "Extension store is refreshing" }
            val updated = cachedStores.filterNot { it.index.storeId == storeId }
            require(updated.size != cachedStores.size) { "Unknown extension store" }
            persist(updated)
            cachedStores = updated
            publish(
                issues = mutableState.value.issues.filterNot { it.storeId == storeId },
                refreshingStoreIds = mutableState.value.refreshingStoreIds - storeId,
            )
        }
    }

    fun trustedSourceExtensions(): List<TrustedSourceExtension> = state.value.stores
        .flatMap { it.index.releases }
        .mapNotNull(VerifiedExtensionRelease::toTrustedSourceExtension)

    fun trustedThemeReleases(): List<VerifiedExtensionRelease> = state.value.stores
        .flatMap { it.index.releases }
        .filter { it.kind == ExtensionReleaseKind.THEME && it.compatible }

    private suspend fun fetchEnvelope(indexUrl: String): ByteArray {
        envelopeFetcher?.let { return it(indexUrl) }
        return fetchEnvelopeFromNetwork(indexUrl)
    }

    private suspend fun fetchEnvelopeFromNetwork(indexUrl: String): ByteArray = withTimeout(FETCH_TIMEOUT_MILLIS) {
        val request = Request.Builder()
            .url(indexUrl)
            .header("Accept", "application/vnd.paperreader.extension-index+json, application/json")
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).await().use { response ->
            withContext(Dispatchers.IO) {
                require(
                    isSameHttpsOrigin(indexUrl, response.request.url),
                ) {
                    "Extension store redirected to another origin"
                }
                require(response.isSuccessful) { "Extension store returned HTTP ${response.code}" }
                val announcedLength = response.body.contentLength()
                require(announcedLength < 0 || announcedLength <= MAX_ENVELOPE_BYTES) {
                    "Extension store envelope is too large"
                }
                val source = response.body.source()
                require(!source.request(MAX_ENVELOPE_BYTES + 1L)) { "Extension store envelope is too large" }
                source.readByteArray()
            }
        }
    }

    private fun loadRegistry(): Pair<List<CachedStore>, List<ExtensionStoreIssue>> {
        if (!Files.exists(registryFile)) return emptyList<CachedStore>() to emptyList()
        return try {
            require(Files.size(registryFile) <= MAX_REGISTRY_BYTES) { "Extension store registry is too large" }
            val wire = JSON.decodeFromString<RegistryWire>(Files.readAllBytes(registryFile).decodeToString())
            require(wire.schemaVersion == REGISTRY_SCHEMA_VERSION) { "Unsupported extension store registry" }
            require(wire.stores.size <= MAX_STORES) { "Too many extension stores" }
            val issues = mutableListOf<ExtensionStoreIssue>()
            val loaded = wire.stores.mapNotNull { stored ->
                try {
                    val indexUrl = requireIndexUrl(stored.indexUrl)
                    val key = normalizePublicKey(stored.publicKeyBase64)
                    val envelope = Base64.getDecoder().decode(stored.envelopeBase64)
                    require(envelope.size <= MAX_ENVELOPE_BYTES) { "Extension store envelope is too large" }
                    val index = verifier.verify(envelope, key, expectedStoreId = stored.storeId)
                    require(index.sequence == stored.sequence) { "Extension store cache sequence changed" }
                    CachedStore(indexUrl, key, envelope, index)
                } catch (error: Exception) {
                    issues += ExtensionStoreIssue(
                        stored.storeId.takeIf(String::isNotBlank),
                        error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Cached extension store is invalid",
                    )
                    null
                }
            }
            require(loaded.map { it.index.storeId }.distinct().size == loaded.size) { "Duplicate extension store IDs" }
            require(loaded.map(CachedStore::indexUrl).distinct().size == loaded.size) { "Duplicate extension store URLs" }
            val packages = loaded.flatMap { store -> store.index.releases.map(VerifiedExtensionRelease::packageName) }
            require(packages.distinct().size == packages.size) { "Extension package belongs to multiple stores" }
            loaded to issues
        } catch (error: Exception) {
            emptyList<CachedStore>() to listOf(
                ExtensionStoreIssue(
                    null,
                    error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Extension store registry is invalid",
                ),
            )
        }
    }

    private fun persist(stores: List<CachedStore>) {
        require(stores.size <= MAX_STORES)
        val wire = RegistryWire(
            schemaVersion = REGISTRY_SCHEMA_VERSION,
            stores = stores.map { store ->
                StoredStoreWire(
                    storeId = store.index.storeId,
                    indexUrl = store.indexUrl,
                    publicKeyBase64 = store.publicKeyBase64,
                    sequence = store.index.sequence,
                    envelopeBase64 = Base64.getEncoder().encodeToString(store.envelope),
                )
            },
        )
        val bytes = JSON.encodeToString(wire).encodeToByteArray()
        require(bytes.size <= MAX_REGISTRY_BYTES) { "Extension store registry is too large" }
        Files.createDirectories(registryFile.parent)
        val temporary = registryFile.resolveSibling("${registryFile.fileName}.tmp")
        Files.write(temporary, bytes)
        try {
            Files.move(
                temporary,
                registryFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, registryFile, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun publish(
        issues: List<ExtensionStoreIssue> = mutableState.value.issues,
        refreshingStoreIds: Set<String> = mutableState.value.refreshingStoreIds,
    ) {
        mutableState.value = ExtensionStoreRegistryState(
            stores = cachedStores.toRecords(),
            issues = issues,
            refreshingStoreIds = refreshingStoreIds,
        )
    }

    private fun prunePreviews() {
        val oldestAllowed = clock.instant().minus(PREVIEW_LIFETIME)
        pendingPreviews.entries.removeAll { it.value.createdAt.isBefore(oldestAllowed) }
    }

    private fun requireIndexUrl(raw: String): String {
        val normalized = raw.trim()
        val uri = URI(normalized)
        require(
            normalized.length <= 2_048 && uri.scheme == "https" && uri.host != null &&
                uri.userInfo == null && uri.fragment == null,
        ) { "Invalid extension store URL" }
        return normalized
    }

    private fun normalizePublicKey(raw: String): String {
        val normalized = raw.trim()
        require(normalized.none(Char::isWhitespace)) { "Invalid extension store public key" }
        val decoded = Base64.getDecoder().decode(normalized)
        require(decoded.size == 32) { "Invalid extension store public key" }
        return Base64.getEncoder().encodeToString(decoded)
    }

    private data class PendingPreview(
        val createdAt: Instant,
        val indexUrl: String,
        val publicKeyBase64: String,
        val envelope: ByteArray,
        val index: VerifiedExtensionStoreIndex,
    )

    private data class CachedStore(
        val indexUrl: String,
        val publicKeyBase64: String,
        val envelope: ByteArray,
        val index: VerifiedExtensionStoreIndex,
    )

    private fun CachedStore.toRecord() = ExtensionStoreRecord(
        indexUrl = indexUrl,
        index = index,
        pinned = index.storeId in pinnedStoreIds,
    )

    private fun List<CachedStore>.toRecords(): List<ExtensionStoreRecord> =
        map { it.toRecord() }.sortedBy { it.index.displayName }

    private companion object {
        const val REGISTRY_SCHEMA_VERSION = 1
        const val MAX_STORES = 10
        const val MAX_PENDING_PREVIEWS = 3
        const val MAX_ENVELOPE_BYTES = 2L * 1024L * 1024L
        const val MAX_REGISTRY_BYTES = 24L * 1024L * 1024L
        const val FETCH_TIMEOUT_MILLIS = 20_000L
        val PREVIEW_LIFETIME: Duration = Duration.ofMinutes(10)
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
    }
}

internal fun isSameHttpsOrigin(expectedUrl: String, actualUrl: HttpUrl): Boolean {
    val expected = URI(expectedUrl)
    val expectedPort = expected.port.takeIf { it >= 0 } ?: 443
    return actualUrl.isHttps && actualUrl.host.equals(expected.host, ignoreCase = true) && actualUrl.port == expectedPort
}

@Serializable
private data class RegistryWire(
    val schemaVersion: Int,
    val stores: List<StoredStoreWire>,
)

@Serializable
private data class StoredStoreWire(
    val storeId: String,
    val indexUrl: String,
    val publicKeyBase64: String,
    val sequence: Long,
    val envelopeBase64: String,
)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
