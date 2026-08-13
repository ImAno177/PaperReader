package dev.paperreader.app.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.MainActivity
import dev.paperreader.app.R
import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.WorkId
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadablePaperWebViewAndroidTest {
    @Test
    fun capturesNativeSelectionAndRendersHighlightWithJavaScriptDisabledAtRest() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val webView = AtomicReference<ReadablePaperWebView>()
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                val reader = ReadablePaperWebView(activity).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.countDown()
                        }
                    }
                }
                webView.set(reader)
                activity.setContentView(reader)
                reader.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/readable/document.html",
                    renderReadablePaperHtml(
                        sanitizedBodyHtml =
                            "<main class=\"paperreader-document\"><p data-paperreader-block-id=\"prx-b00001\">" +
                                "Large language models make papers easier to inspect.</p></main>",
                        palette = ReadablePaperPalette(
                            background = "#FFFFFF",
                            surface = "#F5F5F5",
                            text = "#111111",
                            mutedText = "#555555",
                            border = "#222222",
                            link = "#0044AA",
                            selection = "#FFEE99",
                        ),
                        dark = false,
                    ),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            assertTrue("Local readable page did not load", loaded.await(10, TimeUnit.SECONDS))

            val rangeCreated = CountDownLatch(1)
            scenario.onActivity {
                webView.get().settings.javaScriptEnabled = true
                webView.get().evaluateJavascript(
                    """
                    (() => {
                      const block = document.querySelector('[data-paperreader-block-id="prx-b00001"]');
                      const node = block.firstChild;
                      const start = node.data.indexOf('language models');
                      const range = document.createRange();
                      range.setStart(node, start);
                      range.setEnd(node, start + 'language models'.length);
                      const selection = window.getSelection();
                      selection.removeAllRanges();
                      selection.addRange(range);
                      return true;
                    })()
                    """.trimIndent(),
                ) {
                    webView.get().settings.javaScriptEnabled = false
                    rangeCreated.countDown()
                }
            }
            assertTrue("Native selection was not created", rangeCreated.await(5, TimeUnit.SECONDS))

            val contextualHighlightFound = AtomicReference(false)
            scenario.onActivity { activity ->
                webView.get().onHighlightSelectionRequested = {}
                val mode = webView.get().startActionMode(
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

                        override fun onDestroyActionMode(mode: ActionMode) = Unit
                    },
                )
                contextualHighlightFound.set(
                    mode?.menu?.let { menu ->
                        (0 until menu.size()).any { index ->
                            menu.getItem(index).title == activity.getString(R.string.readable_reader_context_highlight)
                        }
                    } == true,
                )
                mode?.finish()
            }
            assertTrue("The native selection menu did not expose Highlight", contextualHighlightFound.get())

            val captured = AtomicReference<ReadableSelectionResult>()
            val capturedLatch = CountDownLatch(1)
            scenario.onActivity {
                webView.get().captureTextSelection { result ->
                    captured.set(result)
                    capturedLatch.countDown()
                }
            }
            assertTrue("Selection bridge timed out", capturedLatch.await(5, TimeUnit.SECONDS))
            val selection = (captured.get() as ReadableSelectionResult.Ready).selection
            assertEquals("prx-b00001", selection.blockId)
            assertEquals("language models", selection.quoteExact)
            assertEquals(selection.quoteExact.length, selection.endOffset - selection.startOffset)
            scenario.onActivity { assertFalse(webView.get().settings.javaScriptEnabled) }

            val now = Instant.parse("2026-08-13T00:00:00Z")
            val rendered = AtomicReference<Int>()
            val renderedLatch = CountDownLatch(1)
            scenario.onActivity {
                webView.get().applyAnnotations(
                    listOf(
                        Annotation(
                            id = "ann-device-test",
                            workId = WorkId("work"),
                            documentSha256 = "a".repeat(64),
                            blockId = selection.blockId,
                            startOffset = selection.startOffset,
                            endOffset = selection.endOffset,
                            quotePrefix = selection.quotePrefix,
                            quoteExact = selection.quoteExact,
                            quoteSuffix = selection.quoteSuffix,
                            pageIndex = null,
                            note = "This note must never enter the renderer script.",
                            color = "highlight",
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                ) { count ->
                    rendered.set(count)
                    renderedLatch.countDown()
                }
            }
            assertTrue("Highlight render timed out", renderedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, rendered.get())
            scenario.onActivity { assertFalse(webView.get().settings.javaScriptEnabled) }

            val found = AtomicReference<Boolean>()
            val foundLatch = CountDownLatch(1)
            scenario.onActivity {
                webView.get().scrollToAnnotation("ann-device-test") { exists ->
                    found.set(exists)
                    foundLatch.countDown()
                }
            }
            assertTrue("Highlight lookup timed out", foundLatch.await(5, TimeUnit.SECONDS))
            assertTrue(found.get())
            scenario.onActivity { assertFalse(webView.get().settings.javaScriptEnabled) }
        }
    }
}
