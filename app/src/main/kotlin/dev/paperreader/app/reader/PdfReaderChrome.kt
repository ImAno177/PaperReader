package dev.paperreader.app.reader

import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperIconSet

internal fun applyReaderSystemBarInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}

internal fun applyPdfCommunityChrome(
    root: View,
    toolbar: Toolbar,
    pageIndicator: TextView,
    theme: CommunityPaperTheme?,
) {
    theme ?: return
    val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val palette = theme.palette(dark)
    root.setBackgroundColor(palette.canvas)
    toolbar.setBackgroundColor(palette.surface)
    toolbar.setTitleTextColor(palette.ink)
    toolbar.setSubtitleTextColor(palette.inkMuted)
    pageIndicator.setTextColor(palette.ink)
}

internal fun configurePdfReaderToolbar(
    toolbar: Toolbar,
    title: String,
    icons: PaperIconSet,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggleBookmark: () -> Unit,
    onViewBookmarks: () -> Unit,
) {
    val context = toolbar.context
    toolbar.title = title
    toolbar.subtitle = context.getString(R.string.reader_subtitle)
    toolbar.navigationIcon = icons.drawable(context, PaperIconKey.BACK)
    toolbar.navigationContentDescription = context.getString(R.string.back)
    toolbar.setNavigationOnClickListener { onBack() }
    toolbar.inflateMenu(R.menu.pdf_reader_actions)
    toolbar.menu.findItem(R.id.action_search_pdf).icon = icons.drawable(context, PaperIconKey.SEARCH)
    toolbar.menu.findItem(R.id.action_toggle_bookmark).icon = icons.drawable(context, PaperIconKey.BOOKMARK_ADD)
    toolbar.menu.findItem(R.id.action_view_bookmarks).icon = icons.drawable(context, PaperIconKey.BOOKMARKS)
    toolbar.menu.findItem(R.id.action_open_external).icon = icons.drawable(context, PaperIconKey.OPEN_EXTERNAL)
    toolbar.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_search_pdf -> onSearch()
            R.id.action_open_external -> onOpenExternal()
            R.id.action_toggle_bookmark -> onToggleBookmark()
            R.id.action_view_bookmarks -> onViewBookmarks()
            else -> return@setOnMenuItemClickListener false
        }
        true
    }
}
