package dev.paperreader.app.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperIconSet
import dev.paperreader.logic.reader.ReadablePaperSection
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReadablePaperToolbarActions(
    val navigateBack: () -> Unit,
    val search: () -> Unit,
    val showContents: () -> Unit,
    val annotateSelection: () -> Unit,
    val showAnnotations: () -> Unit,
    val changeLayout: () -> Unit,
    val openOriginalPdf: () -> Unit,
    val openReadableSource: () -> Unit,
)

internal data class ReadablePaperPrimaryActionStyle(
    val container: Int,
    val content: Int,
    val border: Int,
    val cornerRadiusDp: Float,
    val borderWidthDp: Float,
)

internal fun MaterialButton.configureReadableCitationReturn(
    icons: PaperIconSet,
    style: ReadablePaperPrimaryActionStyle,
    onClick: () -> Unit,
) {
    icon = icons.drawable(context, PaperIconKey.BACK)
    iconTint = ColorStateList.valueOf(style.content)
    backgroundTintList = ColorStateList.valueOf(style.container)
    strokeColor = ColorStateList.valueOf(style.border)
    strokeWidth = (style.borderWidthDp * resources.displayMetrics.density).roundToInt()
    cornerRadius = (style.cornerRadiusDp * resources.displayMetrics.density).roundToInt()
    setTextColor(style.content)
    contentDescription = context.getString(R.string.readable_reader_citation_return)
    setOnClickListener { onClick() }
}

internal fun configureReadablePaperToolbar(
    toolbar: Toolbar,
    title: String,
    icons: PaperIconSet,
    actions: ReadablePaperToolbarActions,
) {
    val context = toolbar.context
    toolbar.title = title
    constrainReaderToolbarTitle(toolbar, title)
    toolbar.subtitle = context.getString(R.string.readable_reader_subtitle)
    toolbar.navigationIcon = icons.drawable(context, PaperIconKey.BACK)
    toolbar.navigationContentDescription = context.getString(R.string.back)
    toolbar.setNavigationOnClickListener { actions.navigateBack() }
    toolbar.inflateMenu(R.menu.readable_reader_actions)
    toolbar.menu.findItem(R.id.action_search_readable).icon = icons.drawable(context, PaperIconKey.SEARCH)
    toolbar.menu.findItem(R.id.action_readable_contents).icon = icons.drawable(context, PaperIconKey.LIST)
    toolbar.menu.findItem(R.id.action_annotate_selection).icon = icons.drawable(context, PaperIconKey.EDIT)
    toolbar.menu.findItem(R.id.action_readable_annotations).icon = icons.drawable(context, PaperIconKey.BOOKMARKS)
    toolbar.menu.findItem(R.id.action_reading_layout).icon = icons.drawable(context, PaperIconKey.PALETTE)
    toolbar.menu.findItem(R.id.action_open_original_pdf).icon = icons.drawable(context, PaperIconKey.OPEN_EXTERNAL)
    toolbar.menu.findItem(R.id.action_open_readable_source).icon = icons.drawable(context, PaperIconKey.OPEN_EXTERNAL)
    toolbar.menu.findItem(R.id.action_readable_contents).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    setReadablePaperActionsEnabled(toolbar, source = false, document = false, contents = false)
    tintReaderToolbarIcons(toolbar, context.resolveReaderToolbarIconColor())
    toolbar.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_search_readable -> actions.search()
            R.id.action_readable_contents -> actions.showContents()
            R.id.action_annotate_selection -> actions.annotateSelection()
            R.id.action_readable_annotations -> actions.showAnnotations()
            R.id.action_reading_layout -> actions.changeLayout()
            R.id.action_open_original_pdf -> actions.openOriginalPdf()
            R.id.action_open_readable_source -> actions.openReadableSource()
            else -> return@setOnMenuItemClickListener false
        }
        true
    }
}

internal fun tintReaderToolbarIcons(toolbar: Toolbar, color: Int) {
    toolbar.navigationIcon?.let { DrawableCompat.setTint(it, color) }
    for (index in 0 until toolbar.menu.size()) {
        toolbar.menu.getItem(index).icon?.let { DrawableCompat.setTint(it, color) }
    }
}

internal fun constrainReaderToolbarTitle(toolbar: Toolbar, title: String) {
    for (index in 0 until toolbar.childCount) {
        val child = toolbar.getChildAt(index) as? TextView ?: continue
        if (child.text?.toString() != title) continue
        child.maxLines = 1
        child.ellipsize = TextUtils.TruncateAt.END
        child.setHorizontallyScrolling(false)
        return
    }
}

internal fun Context.resolveReaderToolbarIconColor(): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, value, true))
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}

internal fun setReadablePaperActionsEnabled(
    toolbar: Toolbar,
    source: Boolean,
    document: Boolean,
    contents: Boolean,
) {
    toolbar.menu.findItem(R.id.action_open_readable_source).isEnabled = source
    toolbar.menu.findItem(R.id.action_search_readable).isEnabled = document
    toolbar.menu.findItem(R.id.action_readable_contents).isEnabled = document && contents
    toolbar.menu.findItem(R.id.action_annotate_selection).isEnabled = document
    toolbar.menu.findItem(R.id.action_readable_annotations).isEnabled = document
    toolbar.menu.findItem(R.id.action_reading_layout).isEnabled = document
}

