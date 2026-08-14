package dev.paperreader.app.reader

import android.content.Context
import android.content.Intent
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId

internal const val READABLE_EXTRA_WORK_ID = "dev.paperreader.app.reader.READABLE_WORK_ID"
internal const val READABLE_EXTRA_MANIFESTATION_ID = "dev.paperreader.app.reader.READABLE_MANIFESTATION_ID"
internal const val READABLE_EXTRA_TITLE = "dev.paperreader.app.reader.READABLE_TITLE"
internal const val READABLE_EXTRA_THEME_PRESET = "dev.paperreader.app.reader.READABLE_THEME_PRESET"
internal const val READABLE_EXTRA_THEME_MODE = "dev.paperreader.app.reader.READABLE_THEME_MODE"
private const val MAX_READABLE_TITLE_LENGTH = 240

internal data class ReadableReaderArgs(
    val workId: WorkId,
    val manifestationId: ManifestationId,
    val title: String,
    val themePreset: PaperThemePreset,
    val themeKey: String,
    val themeMode: PaperThemeMode,
)

internal fun parseReadableReaderArgs(intent: Intent, fallbackTitle: String): ReadableReaderArgs? = runCatching {
    val workId = WorkId(intent.getStringExtra(READABLE_EXTRA_WORK_ID) ?: return null)
    val manifestationId = ManifestationId(intent.getStringExtra(READABLE_EXTRA_MANIFESTATION_ID) ?: return null)
    val title = intent.getStringExtra(READABLE_EXTRA_TITLE)?.trim()?.take(MAX_READABLE_TITLE_LENGTH)
        ?.takeIf(String::isNotBlank) ?: fallbackTitle
    val themeKey = intent.getStringExtra(READABLE_EXTRA_THEME_PRESET) ?: PaperThemePreset.NEOBRUTALISM.storageKey
    ReadableReaderArgs(
        workId,
        manifestationId,
        title,
        PaperThemePreset.fromStorageKey(themeKey),
        themeKey,
        PaperThemeMode.fromStorageKey(intent.getStringExtra(READABLE_EXTRA_THEME_MODE)),
    )
}.getOrNull()

internal fun createReadablePaperIntent(
    context: Context,
    workId: WorkId,
    manifestationId: ManifestationId,
    title: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
): Intent = Intent(context, ReadablePaperActivity::class.java).apply {
    putExtra(READABLE_EXTRA_WORK_ID, workId.value)
    putExtra(READABLE_EXTRA_MANIFESTATION_ID, manifestationId.value)
    putExtra(READABLE_EXTRA_TITLE, title.take(MAX_READABLE_TITLE_LENGTH))
    putExtra(READABLE_EXTRA_THEME_PRESET, themeKey)
    putExtra(READABLE_EXTRA_THEME_MODE, themeMode.storageKey)
}
