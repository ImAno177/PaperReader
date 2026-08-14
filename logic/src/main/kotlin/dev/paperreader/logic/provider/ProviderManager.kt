package dev.paperreader.logic.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ProviderOrigin {
    BUILT_IN,
    COMMUNITY_PLUGIN,
    LOCAL_IMPORT,
}

data class InstalledProvider(
    val descriptor: ProviderDescriptor,
    val origin: ProviderOrigin,
    val packageName: String? = null,
    val versionCode: Long? = null,
)

data class AvailableProviderPlugin(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
    val providerIds: Set<String>,
    val versionName: String? = null,
    val installedVersionCode: Long? = null,
    val installUrl: String? = null,
    val serviceClassName: String? = null,
    val signerSha256: String? = null,
    val apkSha256: String? = null,
    val apkSizeBytes: Long? = null,
)

data class UntrustedProviderPlugin(
    val packageName: String,
    val signerSha256: String,
    val reason: String,
    val installedVersionCode: Long? = null,
    val updateCanRemediate: Boolean = false,
)

data class OrphanedProviderPlugin(
    val packageName: String,
    val displayName: String,
    val versionCode: Long,
)

data class ProviderManagerState(
    val installed: List<InstalledProvider> = emptyList(),
    val available: List<AvailableProviderPlugin> = emptyList(),
    val untrusted: List<UntrustedProviderPlugin> = emptyList(),
    val orphaned: List<OrphanedProviderPlugin> = emptyList(),
    val disabledProviderIds: Set<String> = emptySet(),
)

interface ProviderManager {
    val state: StateFlow<ProviderManagerState>

    fun get(providerId: String): PaperProvider?

    fun getOrStub(providerId: String): PaperProvider

    fun getAll(): List<PaperProvider>

    fun setDisabledProviderIds(providerIds: Set<String>)
}

internal class MutableProviderManager(
    builtIns: Iterable<PaperProvider>,
) : ProviderManager {
    private data class RuntimeProvider(
        val provider: PaperProvider,
        val origin: ProviderOrigin,
        val packageName: String?,
        val versionCode: Long?,
    )

    private val providers = linkedMapOf<String, RuntimeProvider>()
    private val providerLock = Any()
    private val mutableState = MutableStateFlow(ProviderManagerState())
    override val state: StateFlow<ProviderManagerState> = mutableState.asStateFlow()

    init {
        builtIns.forEach { register(it, ProviderOrigin.BUILT_IN) }
    }

    override fun get(providerId: String): PaperProvider? = synchronized(providerLock) {
        providers[providerId]?.provider
    }

    override fun getOrStub(providerId: String): PaperProvider = get(providerId) ?: MissingPaperProvider(providerId)

    override fun getAll(): List<PaperProvider> = synchronized(providerLock) {
        val disabled = mutableState.value.disabledProviderIds
        providers.filterKeys { it !in disabled }.values.map(RuntimeProvider::provider)
    }

    override fun setDisabledProviderIds(providerIds: Set<String>) {
        require(providerIds.none(String::isBlank))
        mutableState.update { it.copy(disabledProviderIds = providerIds.toSet()) }
    }

    fun register(
        provider: PaperProvider,
        origin: ProviderOrigin,
        packageName: String? = null,
        versionCode: Long? = null,
    ) {
        synchronized(providerLock) {
            val id = provider.descriptor.id
            val existing = providers[id]
            require(existing == null) {
                if (existing?.origin == ProviderOrigin.BUILT_IN && origin != ProviderOrigin.BUILT_IN) {
                    "Community provider cannot replace built-in provider: $id"
                } else {
                    "Provider ID is already registered: $id"
                }
            }
            providers[id] = RuntimeProvider(provider, origin, packageName, versionCode)
            publishInstalled()
        }
    }

    fun unregister(providerId: String, packageName: String? = null) {
        synchronized(providerLock) {
            val existing = providers[providerId] ?: return
            if (packageName != null && existing.packageName != packageName) return
            providers.remove(providerId)
            publishInstalled()
        }
    }

    fun unregisterByOrigin(origin: ProviderOrigin) {
        synchronized(providerLock) {
            providers.entries.removeAll { it.value.origin == origin }
            publishInstalled()
        }
    }

    fun updateAvailable(plugins: List<AvailableProviderPlugin>) {
        mutableState.update { it.copy(available = plugins.sortedBy(AvailableProviderPlugin::displayName)) }
    }

    fun updateUntrusted(plugins: List<UntrustedProviderPlugin>) {
        mutableState.update { it.copy(untrusted = plugins.sortedBy(UntrustedProviderPlugin::packageName)) }
    }

    fun updateOrphaned(plugins: List<OrphanedProviderPlugin>) {
        mutableState.update { it.copy(orphaned = plugins.sortedBy(OrphanedProviderPlugin::displayName)) }
    }

    private fun publishInstalled() {
        val installed = providers.values.map { runtime ->
            InstalledProvider(
                descriptor = runtime.provider.descriptor,
                origin = runtime.origin,
                packageName = runtime.packageName,
                versionCode = runtime.versionCode,
            )
        }.sortedBy { it.descriptor.displayName }
        mutableState.update { it.copy(installed = installed) }
    }
}

private class MissingPaperProvider(providerId: String) : PaperProvider {
    override val descriptor = ProviderDescriptor(
        id = providerId,
        displayName = "Missing provider ($providerId)",
        minimumRequestIntervalMillis = 0,
    )

    override suspend fun search(query: PaperSearchQuery): ProviderPage = unavailable()

    override suspend fun get(recordId: String): RemotePaper? = unavailable()

    private fun <T> unavailable(): T = throw ProviderException.Unavailable(
        IllegalStateException("Provider '${descriptor.id}' is not installed"),
    )
}