internal fun resolveReadablePaperPalette(
    activity: Activity,
    communityTheme: CommunityPaperTheme?,
): ReadablePaperPalette {
    val community = communityTheme?.palette(activity.isReaderDarkMode())
    return ReadablePaperPalette(
        background = (community?.surface
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorSurface)).toCssColor(),
        surface = (community?.surfaceMuted
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)).toCssColor(),
        text = (community?.ink
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)).toCssColor(),
        mutedText = (community?.inkMuted
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)).toCssColor(),
        border = (community?.border
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOutline)).toCssColor(),
        link = (community?.primary
            ?: activity.resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)).toCssColor(),
        selection = (community?.secondaryContainer
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)).toCssColor(),
    )
}

internal fun resolveReadablePaperSurfaceMuted(
    activity: Activity,
    communityTheme: CommunityPaperTheme?,
): Int = communityTheme?.palette(activity.isReaderDarkMode())?.surfaceMuted
    ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)

internal fun resolveReadablePaperActionColor(
    activity: Activity,
    communityTheme: CommunityPaperTheme?,
): Int = communityTheme?.palette(activity.isReaderDarkMode())?.ink
    ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)

internal fun resolveReadablePaperPrimaryActionStyle(
    activity: Activity,
    communityTheme: CommunityPaperTheme?,
): ReadablePaperPrimaryActionStyle {
    val communityPalette = communityTheme?.palette(activity.isReaderDarkMode())
    return ReadablePaperPrimaryActionStyle(
        container = communityPalette?.primary
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorSecondary),
        content = communityPalette?.onPrimary
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOnSecondary),
        border = communityPalette?.border
            ?: activity.resolveThemeColor(com.google.android.material.R.attr.colorOutline),
        cornerRadiusDp = communityTheme?.definition?.cornerRadiusDp ?: 4f,
        borderWidthDp = communityTheme?.definition?.borderWidthDp ?: 2f,
    )
}

internal fun applyReadablePaperCommunityChrome(
    root: View,
    toolbar: Toolbar,
    provenance: TextView,
    theme: CommunityPaperTheme?,
) {
    theme ?: return
    val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val palette = theme.palette(dark)
    root.setBackgroundColor(palette.canvas)
    toolbar.setBackgroundColor(palette.surface)
    toolbar.setTitleTextColor(palette.ink)
    toolbar.setSubtitleTextColor(palette.inkMuted)
    provenance.setBackgroundColor(palette.primaryContainer)
    provenance.setTextColor(palette.onPrimaryContainer)
    tintReaderToolbarIcons(toolbar, palette.ink)
}

internal fun Activity.openSafeReaderExternalUri(raw: String) {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
    if (uri.scheme !in setOf("https", "mailto") || uri.userInfo != null) return
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.readable_reader_external_unavailable, Toast.LENGTH_LONG).show()
    }
}

internal fun AppCompatActivity.showReadablePaperContents(
    sections: List<ReadablePaperSection>,
    onSelected: (ReadablePaperSection) -> Unit,
) {
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
            onSelected(sections[index])
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun AppCompatActivity.openOriginalPaperPdf(
    app: PaperReaderApplication,
    args: ReadableReaderArgs,
) {
    lifecycleScope.launch {
        val localAndRemote = try {
            withContext(Dispatchers.IO) {
                val downloaded = app.logic.downloads.downloadedPaper(args.manifestationId)
                val paper = app.logic.useCases.getPaper.await(args.workId)
                downloaded to paper?.manifestations
                    ?.firstOrNull { it.id == args.manifestationId }
                    ?.pdfUrl
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (localAndRemote == null) {
            showOriginalUnavailable()
            return@launch
        }
        val downloaded = localAndRemote.first
        if (downloaded != null) {
            startActivity(
                PdfReaderActivity.createIntent(
                    this@openOriginalPaperPdf,
                    downloaded,
                    args.workId,
                    args.title,
                    args.themePreset,
                    args.themeKey,
                    args.themeMode,
                ),
            )
            return@launch
        }
        val remoteUrl = localAndRemote.second
        if (remoteUrl != null && Uri.parse(remoteUrl).scheme == "https") {
            openSafeReaderExternalUri(remoteUrl)
        } else {
            showOriginalUnavailable()
        }
    }
}

internal fun Activity.isReaderDarkMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

private fun Activity.resolveThemeColor(attribute: Int): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) { "Missing theme color $attribute" }
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}

private fun Int.toCssColor(): String = String.format(Locale.ROOT, "#%06X", this and 0xFFFFFF)

private fun AppCompatActivity.showOriginalUnavailable() {
    Toast.makeText(this, R.string.readable_reader_original_unavailable, Toast.LENGTH_LONG).show()
}
