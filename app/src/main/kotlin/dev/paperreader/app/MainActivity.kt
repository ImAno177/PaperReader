package dev.paperreader.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.paperreader.app.extensions.ExtensionNotificationPublisher
import dev.paperreader.app.importer.IncomingPdfRequest
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.importer.incomingPaperReferencePayloadOrNull
import dev.paperreader.app.importer.incomingPdfUriOrNull
import dev.paperreader.app.ui.PaperReaderApp
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.resolveSystemBarAppearance
import dev.paperreader.app.ui.theme.setSystemBarAppearance
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.logic.PaperReaderLogic
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incomingPdfRequest = MutableStateFlow<IncomingPdfRequest?>(null)
    private val incomingPaperReferenceRequest = MutableStateFlow<IncomingPaperReferenceRequest?>(null)
    private val openUpdatesRequest = MutableStateFlow<Long?>(null)
    private val openExtensionsRequest = MutableStateFlow<Long?>(null)
    private val incomingRequestIds = AtomicLong()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withEnglishLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIncomingPdf(intent)
        acceptIncomingPaperReference(intent)
        acceptOpenUpdates(intent)
        acceptOpenExtensions(intent)
        enableEdgeToEdge()
        val app = application as PaperReaderApplication
        setContent {
            val themeMode by app.preferences.themeMode.collectAsStateWithLifecycle(PaperThemeMode.SYSTEM)
            val systemBarAppearance = resolveSystemBarAppearance(themeMode, isSystemInDarkTheme())
            SideEffect { window.setSystemBarAppearance(systemBarAppearance) }
            val pendingPdfImport by incomingPdfRequest.collectAsStateWithLifecycle()
            val pendingPaperReference by incomingPaperReferenceRequest.collectAsStateWithLifecycle()
            val pendingOpenUpdates by openUpdatesRequest.collectAsStateWithLifecycle()
            val pendingOpenExtensions by openExtensionsRequest.collectAsStateWithLifecycle()
            val logic by produceState<PaperReaderLogic?>(initialValue = null, app) {
                value = withContext(Dispatchers.IO) { app.logic }
            }
            LaunchedEffect(logic) {
                logic?.let { current ->
                    withContext(Dispatchers.IO) { app.downloadWorkScheduler.recover(current) }
                }
            }
            PaperReaderApp(
                preferences = app.preferences,
                themeExtensionManager = app.themeExtensionManager,
                logic = logic,
                downloadWorkScheduler = app.downloadWorkScheduler,
                savedSearchRefreshScheduler = app.savedSearchRefreshScheduler,
                incomingPdfRequest = pendingPdfImport,
                onIncomingPdfConsumed = ::consumeIncomingPdf,
                incomingPaperReferenceRequest = pendingPaperReference,
                onIncomingPaperReferenceConsumed = ::consumeIncomingPaperReference,
                openUpdatesRequestId = pendingOpenUpdates,
                onOpenUpdatesConsumed = ::consumeOpenUpdates,
                openExtensionsRequestId = pendingOpenExtensions,
                onOpenExtensionsConsumed = ::consumeOpenExtensions,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (
            acceptIncomingPdf(intent) ||
            acceptIncomingPaperReference(intent) ||
            acceptOpenUpdates(intent) ||
            acceptOpenExtensions(intent)
        ) {
            setIntent(intent)
        }
    }

    private fun acceptIncomingPdf(source: Intent): Boolean {
        val uri = source.incomingPdfUriOrNull() ?: return false
        incomingPaperReferenceRequest.value = null
        openUpdatesRequest.value = null
        openExtensionsRequest.value = null
        incomingPdfRequest.value = IncomingPdfRequest(incomingRequestIds.incrementAndGet(), uri)
        return true
    }

    private fun consumeIncomingPdf(id: Long) {
        if (incomingPdfRequest.value?.id == id) {
            incomingPdfRequest.value = null
            neutralizeConsumedIntent()
        }
    }

    private fun acceptIncomingPaperReference(source: Intent): Boolean {
        val payload = source.incomingPaperReferencePayloadOrNull() ?: return false
        incomingPdfRequest.value = null
        openUpdatesRequest.value = null
        openExtensionsRequest.value = null
        incomingPaperReferenceRequest.value = IncomingPaperReferenceRequest(
            incomingRequestIds.incrementAndGet(),
            payload,
        )
        return true
    }

    private fun consumeIncomingPaperReference(id: Long) {
        if (incomingPaperReferenceRequest.value?.id != id) return
        incomingPaperReferenceRequest.value = null
        neutralizeConsumedIntent()
    }

    private fun acceptOpenUpdates(source: Intent): Boolean {
        if (source.action != SavedSearchNotificationPublisher.ACTION_OPEN_UPDATES) return false
        incomingPdfRequest.value = null
        incomingPaperReferenceRequest.value = null
        openExtensionsRequest.value = null
        openUpdatesRequest.value = incomingRequestIds.incrementAndGet()
        return true
    }

    private fun consumeOpenUpdates(id: Long) {
        if (openUpdatesRequest.value != id) return
        openUpdatesRequest.value = null
        neutralizeConsumedIntent()
    }

    private fun acceptOpenExtensions(source: Intent): Boolean {
        if (source.action != ExtensionNotificationPublisher.ACTION_OPEN_EXTENSIONS) return false
        incomingPdfRequest.value = null
        incomingPaperReferenceRequest.value = null
        openUpdatesRequest.value = null
        openExtensionsRequest.value = incomingRequestIds.incrementAndGet()
        return true
    }

    private fun consumeOpenExtensions(id: Long) {
        if (openExtensionsRequest.value != id) return
        openExtensionsRequest.value = null
        neutralizeConsumedIntent()
    }

    private fun neutralizeConsumedIntent() {
        setIntent(
            Intent(Intent.ACTION_MAIN)
                .setClass(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_LAUNCHER),
        )
    }
}
