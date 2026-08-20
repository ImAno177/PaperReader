package dev.paperreader.app.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.addCallback
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.paperreader.app.BuildConfig
import dev.paperreader.app.MainActivity
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.SystemBarAppearance
import dev.paperreader.app.ui.theme.setSystemBarAppearance
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A hardened, in-app Google surface that hands arXiv links back to the native import pipeline. */
class GoogleSearchActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isBlank()) {
            finish()
            return
        }
        val canvasColor = intent.getIntExtra(EXTRA_CANVAS_COLOR, DEFAULT_CANVAS_COLOR)
        val inkColor = intent.getIntExtra(EXTRA_INK_COLOR, DEFAULT_INK_COLOR)
        val darkTheme = intent.getBooleanExtra(EXTRA_DARK_THEME, false)
        window.decorView.setBackgroundColor(canvasColor)
        window.setSystemBarAppearance(
            if (darkTheme) SystemBarAppearance.DARK_BACKGROUND else SystemBarAppearance.LIGHT_BACKGROUND,
        )

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.google_search_title)
            setBackgroundColor(canvasColor)
            setTitleTextColor(inkColor)
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            navigationIcon?.setTint(inkColor)
            setNavigationContentDescription(android.R.string.cancel)
            setNavigationOnClickListener { finish() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        webView = WebView(this).apply {
            settings.apply {
                // Google stopped serving useful no-JavaScript result pages in 2026. This activity
                // is isolated in its own process/data directory and never installs a JS bridge.
                setJavaScriptEnabled(true)
                setDomStorageEnabled(true)
                setDatabaseEnabled(false)
                setAllowFileAccess(false)
                setAllowContentAccess(false)
                setAllowFileAccessFromFileURLs(false)
                setAllowUniversalAccessFromFileURLs(false)
                setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
                setCacheMode(WebSettings.LOAD_NO_CACHE)
                setJavaScriptCanOpenWindowsAutomatically(false)
                setMediaPlaybackRequiresUserGesture(true)
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webViewClient = GoogleOnlyWebViewClient()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(progress, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        webView.loadUrl(googleSearchUrl(query))
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class GoogleOnlyWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val arxivUrl = url.arxivResultUrlOrNull()
            return when {
                arxivUrl != null -> handOffToPaperReader(arxivUrl)
                url.isAllowedGoogleNavigation() -> false
                else -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (request.url.toString().isAllowedGoogleResource()) {
                super.shouldInterceptRequest(view, request)
            } else {
                blockedResponse()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                progress.visibility = View.GONE
                Toast.makeText(this@GoogleSearchActivity, R.string.google_search_failed, Toast.LENGTH_SHORT).show()
            }
        }

        private fun handOffToPaperReader(arxivUrl: String): Boolean {
            startActivity(
                Intent(this@GoogleSearchActivity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(arxivUrl)),
            )
            finish()
            return true
        }
    }

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_CANVAS_COLOR = "canvas_color"
        private const val EXTRA_INK_COLOR = "ink_color"
        private const val EXTRA_DARK_THEME = "dark_theme"
        private val DEFAULT_CANVAS_COLOR = Color.rgb(251, 251, 249)
        private val DEFAULT_INK_COLOR = Color.rgb(28, 41, 60)

        fun createIntent(
            context: Context,
            query: String,
            canvasColor: Int,
            inkColor: Int,
            darkTheme: Boolean,
        ): Intent = Intent(context, GoogleSearchActivity::class.java)
            .putExtra(EXTRA_QUERY, query)
            .putExtra(EXTRA_CANVAS_COLOR, canvasColor)
            .putExtra(EXTRA_INK_COLOR, inkColor)
            .putExtra(EXTRA_DARK_THEME, darkTheme)

        internal fun googleSearchUrl(query: String): String =
            "https://www.google.com/search?q=${Uri.encode("site:arxiv.org/abs ${query.trim()}")}&hl=en"
    }
}

internal fun String.isAllowedGoogleNavigation(): Boolean =
    parseHttpsUri()?.let { uri -> uri.host.lowercase() in GOOGLE_NAVIGATION_HOSTS } == true

internal fun String.isAllowedGoogleResource(): Boolean =
    parseHttpsUri()?.host?.lowercase()?.let { host ->
        GOOGLE_RESOURCE_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    } == true

internal fun String.isArxivPaperUrl(): Boolean =
    canonicalArxivAbsUrlOrNull() != null

/** Resolves both direct arXiv links and Google's `/url?q=...` result redirect. */
internal fun String.arxivResultUrlOrNull(): String? {
    canonicalArxivAbsUrlOrNull()?.let { return it }
    val googleUri = parseHttpsUri()?.takeIf { uri ->
        uri.host.lowercase() in GOOGLE_NAVIGATION_HOSTS && uri.path == "/url"
    } ?: return null
    val target = googleUri.rawQuery
        ?.split('&')
        ?.asSequence()
        ?.mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = field.substring(0, separator).decodeUrlComponentOrNull()
                ?: return@mapNotNull null
            if (key !in setOf("q", "url")) return@mapNotNull null
            field.substring(separator + 1).decodeUrlComponentOrNull()
        }
        ?.firstNotNullOfOrNull(String::canonicalArxivAbsUrlOrNull)
    return target
}

private fun String.canonicalArxivAbsUrlOrNull(): String? {
    val uri = parseHttpsUri() ?: return null
    if (!uri.host.equals("arxiv.org", ignoreCase = true) || uri.rawQuery != null) return null
    val match = ARXIV_PAPER_PATH.matchEntire(uri.rawPath ?: return null) ?: return null
    val id = match.groupValues[2].removeSuffix(".pdf")
    return "https://arxiv.org/abs/$id"
}

private fun String.parseHttpsUri(): URI? = runCatching { URI(this) }
    .getOrNull()
    ?.takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host != null &&
        uri.port in setOf(-1, 443)
    }

private fun String.decodeUrlComponentOrNull(): String? = runCatching {
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}.getOrNull()

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    StandardCharsets.UTF_8.name(),
    403,
    "Blocked by PaperReader",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
)

private val GOOGLE_NAVIGATION_HOSTS = setOf("google.com", "www.google.com")
private val GOOGLE_RESOURCE_HOSTS = setOf("google.com", "gstatic.com", "googleusercontent.com")
internal const val GOOGLE_SEARCH_DATA_SUFFIX = "google_search"
private val ARXIV_PAPER_PATH = Regex(
    "^/(abs|html|pdf)/((?:\\d{4}\\.\\d{4,5}|[a-z][a-z0-9.-]*/\\d{7})(?:v\\d+)?(?:\\.pdf)?)/?$",
    RegexOption.IGNORE_CASE,
)
