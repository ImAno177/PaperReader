package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.extensions.ThemeExtensionIssue
import dev.paperreader.app.ui.TabletUiMode
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.paperThemeTokens
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.model.PaperDateFormat

@Composable
fun AppearanceScreen(
    selectedThemeKey: String,
    communityThemes: List<CommunityPaperTheme>,
    communityThemesLoading: Boolean,
    communityThemeIssues: List<ThemeExtensionIssue>,
    selectedThemeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (PaperThemeMode) -> Unit,
    selectedLibraryLayout: LibraryLayout = LibraryLayout.LIST,
    onLibraryLayoutChange: (LibraryLayout) -> Unit = {},
    selectedDateFormat: PaperDateFormat = PaperDateFormat.DEFAULT,
    onDateFormatChange: (PaperDateFormat) -> Unit = {},
    relativeTimeEnabled: Boolean = false,
    onRelativeTimeChange: (Boolean) -> Unit = {},
    tabletUiMode: TabletUiMode = TabletUiMode.AUTOMATIC,
    onTabletUiModeChange: (TabletUiMode) -> Unit = {},
    showImagesInDescription: Boolean = true,
    onShowImagesInDescriptionChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val dark = selectedThemeMode.resolveDarkTheme(isSystemInDarkTheme())
    MoreBranchScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {
        item {
            PaperSectionHeader(stringResource(R.string.color_mode_title))
        }
        item {
            ThemeModeSelector(
                selectedMode = selectedThemeMode,
                onModeChange = onThemeModeChange,
            )
        }
        item {
            PaperSectionHeader(stringResource(R.string.built_in_themes_title))
        }
        items(PaperThemePreset.entries, key = PaperThemePreset::storageKey) { preset ->
            ThemeChoiceCard(
                preset = preset,
                selected = preset.storageKey == selectedThemeKey,
                dark = dark,
                onClick = { onThemeChange(preset.storageKey) },
            )
        }
        if (communityThemes.isNotEmpty()) {
            item {
                PaperSectionHeader(stringResource(R.string.community_themes_title))
            }
            items(communityThemes, key = CommunityPaperTheme::storageKey) { theme ->
                CommunityThemeChoiceCard(
                    theme = theme,
                    selected = theme.storageKey == selectedThemeKey,
                    dark = dark,
                    onClick = { onThemeChange(theme.storageKey) },
                )
            }
        }
        if (communityThemesLoading) {
            item {
                PaperStatePanel(
                    title = stringResource(R.string.community_themes_loading),
                    loading = true,
                    compact = true,
                )
            }
        }
        if (communityThemeIssues.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.community_themes_attention)) }
            items(communityThemeIssues, key = ThemeExtensionIssue::packageName) { issue ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.danger)
                        Text(
                            issue.packageName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(issue.message, color = PaperTheme.tokens.inkMuted)
                }
            }
        }
        item { PaperSectionHeader(stringResource(R.string.appearance_display_title)) }
        item {
            DisplaySettings(
                selectedLibraryLayout = selectedLibraryLayout,
                onLibraryLayoutChange = onLibraryLayoutChange,
                selectedDateFormat = selectedDateFormat,
                onDateFormatChange = onDateFormatChange,
                relativeTimeEnabled = relativeTimeEnabled,
                onRelativeTimeChange = onRelativeTimeChange,
                tabletUiMode = tabletUiMode,
                onTabletUiModeChange = onTabletUiModeChange,
                showImagesInDescription = showImagesInDescription,
                onShowImagesInDescriptionChange = onShowImagesInDescriptionChange,
            )
        }
    }
}

@Composable
fun AppearanceScreen(
    selectedPreset: PaperThemePreset,
    selectedThemeMode: PaperThemeMode = PaperThemeMode.SYSTEM,
    onPresetChange: (PaperThemePreset) -> Unit,
    onThemeModeChange: (PaperThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    AppearanceScreen(
        selectedThemeKey = selectedPreset.storageKey,
        communityThemes = emptyList(),
        communityThemesLoading = false,
        communityThemeIssues = emptyList(),
        selectedThemeMode = selectedThemeMode,
        onThemeChange = { key -> onPresetChange(PaperThemePreset.fromStorageKey(key)) },
        onThemeModeChange = onThemeModeChange,
        onBack = onBack,
    )
}

@Composable
private fun DisplaySettings(
    selectedLibraryLayout: LibraryLayout,
    onLibraryLayoutChange: (LibraryLayout) -> Unit,
    selectedDateFormat: PaperDateFormat,
    onDateFormatChange: (PaperDateFormat) -> Unit,
    relativeTimeEnabled: Boolean,
    onRelativeTimeChange: (Boolean) -> Unit,
    tabletUiMode: TabletUiMode,
    onTabletUiModeChange: (TabletUiMode) -> Unit,
    showImagesInDescription: Boolean,
    onShowImagesInDescriptionChange: (Boolean) -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        DisplayChoiceGroup(
            title = stringResource(R.string.library_layout_title),
            options = LibraryLayout.entries.map { layout ->
                DisplayChoice(
                    value = layout,
                    label = stringResource(
                        if (layout == LibraryLayout.LIST) R.string.library_layout_list else R.string.library_layout_grid,
                    ),
                )
            },
            selected = selectedLibraryLayout,
            onSelected = onLibraryLayoutChange,
        )
        Spacer(Modifier.height(12.dp))
        DisplayChoiceGroup(
            title = stringResource(R.string.tablet_ui_mode_title),
            options = TabletUiMode.entries.map { mode ->
                DisplayChoice(value = mode, label = tabletUiModeLabel(mode))
            },
            selected = tabletUiMode,
            onSelected = onTabletUiModeChange,
        )
        Spacer(Modifier.height(12.dp))
        DisplayChoiceGroup(
            title = stringResource(R.string.date_format_title),
            options = PaperDateFormat.entries.map { format ->
                DisplayChoice(value = format, label = paperDateFormatLabel(format))
            },
            selected = selectedDateFormat,
            onSelected = onDateFormatChange,
        )
        Spacer(Modifier.height(8.dp))
        DisplayToggleRow(
            title = stringResource(R.string.relative_time_title),
            supportingText = stringResource(R.string.relative_time_summary),
            checked = relativeTimeEnabled,
            onCheckedChange = onRelativeTimeChange,
        )
        DisplayToggleRow(
            title = stringResource(R.string.show_images_description_title),
            supportingText = stringResource(R.string.show_images_description_summary),
            checked = showImagesInDescription,
            onCheckedChange = onShowImagesInDescriptionChange,
        )
    }
}

