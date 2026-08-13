package dev.paperreader.logic.plugin

import com.google.crypto.tink.subtle.Ed25519Verify
import dev.paperreader.extensions.api.PaperExtensionContract
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.ThemeExtensionDescriptor
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ExtensionReleaseKind {
    SOURCE,
    THEME,
}

data class VerifiedExtensionStoreIndex(
    val storeId: String,
    val displayName: String,
    val websiteUrl: String,
    val sequence: Long,
    val generatedAt: Instant,
    val publicKeySha256: String,
    val signedPayloadSha256: String,
    val releases: List<VerifiedExtensionRelease>,
)

data class VerifiedExtensionRelease(
    val kind: ExtensionReleaseKind,
    val packageName: String,
    val serviceClassName: String,
    val displayName: String,
    val versionCode: Long,
    val minimumVersionCode: Long,
    val versionName: String,
    val signerSha256: String,
    val minimumHostApi: Int,
    val maximumHostApi: Int,
    val installUrl: String,
    val license: String,
    val privacyUrl: String?,
    val providerId: String? = null,
    val minimumRequestIntervalMillis: Long? = null,
    val sourceCapabilities: Set<SourceCapability> = emptySet(),
    val themeIds: Set<String> = emptySet(),
) {
    val compatible: Boolean
        get() = PaperExtensionContract.API_VERSION in minimumHostApi..maximumHostApi

    fun toTrustedSourceExtension(): TrustedSourceExtension? {
        if (kind != ExtensionReleaseKind.SOURCE || !compatible) return null
        return TrustedSourceExtension(
            packageName = packageName,
            serviceClassName = serviceClassName,
            versionCode = versionCode,
            signerSha256 = signerSha256,
            providerId = requireNotNull(providerId),
            displayName = displayName,
            minimumRequestIntervalMillis = requireNotNull(minimumRequestIntervalMillis),
            capabilities = sourceCapabilities,
            versionName = versionName,
            installUrl = installUrl,
            minimumVersionCode = minimumVersionCode,
        )
    }
}

