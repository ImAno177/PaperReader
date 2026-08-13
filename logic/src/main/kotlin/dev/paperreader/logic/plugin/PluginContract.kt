package dev.paperreader.logic.plugin

object ProviderPluginApi {
    const val CURRENT_VERSION: Int = 1
}

enum class PluginCapability {
    SEARCH,
    DETAILS,
    PDF_LINK,
    AUTHENTICATION,
}

data class CommunityPluginDescriptor(
    val packageName: String,
    val serviceClassName: String,
    val displayName: String,
    val apiVersion: Int,
    val capabilities: Set<PluginCapability>,
) {
    init {
        require(packageName.contains('.'))
        require(serviceClassName.isNotBlank())
        require(displayName.isNotBlank())
        require(apiVersion > 0)
    }
}

data class TrustedPluginRelease(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    val minimumApiVersion: Int,
    val maximumApiVersion: Int,
)

sealed interface PluginTrustDecision {
    data object Trusted : PluginTrustDecision
    data object PackageMismatch : PluginTrustDecision
    data object SignerMismatch : PluginTrustDecision
    data object IncompatibleApi : PluginTrustDecision
}

object PluginTrustPolicy {
    fun evaluate(
        installedPackageName: String,
        installedSignerSha256: String,
        hostApiVersion: Int,
        release: TrustedPluginRelease,
    ): PluginTrustDecision {
        if (installedPackageName != release.packageName) return PluginTrustDecision.PackageMismatch
        if (normalizeFingerprint(installedSignerSha256) != normalizeFingerprint(release.signerSha256)) {
            return PluginTrustDecision.SignerMismatch
        }
        if (hostApiVersion !in release.minimumApiVersion..release.maximumApiVersion) {
            return PluginTrustDecision.IncompatibleApi
        }
        return PluginTrustDecision.Trusted
    }

    private fun normalizeFingerprint(value: String): String {
        val normalized = value.filter(Char::isLetterOrDigit).lowercase()
        require(normalized.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 fingerprint" }
        return normalized
    }
}
