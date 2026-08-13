package dev.paperreader.app.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIconSet
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.paperIconSet
import dev.paperreader.app.withEnglishLocale
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.reader.ReadablePaperDocument
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.reader.ReadablePaperWarning
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ReadablePaperActivity : AppCompatActivity() {
    private lateinit var readerArgs: ReaderArgs
    private lateinit var toolbar: Toolbar
    private lateinit var provenance: TextView
    private lateinit var webView: ReadablePaperWebView
    private lateinit var loading: ProgressBar
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorBody: TextView
    private val sessionState: ReaderSessionViewModel by viewModels()
    private var currentDocument: ReadablePaperDocument? = null
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private var pendingRestoreProgression = 0.0
    private var restoredInstanceProgression: Double? = null
    private var documentLoaded = false
    private var restorationReady = false
    private var readerResumed = false
    private var textZoom = DEFAULT_TEXT_ZOOM
    private var textSpacing = ReadableTextSpacing.COMFORTABLE
    private var sideMargin = ReadableSideMargin.COMFORTABLE
    private var displayedProgressPercent = -1
    private var communityTheme: CommunityPaperTheme? = null
    private lateinit var readerIcons: PaperIconSet

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withEnglishLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val requestedTheme = PaperThemePreset.fromStorageKey(intent?.getStringExtra(EXTRA_THEME_PRESET))
        setTheme(readerThemeStyle(requestedTheme))
        super.onCreate(savedInstanceState)
        val parsedArgs = parseArgs()
        if (parsedArgs == null) {
            Toast.makeText(this, R.string.reader_invalid_request, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readerArgs = parsedArgs
        communityTheme = (application as PaperReaderApplication).themeExtensionManager.theme(readerArgs.themeKey)
        readerIcons = communityTheme?.let { PaperIconSet.community(it.iconPaths) }
            ?: paperIconSet(readerArgs.themePreset)
        restoredInstanceProgression = savedInstanceState
            ?.takeIf { it.getString(STATE_MANIFESTATION_ID) == readerArgs.manifestationId.value }
            ?.getDouble(STATE_PROGRESSION)
            ?.coerceIn(0.0, 1.0)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_readable_paper)
        applySystemBarInsets(findViewById(R.id.readable_reader_root))
        bindViews()
        applyCommunityChrome()
        configureToolbar()
        configureWebView()
        findViewById<Button>(R.id.readable_reader_retry).setOnClickListener { loadDocument() }
        findViewById<Button>(R.id.readable_reader_original_pdf).setOnClickListener { openOriginalPdf() }
        loadDocument()
    }

    override fun onResume() {
        super.onResume()
        readerResumed = true
        if (documentLoaded) sessionState.resume(SystemClock.elapsedRealtime())
    }

    override fun onPause() {
        readerResumed = false
        sessionState.pause(SystemClock.elapsedRealtime())
        super.onPause()
    }

    override fun onStop() {
        flushReaderState(includeSession = !isChangingConfigurations)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentDocument?.let {
            outState.putString(STATE_MANIFESTATION_ID, readerArgs.manifestationId.value)
            outState.putDouble(STATE_PROGRESSION, webView.currentProgression())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        progressSaveJob?.cancel()
        if (::webView.isInitialized) {
            webView.onProgressionChanged = null
            webView.webViewClient = WebViewClient()
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.readable_reader_toolbar)
        provenance = findViewById(R.id.readable_reader_provenance)
        webView = findViewById(R.id.readable_reader_webview)
        loading = findViewById(R.id.readable_reader_loading)
        errorContainer = findViewById(R.id.readable_reader_error)
        errorBody = findViewById(R.id.readable_reader_error_body)
    }

    private fun configureToolbar() {
        toolbar.title = readerArgs.title
        toolbar.subtitle = getString(R.string.readable_reader_subtitle)
        toolbar.navigationIcon = readerIcons.drawable(this, PaperIconKey.BACK)
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.readable_reader_actions)
        toolbar.menu.findItem(R.id.action_search_readable).icon = readerIcons.drawable(this, PaperIconKey.SEARCH)
        toolbar.menu.findItem(R.id.action_readable_contents).icon = readerIcons.drawable(this, PaperIconKey.LIST)
        toolbar.menu.findItem(R.id.action_reading_layout).icon = readerIcons.drawable(this, PaperIconKey.PALETTE)
        toolbar.menu.findItem(R.id.action_open_original_pdf).icon =
            readerIcons.drawable(this, PaperIconKey.OPEN_EXTERNAL)
        toolbar.menu.findItem(R.id.action_open_readable_source).icon =
            readerIcons.drawable(this, PaperIconKey.OPEN_EXTERNAL)
        toolbar.menu.findItem(R.id.action_open_readable_source).isEnabled = false
        toolbar.menu.findItem(R.id.action_search_readable).isEnabled = false
        toolbar.menu.findItem(R.id.action_readable_contents).isEnabled = false
        toolbar.menu.findItem(R.id.action_reading_layout).isEnabled = false
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search_readable -> {
                    showSearchDialog()
                    true
                }
                R.id.action_readable_contents -> {
                    showTableOfContents()
                    true
                }
                R.id.action_reading_layout -> {
                    showReadingLayoutDialog()
                    true
                }
                R.id.action_open_original_pdf -> {
                    openOriginalPdf()
                    true
                }
                R.id.action_open_readable_source -> {
                    currentDocument?.sourceUrl?.let(::openExternalUri)
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun configureWebView() {
        val preferences = getSharedPreferences(READER_PREFERENCES, MODE_PRIVATE)
        textZoom = preferences.getInt(PREFERENCE_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            .coerceIn(MINIMUM_TEXT_ZOOM, MAXIMUM_TEXT_ZOOM)
        textSpacing = ReadableTextSpacing.fromStorageKey(preferences.getString(PREFERENCE_TEXT_SPACING, null))
        sideMargin = ReadableSideMargin.fromStorageKey(preferences.getString(PREFERENCE_SIDE_MARGIN, null))
        webView.setBackgroundColor(
            communityTheme?.palette(isDarkMode())?.surfaceMuted
                ?: resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant),
        )
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
            textZoom = this@ReadablePaperActivity.textZoom
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(Uri.parse(url))

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                return if (uri.scheme in setOf("http", "https") && uri.host != LOCAL_RENDERER_HOST) {
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url != LOCAL_RENDERER_URL || currentDocument == null) return
                if (documentLoaded) return
                loading.visibility = View.GONE
                webView.visibility = View.VISIBLE
                documentLoaded = true
                webView.restoreProgression(pendingRestoreProgression)
                webView.postDelayed({
                    if (isDestroyed || !documentLoaded) return@postDelayed
                    restorationReady = true
                    updateProgress(webView.currentProgression())
                }, RESTORE_SETTLE_MILLIS)
                if (readerResumed) sessionState.resume(SystemClock.elapsedRealtime())
            }
        }
        webView.onProgressionChanged = { progression ->
            updateProgress(progression)
            if (restorationReady) scheduleProgressSave(progression)
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (uri.scheme == "https" && uri.host == LOCAL_RENDERER_HOST && uri.path == LOCAL_RENDERER_PATH) {
            return false
        }
        if (uri.scheme in setOf("https", "mailto") && uri.userInfo == null) {
            openExternalUri(uri.toString())
        }
        return true
    }

    private fun loadDocument() {
        loadJob?.cancel()
        documentLoaded = false
        restorationReady = false
        currentDocument = null
        webView.visibility = View.INVISIBLE
        displayedProgressPercent = -1
        toolbar.subtitle = getString(R.string.readable_reader_subtitle)
        provenance.visibility = View.GONE
        errorContainer.visibility = View.GONE
        loading.visibility = View.VISIBLE
        toolbar.menu.findItem(R.id.action_open_readable_source).isEnabled = false
        toolbar.menu.findItem(R.id.action_search_readable).isEnabled = false
        toolbar.menu.findItem(R.id.action_readable_contents).isEnabled = false
        toolbar.menu.findItem(R.id.action_reading_layout).isEnabled = false
        loadJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    (application as PaperReaderApplication).logic.useCases.loadReadablePaper.await(
                        readerArgs.workId,
                        readerArgs.manifestationId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReadablePaperResult.Unavailable(ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE)
            }
            when (result) {
                is ReadablePaperResult.Ready -> showDocument(result.document)
                is ReadablePaperResult.Unavailable -> showFailure(result.reason)
            }
        }
    }

    private suspend fun showDocument(document: ReadablePaperDocument) {
        val databaseProgress = prepareReadingState(document)
        pendingRestoreProgression = restoredInstanceProgression ?: databaseProgress ?: 0.0
        restoredInstanceProgression = null
        currentDocument = document
        toolbar.menu.findItem(R.id.action_open_readable_source).isEnabled = true
        toolbar.menu.findItem(R.id.action_search_readable).isEnabled = true
        toolbar.menu.findItem(R.id.action_readable_contents).isEnabled = document.sections.isNotEmpty()
        toolbar.menu.findItem(R.id.action_reading_layout).isEnabled = true
        val provenanceText = getString(
            when {
                document.warnings.any {
                    it == ReadablePaperWarning.FIGURE_UNAVAILABLE ||
                        it == ReadablePaperWarning.FIGURE_LIMIT_REACHED
                } -> R.string.readable_reader_provenance_warning
                document.servedFromCache -> R.string.readable_reader_provenance
                else -> R.string.readable_reader_provenance_fresh
            },
            document.sourceVersion,
        )
        provenance.text = document.license
            ?.takeIf(String::isNotBlank)
            ?.let { "$provenanceText\n${getString(R.string.readable_reader_license_line, it)}" }
            ?: provenanceText
        provenance.visibility = View.VISIBLE
        loadRenderedDocument(document)
    }

    private suspend fun loadRenderedDocument(document: ReadablePaperDocument) {
        val palette = resolvedPalette()
        val dark = isDarkMode()
        val layout = ReadablePaperLayout(textSpacing, sideMargin)
        val renderedHtml = withContext(Dispatchers.Default) {
            renderReadablePaperHtml(
                sanitizedBodyHtml = document.bodyHtml,
                palette = palette,
                dark = dark,
                layout = layout,
            )
        }
        webView.loadDataWithBaseURL(
            LOCAL_RENDERER_URL,
            renderedHtml,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun showFailure(reason: ReadablePaperFailure) {
        loading.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        errorBody.setText(
            when (reason) {
                ReadablePaperFailure.PAPER_NOT_FOUND -> R.string.readable_reader_paper_missing
                ReadablePaperFailure.MANIFESTATION_NOT_FOUND -> R.string.readable_reader_manifestation_missing
                ReadablePaperFailure.UNSUPPORTED_SOURCE -> R.string.readable_reader_unsupported
                ReadablePaperFailure.UNVERSIONED_SOURCE -> R.string.readable_reader_unversioned
                ReadablePaperFailure.SOURCE_NOT_FOUND -> R.string.readable_reader_source_missing
                ReadablePaperFailure.RATE_LIMITED -> R.string.readable_reader_rate_limited
                ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE -> R.string.readable_reader_offline
                ReadablePaperFailure.RESPONSE_TOO_LARGE -> R.string.readable_reader_too_large
                ReadablePaperFailure.INVALID_RESPONSE -> R.string.readable_reader_invalid
            },
        )
    }

    private suspend fun prepareReadingState(document: ReadablePaperDocument): Double? =
        withContext(Dispatchers.IO) {
            val app = application as PaperReaderApplication
            app.readerWriteMutex.withLock {
                val paper = app.logic.useCases.getPaper.await(readerArgs.workId) ?: return@withLock null
                val existing = paper.readingState
                val restore = restorableReadableProgress(
                    existing,
                    readerArgs.manifestationId,
                    document.documentSha256,
                )
                app.logic.useCases.updateReadingState.await(
                    readableStateForOpen(
                        existing,
                        readerArgs.workId,
                        readerArgs.manifestationId,
                        document.documentSha256,
                        Instant.now(),
                    ),
                )
                restore
            }
        }

    private fun scheduleProgressSave(progression: Double) {
        progressSaveJob?.cancel()
        progressSaveJob = lifecycleScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MILLIS)
            persistProgress(progression)
        }
    }

    private suspend fun persistProgress(progression: Double) {
        val document = currentDocument ?: return
        val app = application as PaperReaderApplication
        withContext(Dispatchers.IO) {
            app.readerWriteMutex.withLock {
                persistProgressLocked(app, document, progression)
            }
        }
    }

    private suspend fun persistProgressLocked(
        app: PaperReaderApplication,
        document: ReadablePaperDocument,
        progression: Double,
    ) {
        val paper = app.logic.useCases.getPaper.await(readerArgs.workId) ?: return
        app.logic.useCases.updateReadingState.await(
            readableStateForProgress(
                paper.readingState,
                readerArgs.workId,
                readerArgs.manifestationId,
                document.documentSha256,
                progression,
                Instant.now(),
            ),
        )
    }

    private fun flushReaderState(includeSession: Boolean) {
        progressSaveJob?.cancel()
        val document = currentDocument.takeIf { restorationReady }
        val progression = document?.let { webView.currentProgression() }
        val sessionDuration = if (includeSession) {
            sessionState.drain(MINIMUM_READING_SESSION_MILLIS)
        } else {
            null
        }
        if (progression == null && sessionDuration == null) return
        val app = application as PaperReaderApplication
        app.applicationIoScope.launch {
            try {
                app.readerWriteMutex.withLock {
                    if (document != null && progression != null) {
                        persistProgressLocked(app, document, progression)
                    }
                    sessionDuration?.let {
                        app.logic.useCases.recordReadingSession.await(readerArgs.workId, Instant.now(), it)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Reading telemetry never blocks or invalidates the cached document.
            }
        }
    }

    private fun updateProgress(progression: Double) {
        val percent = (progression.coerceIn(0.0, 1.0) * 100).roundToInt()
        if (percent == displayedProgressPercent) return
        displayedProgressPercent = percent
        toolbar.subtitle = getString(R.string.readable_reader_subtitle_progress, percent)
    }

    private fun showSearchDialog() {
        if (!documentLoaded) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.readable_reader_search_hint)
            contentDescription = hint
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.readable_reader_search)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.readable_reader_search_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isEmpty()) {
                    input.error = getString(R.string.readable_reader_search_empty)
                    return@setOnClickListener
                }
                webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                    if (!isDoneCounting) return@setFindListener
                    Toast.makeText(
                        this,
                        if (numberOfMatches == 0) {
                            getString(R.string.readable_reader_search_no_results)
                        } else {
                            getString(
                                R.string.readable_reader_search_count,
                                activeMatchOrdinal + 1,
                                numberOfMatches,
                            )
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                webView.findAllAsync(query)
                dialog.dismiss()
            }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun showTableOfContents() {
        val sections = currentDocument?.sections.orEmpty()
        if (sections.isEmpty()) {
            Toast.makeText(this, R.string.readable_reader_contents_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = sections.map { section ->
            val prefix = when (section.level) {
                2 -> "  "
                3 -> "    "
                else -> ""
            }
            "$prefix${section.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.readable_reader_contents)
            .setItems(labels) { dialog, index ->
                navigateToSection(sections[index].title)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateToSection(title: String) {
        webView.setFindListener { _, numberOfMatches, isDoneCounting ->
            if (isDoneCounting && numberOfMatches == 0) {
                Toast.makeText(this, R.string.readable_reader_contents_empty, Toast.LENGTH_SHORT).show()
            }
        }
        webView.findAllAsync(title)
    }

    private fun showReadingLayoutDialog() {
        if (!documentLoaded) return
        val content = layoutInflater.inflate(R.layout.dialog_readable_paper_layout, null)
        val decrease = content.findViewById<Button>(R.id.readable_layout_text_decrease)
        val increase = content.findViewById<Button>(R.id.readable_layout_text_increase)
        val textValue = content.findViewById<TextView>(R.id.readable_layout_text_value)
        val spacingGroup = content.findViewById<RadioGroup>(R.id.readable_layout_spacing_group)
        val marginGroup = content.findViewById<RadioGroup>(R.id.readable_layout_margin_group)
        var selectedZoom = textZoom
        var selectedSpacing = textSpacing
        var selectedMargin = sideMargin

        fun updateControls() {
            textValue.text = getString(R.string.readable_reader_text_size_value, selectedZoom)
            decrease.isEnabled = selectedZoom > MINIMUM_TEXT_ZOOM
            increase.isEnabled = selectedZoom < MAXIMUM_TEXT_ZOOM
            spacingGroup.check(
                when (selectedSpacing) {
                    ReadableTextSpacing.COMPACT -> R.id.readable_layout_spacing_compact
                    ReadableTextSpacing.COMFORTABLE -> R.id.readable_layout_spacing_comfortable
                    ReadableTextSpacing.RELAXED -> R.id.readable_layout_spacing_relaxed
                },
            )
            marginGroup.check(
                when (selectedMargin) {
                    ReadableSideMargin.NARROW -> R.id.readable_layout_margin_narrow
                    ReadableSideMargin.COMFORTABLE -> R.id.readable_layout_margin_comfortable
                    ReadableSideMargin.WIDE -> R.id.readable_layout_margin_wide
                },
            )
        }

        decrease.setOnClickListener {
            selectedZoom = nextReadableTextZoom(selectedZoom, increase = false)
            updateControls()
        }
        increase.setOnClickListener {
            selectedZoom = nextReadableTextZoom(selectedZoom, increase = true)
            updateControls()
        }
        spacingGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedSpacing = when (checkedId) {
                R.id.readable_layout_spacing_compact -> ReadableTextSpacing.COMPACT
                R.id.readable_layout_spacing_relaxed -> ReadableTextSpacing.RELAXED
                else -> ReadableTextSpacing.COMFORTABLE
            }
        }
        marginGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedMargin = when (checkedId) {
                R.id.readable_layout_margin_narrow -> ReadableSideMargin.NARROW
                R.id.readable_layout_margin_wide -> ReadableSideMargin.WIDE
                else -> ReadableSideMargin.COMFORTABLE
            }
        }
        updateControls()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.readable_reader_layout)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.readable_reader_reset_layout, null)
            .setPositiveButton(R.string.readable_reader_apply_layout, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                selectedZoom = DEFAULT_TEXT_ZOOM
                selectedSpacing = ReadableTextSpacing.COMFORTABLE
                selectedMargin = ReadableSideMargin.COMFORTABLE
                updateControls()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val layoutChanged = selectedSpacing != textSpacing || selectedMargin != sideMargin
                textZoom = selectedZoom
                textSpacing = selectedSpacing
                sideMargin = selectedMargin
                webView.settings.textZoom = textZoom
                getSharedPreferences(READER_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putInt(PREFERENCE_TEXT_ZOOM, textZoom)
                    .putString(PREFERENCE_TEXT_SPACING, textSpacing.storageKey)
                    .putString(PREFERENCE_SIDE_MARGIN, sideMargin.storageKey)
                    .apply()
                dialog.dismiss()
                if (layoutChanged) reloadReadableLayout()
            }
        }
        dialog.show()
    }

    private fun reloadReadableLayout() {
        val document = currentDocument ?: return
        pendingRestoreProgression = webView.currentProgression()
        progressSaveJob?.cancel()
        documentLoaded = false
        restorationReady = false
        webView.visibility = View.INVISIBLE
        loading.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch { loadRenderedDocument(document) }
    }

    private fun openOriginalPdf() {
        lifecycleScope.launch {
            val app = application as PaperReaderApplication
            val localAndRemote = try {
                withContext(Dispatchers.IO) {
                    val downloaded = app.logic.downloads.downloadedPaper(readerArgs.manifestationId)
                    val paper = app.logic.useCases.getPaper.await(readerArgs.workId)
                    downloaded to paper?.manifestations
                        ?.firstOrNull { it.id == readerArgs.manifestationId }
                        ?.pdfUrl
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (localAndRemote == null) {
                Toast.makeText(
                    this@ReadablePaperActivity,
                    R.string.readable_reader_original_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val downloaded = localAndRemote.first
            if (downloaded != null) {
                startActivity(
                    PdfReaderActivity.createIntent(
                        this@ReadablePaperActivity,
                        downloaded,
                        readerArgs.workId,
                        readerArgs.title,
                        readerArgs.themePreset,
                        readerArgs.themeKey,
                    ),
                )
                return@launch
            }
            val remoteUrl = localAndRemote.second
            if (remoteUrl != null && Uri.parse(remoteUrl).scheme == "https") {
                openExternalUri(remoteUrl)
            } else {
                Toast.makeText(
                    this@ReadablePaperActivity,
                    R.string.readable_reader_original_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openExternalUri(raw: String) {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        if (uri.scheme !in setOf("https", "mailto") || uri.userInfo != null) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.readable_reader_external_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun resolvedPalette(): ReadablePaperPalette {
        val community = communityTheme?.palette(isDarkMode())
        return ReadablePaperPalette(
            background = (community?.surface
                ?: resolveThemeColor(com.google.android.material.R.attr.colorSurface)).toCssColor(),
            surface = (community?.surfaceMuted
                ?: resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)).toCssColor(),
            text = (community?.ink
                ?: resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)).toCssColor(),
            mutedText = (community?.inkMuted
                ?: resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)).toCssColor(),
            border = (community?.border
                ?: resolveThemeColor(com.google.android.material.R.attr.colorOutline)).toCssColor(),
            link = (community?.primary
                ?: resolveThemeColor(com.google.android.material.R.attr.colorPrimary)).toCssColor(),
            selection = (community?.secondaryContainer
                ?: resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)).toCssColor(),
        )
    }

    private fun applyCommunityChrome() {
        val palette = communityTheme?.palette(isDarkMode()) ?: return
        findViewById<View>(R.id.readable_reader_root).setBackgroundColor(palette.canvas)
        toolbar.setBackgroundColor(palette.surface)
        toolbar.setTitleTextColor(palette.ink)
        toolbar.setSubtitleTextColor(palette.inkMuted)
        provenance.setBackgroundColor(palette.primaryContainer)
        provenance.setTextColor(palette.onPrimaryContainer)
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        check(theme.resolveAttribute(attribute, value, true)) { "Missing theme color $attribute" }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun parseArgs(): ReaderArgs? = runCatching {
        val workId = WorkId(intent.getStringExtra(EXTRA_WORK_ID) ?: return null)
        val manifestationId = ManifestationId(intent.getStringExtra(EXTRA_MANIFESTATION_ID) ?: return null)
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.app_name)
        ReaderArgs(
            workId,
            manifestationId,
            title,
            PaperThemePreset.fromStorageKey(intent.getStringExtra(EXTRA_THEME_PRESET)),
            intent.getStringExtra(EXTRA_THEME_PRESET) ?: PaperThemePreset.NEOBRUTALISM.storageKey,
        )
    }.getOrNull()

    private data class ReaderArgs(
        val workId: WorkId,
        val manifestationId: ManifestationId,
        val title: String,
        val themePreset: PaperThemePreset,
        val themeKey: String,
    )

    companion object {
        private const val EXTRA_WORK_ID = "dev.paperreader.app.reader.READABLE_WORK_ID"
        private const val EXTRA_MANIFESTATION_ID = "dev.paperreader.app.reader.READABLE_MANIFESTATION_ID"
        private const val EXTRA_TITLE = "dev.paperreader.app.reader.READABLE_TITLE"
        private const val EXTRA_THEME_PRESET = "dev.paperreader.app.reader.READABLE_THEME_PRESET"
        private const val STATE_MANIFESTATION_ID = "readable_manifestation_id"
        private const val STATE_PROGRESSION = "readable_progression"
        private const val READER_PREFERENCES = "readable-reader"
        private const val PREFERENCE_TEXT_ZOOM = "text-zoom"
        private const val PREFERENCE_TEXT_SPACING = "text-spacing"
        private const val PREFERENCE_SIDE_MARGIN = "side-margin"
        private const val LOCAL_RENDERER_HOST = "appassets.androidplatform.net"
        private const val LOCAL_RENDERER_PATH = "/readable/"
        private const val LOCAL_RENDERER_URL = "https://$LOCAL_RENDERER_HOST$LOCAL_RENDERER_PATH"
        private const val DEFAULT_TEXT_ZOOM = 100
        private const val MINIMUM_TEXT_ZOOM = 85
        private const val MAXIMUM_TEXT_ZOOM = 200
        private const val MAX_TITLE_LENGTH = 240
        private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 750L
        private const val RESTORE_SETTLE_MILLIS = 120L
        private const val MINIMUM_READING_SESSION_MILLIS = 1_000L

        fun createIntent(
            context: Context,
            workId: WorkId,
            manifestationId: ManifestationId,
            title: String,
            themePreset: PaperThemePreset,
            themeKey: String = themePreset.storageKey,
        ): Intent = Intent(context, ReadablePaperActivity::class.java).apply {
            putExtra(EXTRA_WORK_ID, workId.value)
            putExtra(EXTRA_MANIFESTATION_ID, manifestationId.value)
            putExtra(EXTRA_TITLE, title.take(MAX_TITLE_LENGTH))
            putExtra(EXTRA_THEME_PRESET, themeKey)
        }
    }
}

private fun Int.toCssColor(): String = String.format(Locale.ROOT, "#%06X", this and 0xFFFFFF)
