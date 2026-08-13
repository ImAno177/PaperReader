package dev.paperreader.app.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.paperreader.app.R

enum class PaperIconKey {
    ADD,
    BACK,
    BOOKMARK_ADD,
    BOOKMARK_REMOVE,
    BOOKMARKS,
    CLOSE,
    COPY,
    DELETE,
    DONE,
    DOWNLOAD,
    EDIT,
    ERROR,
    FOLDER,
    FORWARD,
    GRID,
    HISTORY,
    INFO,
    LIBRARY,
    LIST,
    MARK_READ,
    MORE_HORIZONTAL,
    MORE_VERTICAL,
    NOTIFICATIONS_OFF,
    NOTIFICATIONS_ON,
    OFFLINE,
    OPEN_EXTERNAL,
    PALETTE,
    PDF,
    PUBLIC,
    SEARCH,
    SORT,
    SYNC,
    UPDATES,
    UPLOAD,
}

enum class PaperIconFamily {
    TABLER,
    MATERIAL_SYMBOLS,
}

@Immutable
data class PaperIconSet(
    val family: PaperIconFamily,
) {
    @DrawableRes
    fun resource(key: PaperIconKey): Int = when (family) {
        PaperIconFamily.TABLER -> TABLER_RESOURCES[key.ordinal]
        PaperIconFamily.MATERIAL_SYMBOLS -> MATERIAL_SYMBOL_RESOURCES[key.ordinal]
    }
}

internal fun paperIconSet(preset: PaperThemePreset): PaperIconSet = PaperIconSet(
    when (preset) {
        PaperThemePreset.DOODLE,
        PaperThemePreset.RETRO,
        -> PaperIconFamily.TABLER
        PaperThemePreset.NEOBRUTALISM -> PaperIconFamily.MATERIAL_SYMBOLS
    },
)

internal val LocalPaperIcons = staticCompositionLocalOf<PaperIconSet> {
    error("PaperReaderTheme must be applied before reading PaperTheme.icons")
}

val PaperTheme.icons: PaperIconSet
    @Composable
    get() = LocalPaperIcons.current

@Composable
fun PaperIcon(
    key: PaperIconKey,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(
        painter = painterResource(PaperTheme.icons.resource(key)),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}

private val TABLER_RESOURCES = intArrayOf(
    R.drawable.ic_tabler_add,
    R.drawable.ic_tabler_back,
    R.drawable.ic_tabler_bookmark_add,
    R.drawable.ic_tabler_bookmark_remove,
    R.drawable.ic_tabler_bookmarks,
    R.drawable.ic_tabler_close,
    R.drawable.ic_tabler_copy,
    R.drawable.ic_tabler_delete,
    R.drawable.ic_tabler_done,
    R.drawable.ic_tabler_download,
    R.drawable.ic_tabler_edit,
    R.drawable.ic_tabler_error,
    R.drawable.ic_tabler_folder,
    R.drawable.ic_tabler_forward,
    R.drawable.ic_tabler_grid,
    R.drawable.ic_tabler_history,
    R.drawable.ic_tabler_info,
    R.drawable.ic_tabler_library,
    R.drawable.ic_tabler_list,
    R.drawable.ic_tabler_mark_read,
    R.drawable.ic_tabler_more_horizontal,
    R.drawable.ic_tabler_more_vertical,
    R.drawable.ic_tabler_notifications_off,
    R.drawable.ic_tabler_notifications_on,
    R.drawable.ic_tabler_offline,
    R.drawable.ic_tabler_open_external,
    R.drawable.ic_tabler_palette,
    R.drawable.ic_tabler_pdf,
    R.drawable.ic_tabler_public,
    R.drawable.ic_tabler_search,
    R.drawable.ic_tabler_sort,
    R.drawable.ic_tabler_sync,
    R.drawable.ic_tabler_updates,
    R.drawable.ic_tabler_upload,
)

private val MATERIAL_SYMBOL_RESOURCES = intArrayOf(
    R.drawable.ic_material_symbol_add,
    R.drawable.ic_material_symbol_back,
    R.drawable.ic_material_symbol_bookmark_add,
    R.drawable.ic_material_symbol_bookmark_remove,
    R.drawable.ic_material_symbol_bookmarks,
    R.drawable.ic_material_symbol_close,
    R.drawable.ic_material_symbol_copy,
    R.drawable.ic_material_symbol_delete,
    R.drawable.ic_material_symbol_done,
    R.drawable.ic_material_symbol_download,
    R.drawable.ic_material_symbol_edit,
    R.drawable.ic_material_symbol_error,
    R.drawable.ic_material_symbol_folder,
    R.drawable.ic_material_symbol_forward,
    R.drawable.ic_material_symbol_grid,
    R.drawable.ic_material_symbol_history,
    R.drawable.ic_material_symbol_info,
    R.drawable.ic_material_symbol_library,
    R.drawable.ic_material_symbol_list,
    R.drawable.ic_material_symbol_mark_read,
    R.drawable.ic_material_symbol_more_horizontal,
    R.drawable.ic_material_symbol_more_vertical,
    R.drawable.ic_material_symbol_notifications_off,
    R.drawable.ic_material_symbol_notifications_on,
    R.drawable.ic_material_symbol_offline,
    R.drawable.ic_material_symbol_open_external,
    R.drawable.ic_material_symbol_palette,
    R.drawable.ic_material_symbol_pdf,
    R.drawable.ic_material_symbol_public,
    R.drawable.ic_material_symbol_search,
    R.drawable.ic_material_symbol_sort,
    R.drawable.ic_material_symbol_sync,
    R.drawable.ic_material_symbol_updates,
    R.drawable.ic_material_symbol_upload,
)

internal fun requireCompletePaperIconSets() {
    check(TABLER_RESOURCES.size == PaperIconKey.entries.size)
    check(MATERIAL_SYMBOL_RESOURCES.size == PaperIconKey.entries.size)
}