private data class DisplayChoice<T>(val value: T, val label: String)

@Composable
private fun <T> DisplayChoiceGroup(
    title: String,
    options: List<DisplayChoice<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = PaperTheme.tokens.inkMuted)
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { option ->
            val isSelected = option.value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(option.value) },
                    )
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = PaperTheme.tokens.ink,
                        unselectedColor = PaperTheme.tokens.inkMuted,
                    ),
                )
                Text(option.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DisplayToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = PaperTheme.tokens.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun tabletUiModeLabel(mode: TabletUiMode): String = stringResource(
    when (mode) {
        TabletUiMode.AUTOMATIC -> R.string.tablet_ui_mode_automatic
        TabletUiMode.COMPACT -> R.string.tablet_ui_mode_compact
        TabletUiMode.WIDE -> R.string.tablet_ui_mode_wide
    },
)

@Composable
private fun paperDateFormatLabel(format: PaperDateFormat): String = stringResource(
    when (format) {
        PaperDateFormat.DEFAULT -> R.string.date_format_default
        PaperDateFormat.US_SHORT -> R.string.date_format_us_short
        PaperDateFormat.EUROPEAN -> R.string.date_format_european
        PaperDateFormat.ISO -> R.string.date_format_iso
        PaperDateFormat.LONG -> R.string.date_format_long
    },
)

@Composable
private fun ThemeModeSelector(
    selectedMode: PaperThemeMode,
    onModeChange: (PaperThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaperThemeMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onModeChange(mode) },
                    ),
                shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
                color = if (selected) PaperTheme.tokens.primary else PaperTheme.tokens.surface,
                contentColor = if (selected) PaperTheme.tokens.onPrimary else PaperTheme.tokens.ink,
                border = BorderStroke(
                    PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp),
                    PaperTheme.tokens.border,
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        themeModeName(mode),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun themeModeName(mode: PaperThemeMode): String = stringResource(
    when (mode) {
        PaperThemeMode.SYSTEM -> R.string.theme_mode_system
        PaperThemeMode.LIGHT -> R.string.theme_mode_light
        PaperThemeMode.DARK -> R.string.theme_mode_dark
    },
)

@Composable
private fun ThemeChoiceCard(
    preset: PaperThemePreset,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    PaperSurface(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = PaperTheme.tokens.ink,
                    unselectedColor = PaperTheme.tokens.inkMuted,
                ),
            )
            Text(
                themeName(preset),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            ThemeSwatch(preset, dark)
        }
    }
}

@Composable
private fun CommunityThemeChoiceCard(
    theme: CommunityPaperTheme,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val preview = theme.tokens(dark)
    PaperSurface(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = PaperTheme.tokens.ink,
                    unselectedColor = PaperTheme.tokens.inkMuted,
                ),
            )
            Text(
                theme.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ThemeSwatch(listOf(preview.primary, preview.secondary, preview.surface))
        }
    }
}

@Composable
internal fun themeName(preset: PaperThemePreset): String = stringResource(
    when (preset) {
        PaperThemePreset.NEOBRUTALISM -> R.string.theme_neobrutalism
        PaperThemePreset.MIHON -> R.string.theme_mihon
    },
)

@Composable
private fun ThemeSwatch(preset: PaperThemePreset, dark: Boolean) {
    val preview = paperThemeTokens(preset, dark)
    ThemeSwatch(listOf(preview.primary, preview.secondary, preview.surface))
}

@Composable
private fun ThemeSwatch(colors: List<androidx.compose.ui.graphics.Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { color ->
            Surface(
                modifier = Modifier.size(20.dp),
                shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius / 2),
                color = color,
                border = BorderStroke(
                    PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp),
                    PaperTheme.tokens.border,
                ),
            ) {}
        }
    }
}
