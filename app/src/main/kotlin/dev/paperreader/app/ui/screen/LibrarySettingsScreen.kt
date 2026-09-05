package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import androidx.compose.ui.res.stringResource

@Composable
fun LibrarySettingsScreen(
    selectedLayout: LibraryLayout,
    onLayoutChange: (LibraryLayout) -> Unit,
    onOpenCollections: () -> Unit,
    onBack: () -> Unit,
) {
    MoreBranchScaffold(title = stringResource(R.string.library_settings_title), onBack = onBack) {
        item { PaperSectionHeader(stringResource(R.string.library_layout_title)) }
        item {
            PaperSurface(contentPadding = PaddingValues(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryLayout.entries.forEach { layout ->
                        val selected = layout == selectedLayout
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onLayoutChange(layout) },
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                text = stringResource(
                                    if (layout == LibraryLayout.LIST) R.string.library_layout_list else R.string.library_layout_grid,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
        item {
            PaperSecondaryButton(onClick = onOpenCollections, modifier = Modifier.fillMaxWidth()) {
                PaperIcon(PaperIconKey.FOLDER, contentDescription = null)
                Text(stringResource(R.string.manage_collections), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
