package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.reader.DEFAULT_READER_LAYOUT
import dev.paperreader.app.reader.MAXIMUM_TEXT_ZOOM
import dev.paperreader.app.reader.MINIMUM_TEXT_ZOOM
import dev.paperreader.app.reader.ReadablePaperLayoutPreferences
import dev.paperreader.app.reader.ReadablePaperUserLayout
import dev.paperreader.app.reader.ReadableSideMargin
import dev.paperreader.app.reader.ReadableTextSpacing
import dev.paperreader.app.reader.nextReadableTextZoom
import dev.paperreader.app.ui.components.PaperLabel
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
fun ReaderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { ReadablePaperLayoutPreferences(context) }
    var layout by remember { mutableStateOf(preferences.load()) }

    fun update(next: ReadablePaperUserLayout) {
        layout = next
        preferences.save(next)
    }

    MoreBranchScaffold(title = stringResource(R.string.reader_settings_title), onBack = onBack) {
        item { PaperSectionHeader(stringResource(R.string.reader_layout_settings_title)) }
        item {
            PaperSurface {
                Text(
                    stringResource(R.string.reader_layout_settings_summary),
                    color = PaperTheme.tokens.inkMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperSecondaryButton(
                        onClick = {
                            update(layout.copy(textZoom = nextReadableTextZoom(layout.textZoom, increase = false)))
                        },
                        enabled = layout.textZoom > MINIMUM_TEXT_ZOOM,
                    ) { Text("A−") }
                    PaperLabel(stringResource(R.string.reader_text_zoom_value, layout.textZoom), color = PaperTheme.tokens.ink)
                    PaperSecondaryButton(
                        onClick = {
                            update(layout.copy(textZoom = nextReadableTextZoom(layout.textZoom, increase = true)))
                        },
                        enabled = layout.textZoom < MAXIMUM_TEXT_ZOOM,
                    ) { Text("A+") }
                }
            }
        }
        item { PaperSectionHeader(stringResource(R.string.reader_text_spacing_setting)) }
        item {
            PaperSurface(contentPadding = PaddingValues(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ReadableTextSpacing.entries.forEach { spacing ->
                        ReaderChoice(
                            selected = layout.textSpacing == spacing,
                            label = readableSpacingLabel(spacing),
                            onClick = { update(layout.copy(textSpacing = spacing)) },
                        )
                    }
                }
            }
        }
        item { PaperSectionHeader(stringResource(R.string.reader_side_margin_setting)) }
        item {
            PaperSurface(contentPadding = PaddingValues(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ReadableSideMargin.entries.forEach { margin ->
                        ReaderChoice(
                            selected = layout.sideMargin == margin,
                            label = readableMarginLabel(margin),
                            onClick = { update(layout.copy(sideMargin = margin)) },
                        )
                    }
                }
            }
        }
        item {
            PaperPrimaryButton(onClick = { update(DEFAULT_READER_LAYOUT) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.readable_reader_reset_layout))
            }
        }
    }
}

@Composable
private fun RowScope.ReaderChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = PaperTheme.tokens.ink,
                unselectedColor = PaperTheme.tokens.inkMuted,
            ),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

@Composable
private fun readableSpacingLabel(value: ReadableTextSpacing): String = stringResource(
    when (value) {
        ReadableTextSpacing.COMPACT -> R.string.reader_compact
        ReadableTextSpacing.COMFORTABLE -> R.string.reader_comfortable
        ReadableTextSpacing.RELAXED -> R.string.reader_relaxed
    },
)

@Composable
private fun readableMarginLabel(value: ReadableSideMargin): String = stringResource(
    when (value) {
        ReadableSideMargin.NARROW -> R.string.reader_narrow
        ReadableSideMargin.COMFORTABLE -> R.string.reader_comfortable
        ReadableSideMargin.WIDE -> R.string.reader_wide
    },
)
