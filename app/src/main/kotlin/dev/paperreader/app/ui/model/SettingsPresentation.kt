package dev.paperreader.app.ui.model

data class PaperSettingEntry(
    val key: String,
    val title: String,
    val subtitle: String? = null,
)

fun filterPaperSettings(
    entries: List<PaperSettingEntry>,
    query: String,
): List<PaperSettingEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return entries
    return entries.filter { entry ->
        entry.title.contains(needle, ignoreCase = true) ||
            entry.subtitle?.contains(needle, ignoreCase = true) == true
    }
}
