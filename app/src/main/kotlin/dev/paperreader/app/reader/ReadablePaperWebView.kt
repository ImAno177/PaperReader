package dev.paperreader.app.reader

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import dev.paperreader.app.R
import dev.paperreader.logic.domain.Annotation
import kotlin.math.roundToInt
import org.json.JSONObject

internal class ReadablePaperWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {
    var onProgressionChanged: ((Double) -> Unit)? = null
    var onHighlightSelectionRequested: (() -> Unit)? = null
    private var appCommandSerial = 0
    private var appCommandTimeout: Runnable? = null

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        onProgressionChanged?.invoke(currentProgression())
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(HighlightSelectionActionModeCallback(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(HighlightSelectionActionModeCallback(callback), type)

    fun currentProgression(): Double {
        val maximumScroll = (computeVerticalScrollRange() - height).coerceAtLeast(0)
        if (maximumScroll <= 0) return 0.0
        return (scrollY / maximumScroll.toDouble()).coerceIn(0.0, 1.0)
    }

    fun restoreProgression(progression: Double) {
        val bounded = progression.coerceIn(0.0, 1.0)
        post {
            val maximumScroll = (computeVerticalScrollRange() - height).coerceAtLeast(0)
            scrollTo(0, (maximumScroll * bounded).roundToInt())
        }
    }

    fun scrollToDocumentAnchor(anchor: String, onResult: (Boolean) -> Unit) {
        runAppOwnedCommand(readableSectionNavigationScript(anchor)) { result ->
            onResult(result == "true")
        }
    }

    internal fun captureTextSelection(onResult: (ReadableSelectionResult) -> Unit) {
        runAppOwnedCommand(readableSelectionCaptureScript()) { raw ->
            onResult(parseReadableSelection(raw))
        }
    }

    fun applyAnnotations(annotations: List<Annotation>, onResult: (Int) -> Unit = {}) {
        runAppOwnedCommand(readableAnnotationRenderScript(annotations)) { raw ->
            onResult(raw?.toIntOrNull() ?: 0)
        }
    }

    fun scrollToAnnotation(id: String, onResult: (Boolean) -> Unit) {
        runAppOwnedCommand(readableAnnotationNavigationScript(id)) { raw -> onResult(raw == "true") }
    }

    fun clearTextSelection() {
        runAppOwnedCommand(
            "(() => { const selection = window.getSelection(); " +
                "if (selection) selection.removeAllRanges(); return true; })()",
        ) {}
    }

    fun cancelAppOwnedCommand() {
        appCommandSerial += 1
        appCommandTimeout?.let(::removeCallbacks)
        appCommandTimeout = null
        settings.javaScriptEnabled = false
    }

    private fun runAppOwnedCommand(script: String, onResult: (String?) -> Unit) {
        cancelAppOwnedCommand()
        val serial = ++appCommandSerial
        // App-owned scripts run only against the sanitized, network-blocked local document.
        // codeql[java/android/websettings-javascript-enabled]
        settings.javaScriptEnabled = true
        val timeout = Runnable { finishAppOwnedCommand(serial, null, onResult) }
        appCommandTimeout = timeout
        postDelayed(timeout, APP_COMMAND_TIMEOUT_MILLIS)
        evaluateJavascript(script) { result -> finishAppOwnedCommand(serial, result, onResult) }
    }

    private fun finishAppOwnedCommand(serial: Int, result: String?, onResult: (String?) -> Unit) {
        if (serial != appCommandSerial) return
        appCommandTimeout?.let(::removeCallbacks)
        appCommandTimeout = null
        appCommandSerial += 1
        settings.javaScriptEnabled = false
        onResult(result)
    }

    private inner class HighlightSelectionActionModeCallback(
        private val delegate: ActionMode.Callback,
    ) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = delegate.onCreateActionMode(mode, menu)
            if (
                created &&
                onHighlightSelectionRequested != null &&
                menu.findItem(HIGHLIGHT_SELECTION_ACTION_ID) == null
            ) {
                menu.add(
                    Menu.NONE,
                    HIGHLIGHT_SELECTION_ACTION_ID,
                    HIGHLIGHT_SELECTION_ACTION_ORDER,
                    context.getString(R.string.readable_reader_context_highlight),
                ).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == HIGHLIGHT_SELECTION_ACTION_ID) {
                onHighlightSelectionRequested?.invoke()
                return true
            }
            return delegate.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = delegate.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val positioned = delegate as? ActionMode.Callback2
            if (positioned != null) {
                positioned.onGetContentRect(mode, view, outRect)
            } else {
                super.onGetContentRect(mode, view, outRect)
            }
        }
    }

    companion object {
        private const val APP_COMMAND_TIMEOUT_MILLIS = 1_500L
        private const val HIGHLIGHT_SELECTION_ACTION_ID = 0x50525801
        private const val HIGHLIGHT_SELECTION_ACTION_ORDER = 80
    }
}

private fun parseReadableSelection(raw: String?): ReadableSelectionResult {
    val json = runCatching { raw?.let(::JSONObject) }.getOrNull()
        ?: return ReadableSelectionResult.Unavailable(ReadableSelectionFailure.INVALID)
    return when (json.optString("status")) {
        "ready" -> runCatching {
            ReadableSelectionResult.Ready(
                ReadableTextSelection(
                    blockId = json.getString("blockId"),
                    startOffset = json.getInt("startOffset"),
                    endOffset = json.getInt("endOffset"),
                    quotePrefix = json.getString("quotePrefix"),
                    quoteExact = json.getString("quoteExact"),
                    quoteSuffix = json.getString("quoteSuffix"),
                ),
            )
        }.getOrElse { ReadableSelectionResult.Unavailable(ReadableSelectionFailure.INVALID) }
        "empty" -> ReadableSelectionResult.Unavailable(ReadableSelectionFailure.EMPTY)
        "cross_block" -> ReadableSelectionResult.Unavailable(ReadableSelectionFailure.CROSS_BLOCK)
        "too_long" -> ReadableSelectionResult.Unavailable(ReadableSelectionFailure.TOO_LONG)
        else -> ReadableSelectionResult.Unavailable(ReadableSelectionFailure.INVALID)
    }
}
