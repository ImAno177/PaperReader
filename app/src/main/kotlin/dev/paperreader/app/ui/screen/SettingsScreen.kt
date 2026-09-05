package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperPreferenceRow
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.PaperSettingEntry
import dev.paperreader.app.ui.model.filterPaperSettings
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey

@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenDataBackup: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val options = listOf(
        SettingOption(PaperSettingEntry("appearance", stringResource(R.string.appearance_title), stringResource(R.string.appearance_display_summary)), PaperIconKey.PALETTE, onOpenAppearance),
        SettingOption(PaperSettingEntry("library", stringResource(R.string.library_settings_title), stringResource(R.string.library_settings_summary)), PaperIconKey.LIBRARY, onOpenLibrary),
        SettingOption(PaperSettingEntry("reader", stringResource(R.string.reader_settings_title), stringResource(R.string.reader_settings_summary)), PaperIconKey.HISTORY, onOpenReader),
        SettingOption(PaperSettingEntry("downloads", stringResource(R.string.downloads_settings_title), stringResource(R.string.downloads_settings_summary)), PaperIconKey.DOWNLOAD, onOpenDownloads),
        SettingOption(PaperSettingEntry("updates", stringResource(R.string.updates_settings_title), stringResource(R.string.updates_settings_summary)), PaperIconKey.UPDATES, onOpenUpdates),
        SettingOption(PaperSettingEntry("data", stringResource(R.string.data_settings_title), stringResource(R.string.data_settings_summary)), PaperIconKey.DOWNLOAD, onOpenDataBackup),
        SettingOption(PaperSettingEntry("sources", stringResource(R.string.sources_settings_title), stringResource(R.string.sources_settings_summary)), PaperIconKey.PUBLIC, onOpenSources),
        SettingOption(PaperSettingEntry("about", stringResource(R.string.about_title), stringResource(R.string.about_description)), PaperIconKey.INFO, onOpenAbout),
    )
    val visibleOptions = filterPaperSettings(options.map(SettingOption::entry), query)
        .mapNotNull { entry -> options.firstOrNull { it.entry.key == entry.key } }

    MoreBranchScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_search_label)) },
                leadingIcon = { PaperIcon(PaperIconKey.SEARCH, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            PaperIcon(PaperIconKey.CLOSE, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        }
        if (visibleOptions.isEmpty()) {
            item {
                PaperStatePanel(
                    title = stringResource(R.string.settings_no_results),
                    icon = PaperIconKey.SEARCH,
                    compact = true,
                )
            }
        } else {
            item {
                PaperSurface(contentPadding = PaddingValues(0.dp)) {
                    visibleOptions.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(color = dev.paperreader.app.ui.theme.PaperTheme.tokens.border.copy(alpha = 0.24f))
                        PaperPreferenceRow(
                            title = option.entry.title,
                            supportingText = option.entry.subtitle,
                            icon = option.icon,
                            onClick = option.onClick,
                        )
                    }
                }
            }
        }
    }
}

private data class SettingOption(
    val entry: PaperSettingEntry,
    val icon: PaperIconKey,
    val onClick: () -> Unit,
)
