package dev.paperreader.app.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.EditText
import android.text.InputType
import android.content.DialogInterface
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.pdf.view.PdfView
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.paperIconSet
import dev.paperreader.app.withEnglishLocale
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingBookmark
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.ToggleReadingBookmarkResult
import dev.paperreader.logic.task.DownloadedPaper
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfReaderActivity : AppCompatActivity() {
    private lateinit var readerArgs: ReaderArgs
    private var pdfFragment: PaperPdfViewerFragment? = null
    private var pdfView: PdfView? = null
    private var pageCount: Int = 0
    private var documentLoaded = false
    private var restorationReady = false
    private var latestPosition: ReaderPosition? = null
    private var progressSaveJob: Job? = null
    private val sessionState: ReaderSessionViewModel by viewModels()
    private var errorDialog: AlertDialog? = null
    private var bookmarkDialog: AlertDialog? = null
    private var jumpPageDialog: AlertDialog? = null
    private var bookmarks: List<ReadingBookmark> = emptyList()
    private var bookmarkActionRunning = false
    private lateinit var toolbar: Toolbar
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var pageIndicator: TextView
    private var readerResumed = false

    private val viewportListener = object : PdfView.OnViewportChangedListener {
        override fun onViewportChanged(
            firstVisiblePage: Int,
            visiblePagesCount: Int,
            pageLocations: SparseArray<RectF>,
            zoomLevel: Float,
        ) {
            val view = pdfView
            val locations = buildList(pageLocations.size()) {
                for (index in 0 until pageLocations.size()) {
                    val bounds = pageLocations.valueAt(index)
                    add(
                        ReaderPageLocation(
                            pageIndex = pageLocations.keyAt(index),
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        ),
                    )
                }
            }
            val position = calculateDominantReaderPosition(
                firstVisiblePage = firstVisiblePage,
                pageCount = pageCount,
                viewportWidth = view?.width ?: 0,
                viewportHeight = view?.height ?: 0,
                pageLocations = locations,
            ) ?: return
            latestPosition = position
            updatePageIndicator(position)
            updateBookmarkActions()
            if (restorationReady) schedulePositionSave(position)
        }
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_pdf_reader)
        applySystemBarInsets(findViewById(R.id.reader_root))
        loadingIndicator = findViewById(R.id.reader_loading)
        pageIndicator = findViewById(R.id.reader_page_indicator)
        pageIndicator.setOnClickListener { showJumpToPageDialog() }
        configureToolbar(findViewById(R.id.reader_toolbar))
        verifyArtifactAndConfigureReader()
    }

    override fun onResume() {
        super.onResume()
        readerResumed = true
        if (documentLoaded) startReaderSession()
    }

    override fun onPause() {
        readerResumed = false
        pauseReaderSession()
        super.onPause()
    }

    override fun onStop() {
        flushReaderState(includeSession = !isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        bookmarkDialog?.dismiss()
        jumpPageDialog?.dismiss()
        pdfView?.removeOnViewportChangedListener(viewportListener)
        pdfView = null
        super.onDestroy()
    }

    internal fun onPdfViewCreated(view: PdfView) {
        pdfView?.removeOnViewportChangedListener(viewportListener)
        pdfView = view.apply {
            isFormFillingEnabled = false
            addOnViewportChangedListener(viewportListener)
        }
        // FragmentManager adds the PDF view after the static overlay children.
        pageIndicator.bringToFront()
    }

    internal fun onPdfDocumentLoaded(loadedPageCount: Int) {
        if (loadedPageCount <= 0) {
            onPdfDocumentError()
            return
        }
        pageCount = loadedPageCount
        documentLoaded = true
        restorationReady = false
        latestPosition = null
        loadingIndicator.visibility = View.GONE
        pageIndicator.bringToFront()
        // Keep the locator hidden until exact-document state has been restored. Showing page 1
        // during this asynchronous window would make the reader briefly report a false position.
        pageIndicator.visibility = View.GONE
        setReaderActionsEnabled(searchEnabled = true, externalEnabled = true)
        if (readerResumed) startReaderSession()
        prepareReaderState()
    }

    internal fun onPdfDocumentError() {
        documentLoaded = false
        pageIndicator.visibility = View.GONE
        jumpPageDialog?.dismiss()
        pauseReaderSession()
        loadingIndicator.visibility = View.GONE
        setReaderActionsEnabled(searchEnabled = false, externalEnabled = true)
        flushReaderState(includeSession = true)
        if (isFinishing || errorDialog?.isShowing == true) return
        errorDialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_open_failed_title)
            .setMessage(R.string.reader_open_failed_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reader_open_external) { _, _ -> openInAnotherApp() }
            .show()
    }

    private fun configureToolbar(toolbar: Toolbar) {
        this.toolbar = toolbar
        toolbar.title = readerArgs.title
        toolbar.subtitle = getString(R.string.reader_subtitle)
        val icons = paperIconSet(readerArgs.themePreset)
        toolbar.setNavigationIcon(icons.resource(PaperIconKey.BACK))
        toolbar.navigationContentDescription = getString(R.string.back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.pdf_reader_actions)
        toolbar.menu.findItem(R.id.action_search_pdf).setIcon(icons.resource(PaperIconKey.SEARCH))
        toolbar.menu.findItem(R.id.action_toggle_bookmark).setIcon(icons.resource(PaperIconKey.BOOKMARK_ADD))
        toolbar.menu.findItem(R.id.action_view_bookmarks).setIcon(icons.resource(PaperIconKey.BOOKMARKS))
        toolbar.menu.findItem(R.id.action_open_external).setIcon(icons.resource(PaperIconKey.OPEN_EXTERNAL))
        setReaderActionsEnabled(searchEnabled = false, externalEnabled = false)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search_pdf -> {
                    pdfFragment?.let { it.isTextSearchActive = !it.isTextSearchActive }
                    true
                }

                R.id.action_open_external -> {
                    openInAnotherApp()
                    true
                }

                R.id.action_toggle_bookmark -> {
                    toggleCurrentPageBookmark()
                    true
                }

                R.id.action_view_bookmarks -> {
                    showBookmarks()
                    true
                }

                else -> false
            }
        }
    }

    private fun setReaderActionsEnabled(searchEnabled: Boolean, externalEnabled: Boolean) {
        toolbar.menu.findItem(R.id.action_search_pdf)?.isEnabled = searchEnabled
        toolbar.menu.findItem(R.id.action_open_external)?.isEnabled = externalEnabled
        updateBookmarkActions()
    }

    private fun updatePageIndicator(position: ReaderPosition?) {
        if (!::pageIndicator.isInitialized || position == null || !documentLoaded) return
        pageIndicator.text = getString(R.string.reader_page_indicator, position.pageIndex + 1, pageCount)
        pageIndicator.contentDescription = getString(
            R.string.reader_page_indicator_action,
            position.pageIndex + 1,
            pageCount,
        )
    }

    private fun showJumpToPageDialog() {
        if (!documentLoaded || pageCount <= 0 || isFinishing) return
        jumpPageDialog?.dismiss()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.reader_jump_page_hint)
            contentDescription = hint
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setText((latestPosition?.pageIndex?.plus(1) ?: 1).toString())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_jump_to_page)
            .setMessage(R.string.reader_jump_page_hint)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reader_jump_to_page, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val pageIndex = readerPageIndexFromInput(input.text.toString(), pageCount)
                if (pageIndex == null) {
                    input.error = getString(R.string.reader_jump_page_invalid, pageCount)
                    input.requestFocus()
                    return@setOnClickListener
                }
                jumpToPage(pageIndex)
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnDismissListener {
            if (jumpPageDialog === dialog) jumpPageDialog = null
        }
        jumpPageDialog = dialog
        dialog.show()
    }

    private fun jumpToPage(pageIndex: Int) {
        if (!documentLoaded || pageIndex !in 0 until pageCount) return
        pdfView?.scrollToPage(pageIndex)
        calculateReaderPosition(pageIndex, pageCount)?.let { position ->
            latestPosition = position
            updatePageIndicator(position)
            updateBookmarkActions()
            if (restorationReady) schedulePositionSave(position)
        }
    }

    private fun verifyArtifactAndConfigureReader() {
        lifecycleScope.launch {
            val verified = try {
                withContext(Dispatchers.IO) {
                    (application as PaperReaderApplication).logic.downloads
                        .downloadedPaper(readerArgs.manifestationId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val matchesRequest = verified != null &&
                verified.manifestationId == readerArgs.manifestationId &&
                verified.sha256.equals(readerArgs.documentSha256, ignoreCase = true) &&
                verified.contentUri == readerArgs.contentUri.toString()
            if (!matchesRequest) {
                loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PdfReaderActivity, R.string.reader_invalid_request, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            observeBookmarks()
            try {
                configurePdfFragment()
            } catch (_: RuntimeException) {
                onPdfDocumentError()
            } catch (_: LinkageError) {
                onPdfDocumentError()
            }
        }
    }

    private fun configurePdfFragment() {
        val restored = supportFragmentManager.findFragmentByTag(PDF_FRAGMENT_TAG) as? PaperPdfViewerFragment
        val fragment = restored ?: PaperPdfViewerFragment().also { created ->
            supportFragmentManager.beginTransaction()
                .replace(R.id.pdf_fragment_container, created, PDF_FRAGMENT_TAG)
                .commitNow()
        }
        pdfFragment = fragment
        if (fragment.documentUri != readerArgs.contentUri) {
            fragment.documentUri = readerArgs.contentUri
        }
    }

    private fun observeBookmarks() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as PaperReaderApplication).logic.useCases.observeReadingBookmarks
                    .subscribe(
                        workId = readerArgs.workId,
                        manifestationId = readerArgs.manifestationId,
                        documentSha256 = readerArgs.documentSha256,
                    )
                    .collect { observed ->
                        bookmarks = observed
                        updateBookmarkActions()
                    }
            }
        }
    }

    private fun updateBookmarkActions() {
        if (!::toolbar.isInitialized) return
        val currentPage = latestPosition?.pageIndex
        val isBookmarked = currentPage != null && isReaderPageBookmarked(bookmarks, currentPage)
        toolbar.menu.findItem(R.id.action_toggle_bookmark)?.apply {
            isEnabled = documentLoaded && currentPage != null && !bookmarkActionRunning
            title = getString(
                if (isBookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark,
            )
            setIcon(
                paperIconSet(readerArgs.themePreset).resource(
                    if (isBookmarked) PaperIconKey.BOOKMARK_REMOVE else PaperIconKey.BOOKMARK_ADD,
                ),
            )
        }
        toolbar.menu.findItem(R.id.action_view_bookmarks)?.isEnabled = documentLoaded
    }

    private fun toggleCurrentPageBookmark() {
        val position = latestPosition?.takeIf { documentLoaded && it.pageIndex in 0 until pageCount } ?: return
        if (bookmarkActionRunning) return
        bookmarkActionRunning = true
        updateBookmarkActions()
        val app = application as PaperReaderApplication
        val args = readerArgs
        app.applicationIoScope.launch {
            val result = try {
                app.readerWriteMutex.withLock {
                    app.logic.useCases.toggleReadingBookmark.await(
                        workId = args.workId,
                        manifestationId = args.manifestationId,
                        documentSha256 = args.documentSha256,
                        pageIndex = position.pageIndex,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main.immediate) {
                if (isDestroyed) return@withContext
                bookmarkActionRunning = false
                updateBookmarkActions()
                val message = when (result) {
                    is ToggleReadingBookmarkResult.Added -> getString(
                        R.string.reader_bookmark_added,
                        position.pageIndex + 1,
                    )

                    ToggleReadingBookmarkResult.Removed -> getString(
                        R.string.reader_bookmark_removed,
                        position.pageIndex + 1,
                    )

                    else -> getString(R.string.reader_bookmark_failed)
                }
                Toast.makeText(this@PdfReaderActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBookmarks() {
        if (!documentLoaded || isFinishing) return
        bookmarkDialog?.dismiss()
        val visibleBookmarks = boundedReaderBookmarks(bookmarks, pageCount)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.reader_bookmarks_title)
            .setNegativeButton(R.string.reader_close, null)
        if (visibleBookmarks.isEmpty()) {
            builder.setMessage(R.string.reader_bookmarks_empty)
        } else {
            builder.setItems(
                visibleBookmarks.map { getString(R.string.reader_bookmark_page, it.pageIndex + 1) }
                    .toTypedArray(),
            ) { dialog, index ->
                val pageIndex = visibleBookmarks[index].pageIndex
                jumpToPage(pageIndex)
                dialog.dismiss()
            }
        }
        bookmarkDialog = builder.show()
    }

    private fun prepareReaderState() {
        lifecycleScope.launch {
            val app = application as PaperReaderApplication
            val restorePage = withContext(Dispatchers.IO) {
                app.readerWriteMutex.withLock {
                    val logic = app.logic
                    val paper = logic.useCases.getPaper.await(readerArgs.workId) ?: return@withLock null
                    val existing = paper.readingState
                    val page = restorableReaderPage(
                        state = existing,
                        manifestationId = readerArgs.manifestationId,
                        documentSha256 = readerArgs.documentSha256,
                        pageCount = pageCount,
                    )
                    logic.useCases.updateReadingState.await(
                        readerStateForOpen(
                            existing = existing,
                            workId = readerArgs.workId,
                            manifestationId = readerArgs.manifestationId,
                            documentSha256 = readerArgs.documentSha256,
                            now = Instant.now(),
                        ),
                    )
                    page
                }
            }
            restorationReady = true
            if (restorePage != null) {
                jumpToPage(restorePage)
            } else {
                selectInitialReaderPosition(
                    observedPosition = latestPosition,
                    firstVisiblePage = pdfView?.firstVisiblePage ?: 0,
                    pageCount = pageCount,
                )?.let { position ->
                    latestPosition = position
                    schedulePositionSave(position)
                }
            }
            if (latestPosition == null) {
                latestPosition = calculateReaderPosition(0, pageCount)
            }
            pageIndicator.visibility = View.VISIBLE
            updatePageIndicator(latestPosition)
            updateBookmarkActions()
        }
    }

    private fun schedulePositionSave(position: ReaderPosition) {
        progressSaveJob?.cancel()
        progressSaveJob = lifecycleScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MILLIS)
            persistPosition(position)
        }
    }

    private suspend fun persistPosition(position: ReaderPosition) {
        val app = application as PaperReaderApplication
        withContext(Dispatchers.IO) {
            app.readerWriteMutex.withLock {
                persistPositionLocked(app, readerArgs, position)
            }
        }
    }

    private suspend fun persistPositionLocked(
        app: PaperReaderApplication,
        args: ReaderArgs,
        position: ReaderPosition,
    ) {
        val paper = app.logic.useCases.getPaper.await(args.workId) ?: return
        app.logic.useCases.updateReadingState.await(
            readerStateForPosition(
                existing = paper.readingState,
                workId = args.workId,
                manifestationId = args.manifestationId,
                documentSha256 = args.documentSha256,
                position = position,
                now = Instant.now(),
            ),
        )
    }

    private fun flushReaderState(includeSession: Boolean) {
        progressSaveJob?.cancel()
        val position = latestPosition.takeIf { restorationReady }
        val sessionDuration = if (includeSession) {
            sessionState.drain(MINIMUM_READING_SESSION_MILLIS)
        } else {
            null
        }
        if (position == null && sessionDuration == null) return
        val app = application as PaperReaderApplication
        val args = readerArgs
        app.applicationIoScope.launch {
            try {
                app.readerWriteMutex.withLock {
                    position?.let { persistPositionLocked(app, args, it) }
                    sessionDuration?.let { duration ->
                        app.logic.useCases.recordReadingSession.await(
                            workId = args.workId,
                            readAt = Instant.now(),
                            duration = duration,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Reader state is secondary to keeping the local document responsive and intact.
            }
        }
    }

    private fun startReaderSession() {
        if (!documentLoaded) return
        sessionState.resume(SystemClock.elapsedRealtime())
    }

    private fun pauseReaderSession() {
        sessionState.pause(SystemClock.elapsedRealtime())
    }

    private fun openInAnotherApp() {
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(readerArgs.contentUri, PDF_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        viewIntent.clipData = ClipData.newRawUri("Local PDF", readerArgs.contentUri)
        try {
            startActivity(Intent.createChooser(viewIntent, getString(R.string.open_pdf_with)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.reader_external_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun parseArgs(): ReaderArgs? = runCatching {
        val uri = intent?.data ?: return null
        val expectedAuthority = "$packageName.files"
        if (!isTrustedLocalPdfLocation(uri.scheme, uri.authority, expectedAuthority)) return null
        val workId = WorkId(intent.getStringExtra(EXTRA_WORK_ID) ?: return null)
        val manifestationId = ManifestationId(intent.getStringExtra(EXTRA_MANIFESTATION_ID) ?: return null)
        val sha256 = intent.getStringExtra(EXTRA_SHA256)?.lowercase(Locale.ROOT) ?: return null
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.app_name)
        ReaderArgs(
            contentUri = uri,
            workId = workId,
            manifestationId = manifestationId,
            documentSha256 = sha256,
            title = title,
            themePreset = PaperThemePreset.fromStorageKey(intent.getStringExtra(EXTRA_THEME_PRESET)),
        )
    }.getOrNull()

    private data class ReaderArgs(
        val contentUri: Uri,
        val workId: WorkId,
        val manifestationId: ManifestationId,
        val documentSha256: String,
        val title: String,
        val themePreset: PaperThemePreset,
    )

    companion object {
        private const val PDF_FRAGMENT_TAG = "paper_pdf_viewer"
        private const val PDF_MIME_TYPE = "application/pdf"
        private const val EXTRA_WORK_ID = "dev.paperreader.app.reader.WORK_ID"
        private const val EXTRA_MANIFESTATION_ID = "dev.paperreader.app.reader.MANIFESTATION_ID"
        private const val EXTRA_SHA256 = "dev.paperreader.app.reader.SHA256"
        private const val EXTRA_TITLE = "dev.paperreader.app.reader.TITLE"
        internal const val EXTRA_THEME_PRESET = "dev.paperreader.app.reader.THEME_PRESET"
        private const val MAX_TITLE_LENGTH = 240
        private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 750L
        private const val MINIMUM_READING_SESSION_MILLIS = 1_000L

        fun createIntent(
            context: Context,
            downloadedPaper: DownloadedPaper,
            workId: WorkId,
            title: String,
            themePreset: PaperThemePreset,
        ): Intent {
            val uri = downloadedPaper.contentUri.toUri()
            return Intent(context, PdfReaderActivity::class.java).apply {
                setDataAndType(uri, PDF_MIME_TYPE)
                clipData = ClipData.newRawUri("Local PDF", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_WORK_ID, workId.value)
                putExtra(EXTRA_MANIFESTATION_ID, downloadedPaper.manifestationId.value)
                putExtra(EXTRA_SHA256, downloadedPaper.sha256)
                putExtra(EXTRA_TITLE, title.take(MAX_TITLE_LENGTH))
                putExtra(EXTRA_THEME_PRESET, themePreset.storageKey)
            }
        }
    }
}

internal fun readerThemeStyle(preset: PaperThemePreset): Int = when (preset) {
    PaperThemePreset.DOODLE -> R.style.Theme_PaperReader_PdfReader_Doodle
    PaperThemePreset.RETRO -> R.style.Theme_PaperReader_PdfReader_Retro
    PaperThemePreset.NEOBRUTALISM -> R.style.Theme_PaperReader_PdfReader_Neobrutalism
}
