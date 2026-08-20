package dev.paperreader.app.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.PaperThemeMode
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
import dev.paperreader.logic.reader.ReadablePaperSection
import dev.paperreader.logic.reader.ReadablePaperWarning
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ReadablePaperActivity : AppCompatActivity() {
    private lateinit var readerArgs: ReadableReaderArgs
    private lateinit var toolbar: Toolbar
    private lateinit var provenance: TextView
    private lateinit var webView: ReadablePaperWebView
    private lateinit var loading: ProgressBar
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorBody: TextView
    private lateinit var findController: ReadablePaperFindController
    private lateinit var annotationController: ReadablePaperAnnotationController
    private lateinit var citationReturnButton: ImageButton
    private val sessionState: ReaderSessionViewModel by viewModels()
    private var currentDocument: ReadablePaperDocument? = null
    private var loadJob: Job? = null
    private var progressSaveJob: Job? = null
    private var pendingRestoreProgression = 0.0
    private var restoredInstanceProgression: Double? = null
    private var restoredDocumentSha256: String? = null
    private var documentLoaded = false
    private var restorationReady = false
    private var readerResumed = false
    private var readerLayout = DEFAULT_READER_LAYOUT
    private var displayedProgressPercent = -1
    private var communityTheme: CommunityPaperTheme? = null
    private lateinit var readerIcons: PaperIconSet
    private var citationReturnScrollY: Int? = null
    private var citationReturnProgression: Double? = null
    private var restoredCitationReturnProgression: Double? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withEnglishLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val requestedTheme = PaperThemePreset.fromStorageKey(intent?.getStringExtra(READABLE_EXTRA_THEME_PRESET))
        delegate.localNightMode = PaperThemeMode.fromStorageKey(
            intent?.getStringExtra(EXTRA_THEME_MODE),
        ).toAppCompatNightMode()
        setTheme(readerThemeStyle(requestedTheme))
        super.onCreate(savedInstanceState)
        val parsedArgs = parseReadableReaderArgs(intent, getString(R.string.app_name))
        if (parsedArgs == null) {
            Toast.makeText(this, R.string.reader_invalid_request, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        readerArgs = parsedArgs
        communityTheme = (application as PaperReaderApplication).themeExtensionManager.theme(readerArgs.themeKey)
        readerIcons = communityTheme?.let { PaperIconSet.community(it.iconPaths) }
            ?: paperIconSet(readerArgs.themePreset)
        val restored = restoreReadableInstanceState(savedInstanceState, readerArgs.manifestationId)
        restoredInstanceProgression = restored.progression
        restoredDocumentSha256 = restored.documentSha256
        restoredCitationReturnProgression = restored.citationReturnProgression
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_readable_paper)
        applyReaderSystemBarInsets(findViewById(R.id.readable_reader_root))
        bindViews()
        applyReadablePaperCommunityChrome(
            root = findViewById(R.id.readable_reader_root),
            toolbar = toolbar,
            provenance = provenance,
            theme = communityTheme,
        )
        configureToolbar()
        configureWebView()
        onBackPressedDispatcher.addCallback(this) {
            if (findController.isVisible) findController.hide(clearQuery = true) else finish()
        }
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
            outState.saveReadableInstanceState(
                readerArgs.manifestationId,
                it.documentSha256,
                webView.currentProgression(),
                citationReturnProgression,
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        progressSaveJob?.cancel()
        if (::annotationController.isInitialized) annotationController.cancel()
        if (::webView.isInitialized) {
            webView.cancelAppOwnedCommand()
            webView.onProgressionChanged = null
            webView.onHighlightSelectionRequested = null
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
        val previous = findViewById<ImageButton>(R.id.readable_reader_find_previous)
        val next = findViewById<ImageButton>(R.id.readable_reader_find_next)
        val close = findViewById<ImageButton>(R.id.readable_reader_find_close)
        previous.setImageDrawable(readerIcons.drawable(this, PaperIconKey.BACK))
        next.setImageDrawable(readerIcons.drawable(this, PaperIconKey.FORWARD))
        close.setImageDrawable(readerIcons.drawable(this, PaperIconKey.CLOSE))
        findController = ReadablePaperFindController(
            context = this,
            webView = webView,
            container = findViewById(R.id.readable_reader_find_bar),
            queryInput = findViewById(R.id.readable_reader_find_query),
            resultLabel = findViewById(R.id.readable_reader_find_result),
            previousButton = previous,
            nextButton = next,
            closeButton = close,
        )
        citationReturnButton = findViewById<ImageButton>(R.id.readable_reader_citation_return).apply {
            setImageDrawable(readerIcons.drawable(this@ReadablePaperActivity, PaperIconKey.BACK))
            imageTintList = ColorStateList.valueOf(
                resolveReadablePaperActionColor(this@ReadablePaperActivity, communityTheme),
            )
            contentDescription = getString(R.string.readable_reader_citation_return)
            setOnClickListener { returnFromCitation() }
        }
        annotationController = ReadablePaperAnnotationController(
            activity = this,
            toolbar = toolbar,
            webView = webView,
            workId = { readerArgs.workId },
            document = { currentDocument },
            documentLoaded = { documentLoaded },
        )
    }

    private fun configureToolbar() {
        configureReadablePaperToolbar(
            toolbar = toolbar,
            title = readerArgs.title,
            icons = readerIcons,
            actions = ReadablePaperToolbarActions(
                navigateBack = {
                    if (findController.isVisible) findController.hide(clearQuery = true) else finish()
                },
                search = findController::show,
                showContents = {
                    showReadablePaperContents(currentDocument?.sections.orEmpty(), ::navigateToSection)
                },
                annotateSelection = annotationController::captureSelection,
                showAnnotations = annotationController::showAnnotations,
                changeLayout = ::showReadingLayoutDialog,
                openOriginalPdf = ::openOriginalPdf,
                openReadableSource = { currentDocument?.sourceUrl?.let(::openSafeReaderExternalUri) },
            ),
        )
    }

    private fun configureWebView() {
        readerLayout = ReadablePaperLayoutPreferences(this).load()
        val webBackground = resolveReadablePaperSurfaceMuted(this, communityTheme)
        configureReadablePaperWebView(
            webView = webView,
            backgroundColor = webBackground,
            textZoom = readerLayout.textZoom,
            hasCurrentDocument = { currentDocument != null },
            isPageLoaded = { documentLoaded },
            annotations = annotationController::annotations,
            onNavigation = ::handleNavigation,
            onPageReady = ::finishReadablePageLoad,
            onProgressionChanged = { progression ->
                updateProgress(progression)
                if (restorationReady) scheduleProgressSave(progression)
            },
            onHighlightSelectionRequested = annotationController::captureSelection,
        )
    }

    private fun handleNavigation(uri: Uri): Boolean {
        bibliographyAnchorFromCitationTarget(uri.toString())?.let { anchor ->
            val hadOrigin = citationReturnProgression != null
            if (!hadOrigin) rememberCitationOrigin()
            citationReturnButton.visibility = View.VISIBLE
            webView.scrollToDocumentAnchor(anchor) { found ->
                if (!found && !hadOrigin) clearCitationReturn()
            }
            return true
        }
        if (uri.scheme == "https" && uri.host == LOCAL_RENDERER_HOST && uri.path == LOCAL_RENDERER_PATH) {
            if (isBibliographyAnchor(uri.fragment)) {
                if (citationReturnProgression == null) rememberCitationOrigin()
                citationReturnButton.visibility = View.VISIBLE
            } else if (uri.fragment != null) {
                clearCitationReturn()
            }
            return false
        }
        if (uri.scheme in setOf("https", "mailto") && uri.userInfo == null) {
            openSafeReaderExternalUri(uri.toString())
        }
        return true
    }

    private fun loadDocument() {
        loadJob?.cancel()
        annotationController.reset()
        documentLoaded = false
        restorationReady = false
        currentDocument = null
        findController.hide(clearQuery = true)
        clearCitationReturn()
        webView.visibility = View.INVISIBLE
        displayedProgressPercent = -1
        toolbar.subtitle = getString(R.string.readable_reader_subtitle)
        provenance.visibility = View.GONE
        errorContainer.visibility = View.GONE
        loading.visibility = View.VISIBLE
        setReadablePaperActionsEnabled(toolbar, source = false, document = false, contents = false)
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
        annotationController.loadInitial(document)
        val exactRestoredProgression = restoredInstanceProgression
            ?.takeIf { restoredDocumentSha256 == document.documentSha256 }
        if (restoredDocumentSha256 != document.documentSha256) restoredCitationReturnProgression = null
        pendingRestoreProgression = exactRestoredProgression ?: databaseProgress ?: 0.0
        restoredInstanceProgression = null
        restoredDocumentSha256 = null
        currentDocument = document
        setReadablePaperActionsEnabled(
            toolbar,
            source = true,
            document = true,
            contents = document.sections.isNotEmpty(),
        )
        annotationController.updateMenu()
        val provenanceText = getString(
            if (document.servedFromCache) {
                R.string.readable_reader_provenance
            } else {
                R.string.readable_reader_provenance_fresh
            },
            document.sourceVersion,
        )
        provenance.text = buildList {
            add(provenanceText)
            document.license?.takeIf(String::isNotBlank)?.let {
                add(getString(R.string.readable_reader_license_line, it))
            }
            if (ReadablePaperWarning.SOURCE_CONVERSION_ARTIFACT_NORMALIZED in document.warnings) {
                add(getString(R.string.readable_reader_conversion_warning))
            }
            if (
                ReadablePaperWarning.FIGURE_UNAVAILABLE in document.warnings ||
                ReadablePaperWarning.FIGURE_LIMIT_REACHED in document.warnings
            ) {
                add(getString(R.string.readable_reader_figure_warning))
            }
            if (ReadablePaperWarning.TABLE_OF_CONTENTS_MISSING in document.warnings) {
                add(getString(R.string.readable_reader_contents_warning))
            }
        }.joinToString("\n")
        provenance.visibility = View.VISIBLE
        loadRenderedDocument(document)
    }

    private fun finishReadablePageLoad() {
        if (isDestroyed || currentDocument == null || documentLoaded) return
        loading.visibility = View.GONE
        webView.visibility = View.VISIBLE
        documentLoaded = true
        webView.restoreProgression(pendingRestoreProgression)
        restoredCitationReturnProgression?.let { progression ->
            citationReturnProgression = progression
            citationReturnButton.visibility = View.VISIBLE
            restoredCitationReturnProgression = null
        }
        webView.postDelayed({
            if (isDestroyed || !documentLoaded) return@postDelayed
            restorationReady = true
            updateProgress(webView.currentProgression())
        }, RESTORE_SETTLE_MILLIS)
        if (readerResumed) sessionState.resume(SystemClock.elapsedRealtime())
        annotationController.observe(checkNotNull(currentDocument))
    }

    private suspend fun loadRenderedDocument(document: ReadablePaperDocument) {
        val palette = resolveReadablePaperPalette(this, communityTheme)
        val dark = isReaderDarkMode()
        val layout = ReadablePaperLayout(readerLayout.textSpacing, readerLayout.sideMargin)
        val renderedHtml = withContext(Dispatchers.Default) {
            renderReadablePaperHtml(
                sanitizedBodyHtml = document.bodyHtml,
                palette = palette,
                dark = dark,
                layout = layout,
            )
        }
        webView.loadDataWithBaseURL(
            READABLE_LOCAL_RENDERER_URL,
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

    private fun returnFromCitation() {
        val scrollY = citationReturnScrollY
        val progression = citationReturnProgression
        if (scrollY == null && progression == null) return
        if (scrollY != null) {
            webView.scrollTo(0, scrollY)
        } else {
            webView.restoreProgression(checkNotNull(progression))
        }
        clearCitationReturn()
    }

    private fun rememberCitationOrigin() {
        citationReturnScrollY = webView.scrollY
        citationReturnProgression = webView.currentProgression()
    }

    private fun clearCitationReturn() {
        citationReturnScrollY = null
        citationReturnProgression = null
        citationReturnButton.visibility = View.GONE
    }

    private fun navigateToSection(section: ReadablePaperSection) {
        findController.hide(clearQuery = true)
        clearCitationReturn()
        webView.scrollToDocumentAnchor(section.anchor) { found ->
            if (!found && !isDestroyed) {
                Toast.makeText(this, R.string.readable_reader_contents_empty, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReadingLayoutDialog() {
        if (!documentLoaded) return
        showReadablePaperLayoutDialog(readerLayout) { selected ->
            val layoutChanged = selected.textSpacing != readerLayout.textSpacing ||
                selected.sideMargin != readerLayout.sideMargin
            readerLayout = selected
            webView.settings.textZoom = readerLayout.textZoom
            ReadablePaperLayoutPreferences(this).save(selected)
            if (layoutChanged) reloadReadableLayout()
        }
    }

    private fun reloadReadableLayout() {
        val document = currentDocument ?: return
        findController.hide(clearQuery = true)
        clearCitationReturn()
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
        openOriginalPaperPdf(application as PaperReaderApplication, readerArgs)
    }

    companion object {
        internal const val EXTRA_THEME_MODE = READABLE_EXTRA_THEME_MODE
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
            themeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
        ): Intent = createReadablePaperIntent(context, workId, manifestationId, title, themePreset, themeKey, themeMode)
    }
}
