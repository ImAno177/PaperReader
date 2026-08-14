package dev.paperreader.app.ui.theme

import androidx.annotation.DrawableRes
import dev.paperreader.app.R

/** Explicit semantic mapping keeps icon resources stable if [PaperIconKey] is reordered. */
private val TABLER_RESOURCES: Map<PaperIconKey, Int> = mapOf(
    PaperIconKey.ADD to R.drawable.ic_tabler_add,
    PaperIconKey.BACK to R.drawable.ic_tabler_back,
    PaperIconKey.BOOKMARK_ADD to R.drawable.ic_tabler_bookmark_add,
    PaperIconKey.BOOKMARK_REMOVE to R.drawable.ic_tabler_bookmark_remove,
    PaperIconKey.BOOKMARKS to R.drawable.ic_tabler_bookmarks,
    PaperIconKey.CLOSE to R.drawable.ic_tabler_close,
    PaperIconKey.COPY to R.drawable.ic_tabler_copy,
    PaperIconKey.DELETE to R.drawable.ic_tabler_delete,
    PaperIconKey.DONE to R.drawable.ic_tabler_done,
    PaperIconKey.DOWNLOAD to R.drawable.ic_tabler_download,
    PaperIconKey.EDIT to R.drawable.ic_tabler_edit,
    PaperIconKey.ERROR to R.drawable.ic_tabler_error,
    PaperIconKey.FOLDER to R.drawable.ic_tabler_folder,
    PaperIconKey.FORWARD to R.drawable.ic_tabler_forward,
    PaperIconKey.GRID to R.drawable.ic_tabler_grid,
    PaperIconKey.HISTORY to R.drawable.ic_tabler_history,
    PaperIconKey.INFO to R.drawable.ic_tabler_info,
    PaperIconKey.LIBRARY to R.drawable.ic_tabler_library,
    PaperIconKey.LIST to R.drawable.ic_tabler_list,
    PaperIconKey.MARK_READ to R.drawable.ic_tabler_mark_read,
    PaperIconKey.MORE_HORIZONTAL to R.drawable.ic_tabler_more_horizontal,
    PaperIconKey.MORE_VERTICAL to R.drawable.ic_tabler_more_vertical,
    PaperIconKey.NOTIFICATIONS_OFF to R.drawable.ic_tabler_notifications_off,
    PaperIconKey.NOTIFICATIONS_ON to R.drawable.ic_tabler_notifications_on,
    PaperIconKey.OFFLINE to R.drawable.ic_tabler_offline,
    PaperIconKey.OPEN_EXTERNAL to R.drawable.ic_tabler_open_external,
    PaperIconKey.PALETTE to R.drawable.ic_tabler_palette,
    PaperIconKey.PDF to R.drawable.ic_tabler_pdf,
    PaperIconKey.PUBLIC to R.drawable.ic_tabler_public,
    PaperIconKey.SEARCH to R.drawable.ic_tabler_search,
    PaperIconKey.SORT to R.drawable.ic_tabler_sort,
    PaperIconKey.SYNC to R.drawable.ic_tabler_sync,
    PaperIconKey.UPDATES to R.drawable.ic_tabler_updates,
    PaperIconKey.UPLOAD to R.drawable.ic_tabler_upload,
)

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

/** Built-in style policy: illustrative packs use Tabler; all other packs use Material Symbols. */
internal fun paperIconSet(preset: PaperThemePreset): PaperIconSet = PaperIconSet(
    when (preset) {
        PaperThemePreset.DOODLE -> PaperIconFamily.TABLER
        PaperThemePreset.NEOBRUTALISM -> PaperIconFamily.MATERIAL_SYMBOLS
    },
)

@DrawableRes
internal fun builtinIconResource(family: PaperIconFamily, key: PaperIconKey): Int = when (family) {
    PaperIconFamily.TABLER -> requireNotNull(TABLER_RESOURCES[key]) { "Missing Tabler icon: $key" }
    PaperIconFamily.MATERIAL_SYMBOLS -> requireNotNull(MATERIAL_SYMBOL_RESOURCES[key]) {
        "Missing Material Symbols icon: $key"
    }
    PaperIconFamily.COMMUNITY -> error("Community icons are not Android resources")
}

internal fun requireCompletePaperIconSets() {
    val expected = PaperIconKey.entries.toSet()
    check(TABLER_RESOURCES.keys == expected) { "Tabler icon map is incomplete" }
    check(MATERIAL_SYMBOL_RESOURCES.keys == expected) { "Material Symbols icon map is incomplete" }
    check(TABLER_RESOURCES.values.all { it != 0 })
    check(MATERIAL_SYMBOL_RESOURCES.values.all { it != 0 })
}
