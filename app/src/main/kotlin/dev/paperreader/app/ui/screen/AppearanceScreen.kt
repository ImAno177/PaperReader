package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.extensions.ThemeExtensionIssue
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.CommunityPaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.ui.theme.paperThemeTokens

@Composable
fun AppearanceScreen(
    selectedThemeKey: String,
    communityThemes: List<CommunityPaperTheme>,
    communityThemesLoading: Boolean,
    communityThemeIssues: List<ThemeExtensionIssue>,
    onThemeChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    MoreBranchScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.appearance_body),
                style = MaterialTheme.typography.bodyLarge,
                color = PaperTheme.tokens.inkMuted,
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
                    body = stringResource(R.string.community_themes_loading_body),
                    loading = true,
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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusBadge(
                            text = stringResource(R.string.community_theme_blocked),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(issue.message, color = PaperTheme.tokens.inkMuted)
                }
            }
        }
    }
}

@Composable
fun AppearanceScreen(
    selectedPreset: PaperThemePreset,
    onPresetChange: (PaperThemePreset) -> Unit,
    onBack: () -> Unit,
) {
    AppearanceScreen(
        selectedThemeKey = selectedPreset.storageKey,
        communityThemes = emptyList(),
        communityThemesLoading = false,
        communityThemeIssues = emptyList(),
        onThemeChange = { key -> onPresetChange(PaperThemePreset.fromStorageKey(key)) },
        onBack = onBack,
    )
}


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
            modifier = Modifier.heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(themeName(preset), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = themeDescription(preset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            modifier = Modifier.heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(theme.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.community_theme_provider, theme.packageName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ThemeSwatch(listOf(preview.primary, preview.secondary, preview.surface))
        }
    }
}

@Composable
internal fun themeName(preset: PaperThemePreset): String = stringResource(
    when (preset) {
        PaperThemePreset.DOODLE -> R.string.theme_doodle
        PaperThemePreset.RETRO -> R.string.theme_retro
        PaperThemePreset.NEOBRUTALISM -> R.string.theme_neobrutalism
    },
)

@Composable
private fun themeDescription(preset: PaperThemePreset): String = stringResource(
    when (preset) {
        PaperThemePreset.DOODLE -> R.string.theme_doodle_description
        PaperThemePreset.RETRO -> R.string.theme_retro_description
        PaperThemePreset.NEOBRUTALISM -> R.string.theme_neobrutalism_description
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
                shape = RoundedCornerShape(4.dp),
                color = color,
                border = BorderStroke(1.dp, PaperTheme.tokens.border),
            ) {}
        }
    }
}
