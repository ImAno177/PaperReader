package dev.paperreader.app.reader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.task.DownloadedPaper
import java.util.Locale

internal const val PDF_READER_EXTRA_WORK_ID = "dev.paperreader.app.reader.WORK_ID"
internal const val PDF_READER_EXTRA_MANIFESTATION_ID = "dev.paperreader.app.reader.MANIFESTATION_ID"
internal const val PDF_READER_EXTRA_SHA256 = "dev.paperreader.app.reader.SHA256"
internal const val PDF_READER_EXTRA_TITLE = "dev.paperreader.app.reader.TITLE"
internal const val PDF_READER_EXTRA_THEME_PRESET = "dev.paperreader.app.reader.THEME_PRESET"
internal const val PDF_READER_EXTRA_THEME_MODE = "dev.paperreader.app.reader.THEME_MODE"
internal const val PDF_MIME_TYPE = "application/pdf"
private const val MAX_TITLE_LENGTH = 240

internal data class PdfReaderArgs(
    val contentUri: Uri,
    val workId: WorkId,
    val manifestationId: ManifestationId,
    val documentSha256: String,
    val title: String,
    val themePreset: PaperThemePreset,
    val themeKey: String,
    val themeMode: PaperThemeMode,
)

internal fun parsePdfReaderArgs(intent: Intent?, packageName: String, fallbackTitle: String): PdfReaderArgs? = runCatching {
    val uri = intent?.data ?: return null
    if (!isTrustedLocalPdfLocation(uri.scheme, uri.authority, "$packageName.files")) return null
    val workId = WorkId(intent.getStringExtra(PDF_READER_EXTRA_WORK_ID) ?: return null)
    val manifestationId = ManifestationId(intent.getStringExtra(PDF_READER_EXTRA_MANIFESTATION_ID) ?: return null)
    val sha256 = intent.getStringExtra(PDF_READER_EXTRA_SHA256)?.lowercase(Locale.ROOT) ?: return null
    require(sha256.matches(Regex("[0-9a-f]{64}")))
    val title = intent.getStringExtra(PDF_READER_EXTRA_TITLE)?.trim()?.take(MAX_TITLE_LENGTH)
        ?.takeIf(String::isNotBlank) ?: fallbackTitle
    val themeKey = intent.getStringExtra(PDF_READER_EXTRA_THEME_PRESET) ?: PaperThemePreset.NEOBRUTALISM.storageKey
    PdfReaderArgs(
        uri,
        workId,
        manifestationId,
        sha256,
        title,
        PaperThemePreset.fromStorageKey(themeKey),
        themeKey,
        PaperThemeMode.fromStorageKey(intent.getStringExtra(PDF_READER_EXTRA_THEME_MODE)),
    )
}.getOrNull()

internal fun createPdfReaderIntent(
    context: Context,
    downloadedPaper: DownloadedPaper,
    workId: WorkId,
    title: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
): Intent {
    val uri = downloadedPaper.contentUri.toUri()
    return Intent(context, PdfReaderActivity::class.java).apply {
        setDataAndType(uri, PDF_MIME_TYPE)
        clipData = ClipData.newRawUri("Local PDF", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(PDF_READER_EXTRA_WORK_ID, workId.value)
        putExtra(PDF_READER_EXTRA_MANIFESTATION_ID, downloadedPaper.manifestationId.value)
        putExtra(PDF_READER_EXTRA_SHA256, downloadedPaper.sha256)
        putExtra(PDF_READER_EXTRA_TITLE, title.take(MAX_TITLE_LENGTH))
        putExtra(PDF_READER_EXTRA_THEME_PRESET, themeKey)
        putExtra(PDF_READER_EXTRA_THEME_MODE, themeMode.storageKey)
    }
}
