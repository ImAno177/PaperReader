package dev.paperreader.app.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.paperreader.logic.domain.Annotation
import java.io.ByteArrayInputStream

@Suppress("DEPRECATION", "SetJavaScriptEnabled")
internal fun configureReadablePaperWebView(
    webView: ReadablePaperWebView,
    backgroundColor: Int,
    textZoom: Int,
    hasCurrentDocument: () -> Boolean,
    isPageLoaded: () -> Boolean,
    annotations: () -> List<Annotation>,
    onNavigation: (Uri) -> Boolean,
    onPageReady: () -> Unit,
    onProgressionChanged: (Double) -> Unit,
    onHighlightSelectionRequested: () -> Unit,
) {
    webView.setBackgroundColor(backgroundColor)
    webView.settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportMultipleWindows(false)
        builtInZoomControls = false
        displayZoomControls = false
        setSupportZoom(false)
        loadsImagesAutomatically = true
        mediaPlaybackRequiresUserGesture = true
        saveFormData = false
        safeBrowsingEnabled = true
        this.textZoom = textZoom
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            onNavigation(request.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            onNavigation(Uri.parse(url))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            return if (uri.scheme in setOf("http", "https") && uri.host != LOCAL_RENDERER_HOST) {
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url != READABLE_LOCAL_RENDERER_URL || !hasCurrentDocument() || isPageLoaded()) return
            webView.applyAnnotations(annotations()) { onPageReady() }
        }
    }
    webView.onProgressionChanged = onProgressionChanged
    webView.onHighlightSelectionRequested = onHighlightSelectionRequested
}

internal const val READABLE_LOCAL_RENDERER_URL = "https://$LOCAL_RENDERER_HOST$LOCAL_RENDERER_PATH"
