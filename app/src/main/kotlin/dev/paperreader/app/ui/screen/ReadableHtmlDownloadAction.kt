package dev.paperreader.app.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.reader.ReadablePaperHtmlFileGateway
import dev.paperreader.app.reader.readablePaperHtmlFileName
import dev.paperreader.app.reader.renderReadablePaperExportHtml
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.reader.ReadablePaperDocument
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReadableHtmlDownloadAction(
    manifestationId: String,
    onLoadReadablePaper: suspend () -> ReadablePaperResult,
    onError: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember(manifestationId) { mutableStateOf(false) }
    var writing by remember(manifestationId) { mutableStateOf(false) }
    var pendingDocument by remember(manifestationId) { mutableStateOf<ReadablePaperDocument?>(null) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { destination ->
        val document = pendingDocument
        pendingDocument = null
        if (destination == null || document == null) {
            writing = false
            return@rememberLauncherForActivityResult
        }
        writing = true
        scope.launch {
            try {
                val html = withContext(Dispatchers.Default) { renderReadablePaperExportHtml(document) }
                withContext(Dispatchers.IO) {
                    ReadablePaperHtmlFileGateway(context.contentResolver).write(destination, html)
                }
                Toast.makeText(context, R.string.readable_reader_html_saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError(R.string.readable_reader_html_save_failed)
            } finally {
                writing = false
            }
        }
    }
    PaperSecondaryButton(
        enabled = !preparing && !writing,
        onClick = {
            if (preparing || writing) return@PaperSecondaryButton
            scope.launch {
                preparing = true
                onError(null)
                try {
                    when (val result = onLoadReadablePaper()) {
                        is ReadablePaperResult.Ready -> {
                            pendingDocument = result.document
                            createDocument.launch(readablePaperHtmlFileName(result.document))
                        }
                        is ReadablePaperResult.Unavailable -> onError(result.reason.messageResource())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onError(R.string.readable_reader_html_save_failed)
                } finally {
                    preparing = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (preparing || writing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
        }
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(
                if (preparing || writing) {
                    R.string.readable_reader_html_preparing
                } else {
                    R.string.readable_reader_download_html
                },
            ),
        )
    }
}

private fun ReadablePaperFailure.messageResource(): Int = when (this) {
    ReadablePaperFailure.PAPER_NOT_FOUND -> R.string.readable_reader_paper_missing
    ReadablePaperFailure.MANIFESTATION_NOT_FOUND -> R.string.readable_reader_manifestation_missing
    ReadablePaperFailure.UNSUPPORTED_SOURCE -> R.string.readable_reader_unsupported
    ReadablePaperFailure.UNVERSIONED_SOURCE -> R.string.readable_reader_unversioned
    ReadablePaperFailure.SOURCE_NOT_FOUND -> R.string.readable_reader_source_missing
    ReadablePaperFailure.RATE_LIMITED -> R.string.readable_reader_rate_limited
    ReadablePaperFailure.OFFLINE_OR_UNAVAILABLE -> R.string.readable_reader_offline
    ReadablePaperFailure.RESPONSE_TOO_LARGE -> R.string.readable_reader_too_large
    ReadablePaperFailure.INVALID_RESPONSE -> R.string.readable_reader_invalid
}
