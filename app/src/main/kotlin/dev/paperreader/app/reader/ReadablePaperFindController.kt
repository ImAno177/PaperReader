package dev.paperreader.app.reader

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import java.net.URI
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import dev.paperreader.app.R

internal const val LOCAL_RENDERER_HOST = "appassets.androidplatform.net"
internal const val LOCAL_RENDERER_PATH = "/readable/"
internal const val CITATION_SCHEME = "paperreader-citation"
private const val FIND_DEBOUNCE_MILLIS = 150L
private val SAFE_BIBLIOGRAPHY_ANCHOR = Regex("bib\\.[A-Za-z0-9._:-]{1,150}", RegexOption.IGNORE_CASE)

internal class ReadablePaperFindController(
    private val context: Context,
    private val webView: ReadablePaperWebView,
    private val container: View,
    private val queryInput: EditText,
    private val resultLabel: TextView,
    private val previousButton: ImageButton,
    private val nextButton: ImageButton,
    private val closeButton: ImageButton,
) {
    private var pendingSearch: Runnable? = null
    private var currentQuery = ""
    private var dispatchedQuery: String? = null

    val isVisible: Boolean
        get() = container.visibility == View.VISIBLE

    init {
        previousButton.setOnClickListener { webView.findNext(false) }
        nextButton.setOnClickListener { webView.findNext(true) }
        closeButton.setOnClickListener { hide(clearQuery = true) }
        queryInput.doAfterTextChanged { search(it?.toString().orEmpty()) }
        queryInput.setOnEditorActionListener { _, _, _ ->
            if (nextButton.isEnabled) webView.findNext(true)
            true
        }
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting && dispatchedQuery == currentQuery && currentQuery.isNotEmpty()) {
                showResult(activeMatchOrdinal, numberOfMatches)
            }
        }
    }

    fun show() {
        if (isVisible) {
            queryInput.requestFocus()
            showKeyboard()
            return
        }
        container.visibility = View.VISIBLE
        queryInput.requestFocus()
        queryInput.setSelection(queryInput.text.length)
        showKeyboard()
        search(queryInput.text.toString())
    }

    fun hide(clearQuery: Boolean) {
        pendingSearch?.let(queryInput::removeCallbacks)
        pendingSearch = null
        dispatchedQuery = null
        container.visibility = View.GONE
        if (clearQuery) queryInput.text?.clear() else webView.clearMatches()
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        resultLabel.text = ""
        context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(queryInput.windowToken, 0)
        webView.requestFocus()
    }

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        currentQuery = query
        dispatchedQuery = null
        pendingSearch?.let(queryInput::removeCallbacks)
        pendingSearch = null
        if (query.isEmpty()) {
            webView.clearMatches()
            previousButton.isEnabled = false
            nextButton.isEnabled = false
            resultLabel.text = ""
            return
        }
        webView.clearMatches()
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        resultLabel.setText(R.string.readable_reader_searching)
        pendingSearch = Runnable {
            pendingSearch = null
            if (query != currentQuery || !isVisible) return@Runnable
            dispatchedQuery = query
            webView.findAllAsync(query)
        }.also { queryInput.postDelayed(it, FIND_DEBOUNCE_MILLIS) }
    }

    private fun showResult(activeMatchOrdinal: Int, numberOfMatches: Int) {
        val hasMatches = numberOfMatches > 0
        previousButton.isEnabled = hasMatches
        nextButton.isEnabled = hasMatches
        resultLabel.text = if (hasMatches) {
            context.getString(
                R.string.readable_reader_search_count,
                activeMatchOrdinal + 1,
                numberOfMatches,
            )
        } else {
            context.getString(R.string.readable_reader_search_no_results_short)
        }
    }

    private fun showKeyboard() {
        queryInput.post {
            context.getSystemService<InputMethodManager>()?.showSoftInput(queryInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

internal fun isBibliographyAnchor(fragment: String?): Boolean =
    fragment?.matches(SAFE_BIBLIOGRAPHY_ANCHOR) == true

internal fun bibliographyAnchorFromCitationTarget(target: String?): String? {
    val uri = target?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val anchor = uri.path?.removePrefix("/") ?: return null
    return anchor.takeIf {
        uri.scheme == CITATION_SCHEME && uri.host == "anchor" && isBibliographyAnchor(it)
    }
}