class ExtensionStoreIndexException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ExtensionStoreIndexVerifier(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(
        envelopeBytes: ByteArray,
        publicKeyBase64: String,
        expectedStoreId: String? = null,
        minimumSequence: Long? = null,
    ): VerifiedExtensionStoreIndex {
        try {
            require(envelopeBytes.size in 1..MAX_ENVELOPE_BYTES) { "Extension store envelope is too large" }
            val publicKey = decodeBase64Exact(publicKeyBase64, PUBLIC_KEY_BYTES, "public key")
            val envelope = JSON.decodeFromString<SignedEnvelopeWire>(envelopeBytes.decodeToString())
            val payload = decodeBase64(envelope.payload, MAX_PAYLOAD_BYTES, "payload")
            val signature = decodeBase64Exact(envelope.signature, SIGNATURE_BYTES, "signature")
            Ed25519Verify(publicKey).verify(signature, payload)
            val index = JSON.decodeFromString<StoreIndexWire>(payload.decodeToString())
            return validate(index, publicKey, payload, expectedStoreId, minimumSequence)
        } catch (error: ExtensionStoreIndexException) {
            throw error
        } catch (error: Exception) {
            throw ExtensionStoreIndexException(
                error.message?.take(180)?.takeIf(String::isNotBlank) ?: "Extension store verification failed",
                error,
            )
        }
    }

    private fun validate(
        index: StoreIndexWire,
        publicKey: ByteArray,
        payload: ByteArray,
        expectedStoreId: String?,
        minimumSequence: Long?,
    ): VerifiedExtensionStoreIndex {
        require(index.schemaVersion == SCHEMA_VERSION) { "Unsupported extension store schema" }
        require(index.storeId.matches(STORE_ID_REGEX)) { "Invalid extension store ID" }
        require(expectedStoreId == null || index.storeId == expectedStoreId) { "Extension store ID changed" }
        require(index.displayName.isNotBlank() && index.displayName.length <= 80) { "Invalid extension store name" }
        requireHttpsUrl(index.websiteUrl, "store website")
        require(index.sequence > 0) { "Invalid extension store sequence" }
        require(minimumSequence == null || index.sequence >= minimumSequence) { "Extension store rollback rejected" }
        val generatedAt = Instant.parse(index.generatedAt)
        require(!generatedAt.isAfter(clock.instant().plus(MAXIMUM_CLOCK_SKEW))) { "Extension store date is in the future" }
        require(index.extensions.size <= MAX_RELEASES) { "Extension store has too many releases" }
        require(index.extensions.map(ExtensionReleaseWire::packageName).distinct().size == index.extensions.size) {
            "Extension store contains duplicate packages"
        }
        val releases = index.extensions.map(::validateRelease)
        return VerifiedExtensionStoreIndex(
            storeId = index.storeId,
            displayName = index.displayName.trim(),
            websiteUrl = index.websiteUrl,
            sequence = index.sequence,
            generatedAt = generatedAt,
            publicKeySha256 = MessageDigest.getInstance("SHA-256").digest(publicKey).toHex(),
            signedPayloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
            releases = releases.sortedWith(compareBy(VerifiedExtensionRelease::displayName, VerifiedExtensionRelease::packageName)),
        )
    }

    private fun validateRelease(release: ExtensionReleaseWire): VerifiedExtensionRelease {
        val kind = runCatching { ExtensionReleaseKind.valueOf(release.kind.uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unknown extension kind") }
        require(release.packageName.matches(PACKAGE_NAME_REGEX)) { "Invalid extension package" }
        require(release.serviceClassName.startsWith("${release.packageName}.") && release.serviceClassName.length <= 300) {
            "Invalid extension service"
        }
        require(release.displayName.isNotBlank() && release.displayName.length <= 80) { "Invalid extension name" }
        require(release.versionCode > 0) { "Invalid extension version code" }
        require(release.minimumVersionCode in 1..release.versionCode) { "Invalid minimum extension version code" }
        require(release.versionName.isNotBlank() && release.versionName.length <= 40) { "Invalid extension version name" }
        val signer = normalizeFingerprint(release.signerSha256)
        require(release.minimumHostApi > 0 && release.maximumHostApi >= release.minimumHostApi) {
            "Invalid extension API range"
        }
        requireHttpsUrl(release.installUrl, "extension install URL")
        require(release.license.isNotBlank() && release.license.length <= 80) { "Invalid extension license" }
        release.privacyUrl?.let { requireHttpsUrl(it, "extension privacy URL") }

        val capabilities = release.sourceCapabilities.mapTo(linkedSetOf()) { wireValue ->
            requireNotNull(SourceCapability.entries.firstOrNull { it.wireValue == wireValue }) {
                "Unknown source capability"
            }
        }
        when (kind) {
            ExtensionReleaseKind.SOURCE -> {
                require(release.themeIds.isEmpty()) { "Source extension cannot declare themes" }
                SourceExtensionDescriptor(
                    packageName = release.packageName,
                    providerId = requireNotNull(release.providerId) { "Source provider ID is required" },
                    displayName = release.displayName,
                    minimumRequestIntervalMillis = requireNotNull(release.minimumRequestIntervalMillis) {
                        "Source request interval is required"
                    },
                    capabilities = capabilities,
                )
            }

            ExtensionReleaseKind.THEME -> {
                require(release.providerId == null && release.minimumRequestIntervalMillis == null && capabilities.isEmpty()) {
                    "Theme extension cannot declare source metadata"
                }
                ThemeExtensionDescriptor(
                    packageName = release.packageName,
                    displayName = release.displayName,
                    themeIds = release.themeIds.toSet(),
                )
            }
        }
        return VerifiedExtensionRelease(
            kind = kind,
            packageName = release.packageName,
            serviceClassName = release.serviceClassName,
            displayName = release.displayName.trim(),
            versionCode = release.versionCode,
            minimumVersionCode = release.minimumVersionCode,
            versionName = release.versionName.trim(),
            signerSha256 = signer,
            minimumHostApi = release.minimumHostApi,
            maximumHostApi = release.maximumHostApi,
            installUrl = release.installUrl,
            license = release.license.trim(),
            privacyUrl = release.privacyUrl,
            providerId = release.providerId,
            minimumRequestIntervalMillis = release.minimumRequestIntervalMillis,
            sourceCapabilities = capabilities,
            themeIds = release.themeIds.toSet(),
        )
    }

    private fun requireHttpsUrl(raw: String, label: String) {
        val uri = URI(raw)
        require(
            uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && raw.length <= 2_048,
        ) { "Invalid $label" }
    }

    private fun decodeBase64(value: String, maximumBytes: Int, label: String): ByteArray {
        require(value.isNotBlank() && value.length <= ((maximumBytes + 2) / 3) * 4 + 4) { "Invalid $label" }
        require(value.none(Char::isWhitespace)) { "Invalid $label" }
        val decoded = Base64.getDecoder().decode(value)
        require(decoded.size in 1..maximumBytes) { "Invalid $label" }
        return decoded
    }

    private fun decodeBase64Exact(value: String, exactBytes: Int, label: String): ByteArray =
        decodeBase64(value, exactBytes, label).also { require(it.size == exactBytes) { "Invalid $label" } }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024
        const val MAX_RELEASES = 200
        val MAXIMUM_CLOCK_SKEW: Duration = Duration.ofMinutes(15)
        val STORE_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{2,63}")
        val PACKAGE_NAME_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        val JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
        }
    }
}

@Serializable
private data class SignedEnvelopeWire(
    val payload: String,
    val signature: String,
)

@Serializable
private data class StoreIndexWire(
    val schemaVersion: Int,
    val storeId: String,
    val displayName: String,
    val websiteUrl: String,
    val sequence: Long,
    val generatedAt: String,
    val extensions: List<ExtensionReleaseWire>,
)

@Serializable
private data class ExtensionReleaseWire(
    val kind: String,
    val packageName: String,
    val serviceClassName: String,
    val displayName: String,
    val versionCode: Long,
    val minimumVersionCode: Long,
    val versionName: String,
    val signerSha256: String,
    val minimumHostApi: Int,
    val maximumHostApi: Int,
    val installUrl: String,
    val license: String,
    val privacyUrl: String? = null,
    val providerId: String? = null,
    val minimumRequestIntervalMillis: Long? = null,
    val sourceCapabilities: List<String> = emptyList(),
    val themeIds: List<String> = emptyList(),
)

private fun normalizeFingerprint(value: String): String {
    val normalized = value.filter(Char::isLetterOrDigit).lowercase()
    require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 fingerprint" }
    return normalized
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
