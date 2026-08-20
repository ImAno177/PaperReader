package dev.paperreader.app.ui.theme

import androidx.annotation.DrawableRes
import dev.paperreader.app.R

/** Explicit semantic mapping keeps icon resources stable if [PaperIconKey] is reordered. */
private val MATERIAL_SYMBOL_RESOURCES: Map<PaperIconKey, Int> = mapOf(
    PaperIconKey.ADD to R.drawable.ic_material_symbol_add,
    PaperIconKey.BACK to R.drawable.ic_material_symbol_back,
    PaperIconKey.BOOKMARK_ADD to R.drawable.ic_material_symbol_bookmark_add,
    PaperIconKey.BOOKMARK_REMOVE to R.drawable.ic_material_symbol_bookmark_remove,
    PaperIconKey.BOOKMARKS to R.drawable.ic_material_symbol_bookmarks,
    PaperIconKey.CLOSE to R.drawable.ic_material_symbol_close,
    PaperIconKey.COPY to R.drawable.ic_material_symbol_copy,
    PaperIconKey.DELETE to R.drawable.ic_material_symbol_delete,
    PaperIconKey.DONE to R.drawable.ic_material_symbol_done,
    PaperIconKey.DOWNLOAD to R.drawable.ic_material_symbol_download,
    PaperIconKey.EDIT to R.drawable.ic_material_symbol_edit,
    PaperIconKey.ERROR to R.drawable.ic_material_symbol_error,
    PaperIconKey.FOLDER to R.drawable.ic_material_symbol_folder,
    PaperIconKey.FORWARD to R.drawable.ic_material_symbol_forward,
    PaperIconKey.GRID to R.drawable.ic_material_symbol_grid,
    PaperIconKey.HISTORY to R.drawable.ic_material_symbol_history,
    PaperIconKey.INFO to R.drawable.ic_material_symbol_info,
    PaperIconKey.LIBRARY to R.drawable.ic_material_symbol_library,
    PaperIconKey.LIST to R.drawable.ic_material_symbol_list,
    PaperIconKey.MARK_READ to R.drawable.ic_material_symbol_mark_read,
    PaperIconKey.MORE_HORIZONTAL to R.drawable.ic_material_symbol_more_horizontal,
    PaperIconKey.MORE_VERTICAL to R.drawable.ic_material_symbol_more_vertical,
    PaperIconKey.NOTIFICATIONS_OFF to R.drawable.ic_material_symbol_notifications_off,
    PaperIconKey.NOTIFICATIONS_ON to R.drawable.ic_material_symbol_notifications_on,
    PaperIconKey.OFFLINE to R.drawable.ic_material_symbol_offline,
    PaperIconKey.OPEN_EXTERNAL to R.drawable.ic_material_symbol_open_external,
    PaperIconKey.PALETTE to R.drawable.ic_material_symbol_palette,
    PaperIconKey.PDF to R.drawable.ic_material_symbol_pdf,
    PaperIconKey.PUBLIC to R.drawable.ic_material_symbol_public,
    PaperIconKey.SEARCH to R.drawable.ic_material_symbol_search,
    PaperIconKey.SORT to R.drawable.ic_material_symbol_sort,
    PaperIconKey.SYNC to R.drawable.ic_material_symbol_sync,
    PaperIconKey.UPDATES to R.drawable.ic_material_symbol_updates,
    PaperIconKey.UPLOAD to R.drawable.ic_material_symbol_upload,
)

internal fun paperIconSet(preset: PaperThemePreset): PaperIconSet = when (preset) {
    PaperThemePreset.NEOBRUTALISM -> PaperIconSet(PaperIconFamily.MATERIAL_SYMBOLS)
}

@DrawableRes
internal fun builtinIconResource(family: PaperIconFamily, key: PaperIconKey): Int = when (family) {
    PaperIconFamily.MATERIAL_SYMBOLS -> requireNotNull(MATERIAL_SYMBOL_RESOURCES[key]) {
        "Missing Material Symbols icon: $key"
    }
    PaperIconFamily.COMMUNITY -> error("Community icons are not Android resources")
}

internal fun requireCompletePaperIconSets() {
    val expected = PaperIconKey.entries.toSet()
    check(MATERIAL_SYMBOL_RESOURCES.keys == expected) { "Material Symbols icon map is incomplete" }
    check(MATERIAL_SYMBOL_RESOURCES.values.all { it != 0 })
}
