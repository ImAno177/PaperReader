package dev.paperreader.app.extensions

import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads signed extension artifacts while binding connections to DNS answers validated as public. */
internal class VerifiedExtensionApkDownloader(
    resolver: (String) -> List<InetAddress> = ::resolveHostAddresses,
) {
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val client = OkHttpClient.Builder()
        .dns(PublicAddressDns(resolver))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    fun cancel(packageName: String) {
        activeCalls.remove(packageName)?.cancel()
    }

    fun download(
        packageName: String,
        rawUrl: String,
        destination: File,
        expectedSize: Long,
        checkCancelled: () -> Unit,
        onProgress: (Long) -> Unit,
    ) {
        var current = URI(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireSafeDownloadUri(current)
            val request = Request.Builder()
                .url(current.toString())
                .header("User-Agent", "PaperReader/0.1 (Android; +https://github.com/ImAno177/PaperReader)")
                .header("Accept", "application/vnd.android.package-archive")
                .build()
            val call = client.newCall(request)
            activeCalls[packageName] = call
            try {
                checkCancelled()
                call.execute().use { response ->
                    when (response.code) {
                        in 300..399 -> {
                            require(redirectCount < MAX_REDIRECTS) { "Extension download redirected too many times" }
                            val location = response.header("Location")
                                ?.takeIf(String::isNotBlank)
                                ?: error("Extension download redirect has no location")
                            current = current.resolve(location)
                        }

                        200 -> {
                            val contentLength = response.body.contentLength()
                            require(contentLength < 0 || contentLength == expectedSize) {
                                "Extension download length does not match the signed registry"
                            }
                            response.body.byteStream().buffered().use { input ->
                                FileOutputStream(destination).buffered().use { output ->
                                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                    var total = 0L
                                    while (true) {
                                        checkCancelled()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= expectedSize) { "Extension download exceeded its signed size" }
                                        output.write(buffer, 0, count)
                                        onProgress(total)
                                    }
                                    output.flush()
                                    require(total == expectedSize) { "Extension download ended before its signed size" }
                                }
                            }
                            return
                        }

                        else -> error("Extension download failed with HTTP ${response.code}")
                    }
                }
            } finally {
                activeCalls.remove(packageName, call)
                call.cancel()
            }
        }
        error("Extension download failed")
    }
}

internal class PublicAddressDns(
    private val resolveHost: (String) -> List<InetAddress> = ::resolveHostAddresses,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        requireSafeDownloadHost(hostname)
        val addresses = runCatching { resolveHost(hostname) }
            .getOrElse { throw IllegalArgumentException("Extension APK host could not be resolved", it) }
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "Extension APK host does not resolve to a public address"
        }
        return addresses
    }
}

internal fun requireAllowedDownloadUri(
    uri: URI,
    resolveHost: (String) -> List<InetAddress> = ::resolveHostAddresses,
) {
    requireSafeDownloadUri(uri)
    PublicAddressDns(resolveHost).lookup(requireNotNull(uri.host))
}

private fun requireSafeDownloadUri(uri: URI) {
    require(uri.scheme.equals("https", ignoreCase = true)) { "Extension APK URL must use HTTPS" }
    require(uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null) {
        "Extension APK URL contains forbidden authority data"
    }
    requireSafeDownloadHost(requireNotNull(uri.host))
}

private fun requireSafeDownloadHost(rawHost: String) {
    val host = rawHost.lowercase()
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
        "Extension APK host is local"
    }
}

private fun resolveHostAddresses(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val bytes = address.address.map { it.toInt() and 0xff }
    return when (address) {
        is Inet4Address -> isPublicIpv4(bytes)
        is Inet6Address -> {
            val uniqueLocal = (bytes[0] and 0xfe) == 0xfc
            val documentation = bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)
            val mappedIpv4 = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            val compatibleIpv4 = bytes.take(12).all { it == 0 }
            val nat64 = bytes.take(12) == listOf(0x00, 0x64, 0xff, 0x9b) + List(8) { 0 }
            val sixToFour = bytes[0] == 0x20 && bytes[1] == 0x02
            when {
                uniqueLocal || documentation -> false
                mappedIpv4 || compatibleIpv4 || nat64 -> isPublicIpv4(bytes.takeLast(4))
                sixToFour -> isPublicIpv4(bytes.subList(2, 6))
                else -> true
            }
        }
        else -> false
    }
}

private fun isPublicIpv4(bytes: List<Int>): Boolean = when {
    bytes.size != 4 -> false
    bytes[0] == 0 || bytes[0] == 10 || bytes[0] == 127 || bytes[0] >= 224 -> false
    bytes[0] == 100 && bytes[1] in 64..127 -> false
    bytes[0] == 169 && bytes[1] == 254 -> false
    bytes[0] == 172 && bytes[1] in 16..31 -> false
    bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 0 -> false
    bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 2 -> false
    bytes[0] == 192 && bytes[1] == 168 -> false
    bytes[0] == 198 && bytes[1] in 18..19 -> false
    bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100 -> false
    bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113 -> false
    else -> true
}

private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 60_000
private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
