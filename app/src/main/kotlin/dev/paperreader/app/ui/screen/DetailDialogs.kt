package dev.paperreader.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PaperActionsMenu(
    paper: PaperUi,
    onManageCollections: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
        PaperIcon(PaperIconKey.MORE_VERTICAL, contentDescription = stringResource(R.string.more_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        paper.primaryIdentifier?.let { identifier ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_identifier)) },
                leadingIcon = { PaperIcon(PaperIconKey.COPY, contentDescription = null) },
                onClick = {
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            paper.title,
                            identifier.displayValue(),
                        ),
                    )
                    expanded = false
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.manage_collections)) },
            leadingIcon = { PaperIcon(PaperIconKey.FOLDER, contentDescription = null) },
            onClick = {
                expanded = false
                onManageCollections()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_paper), color = PaperTheme.tokens.danger) },
            leadingIcon = {
                PaperIcon(PaperIconKey.DELETE, contentDescription = null, tint = PaperTheme.tokens.danger)
            },
            onClick = {
                expanded = false
                onRequestRemove()
            },
        )
    }
}

@Composable
internal fun CollectionAssignmentDialog(
    paper: PaperUi,
    collections: LoadState<List<PaperCollectionUi>>,
    onDismiss: () -> Unit,
    onSave: suspend (Set<Long>) -> SetPaperCollectionsResult,
) {
    val available = (collections as? LoadState.Ready)?.value.orEmpty()
    var selectedIds by rememberSaveable(
        paper.id,
        stateSaver = listSaver(
            save = { ids -> ids.sorted() },
            restore = { ids -> ids.toSet() },
        ),
    ) { mutableStateOf(paper.collectionIds) }
    var saving by remember { mutableStateOf(false) }
    var errorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(collections) {
        if (collections is LoadState.Ready) {
            selectedIds = selectedIds.intersect(available.mapTo(mutableSetOf(), PaperCollectionUi::id))
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.paper_collections_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (collections) {
                    LoadState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.collections_loading))
                    }

                    LoadState.Failed -> Text(
                        stringResource(R.string.paper_collections_load_failed),
                        color = PaperTheme.tokens.danger,
                    )

                    is LoadState.Ready -> if (available.isEmpty()) {
                        Text(
                            stringResource(R.string.paper_collections_empty),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        ) {
                            available.forEach { collection ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(
                                            selected = collection.id in selectedIds,
                                            role = Role.Checkbox,
                                            onClick = {
                                                selectedIds = if (collection.id in selectedIds) {
                                                    selectedIds - collection.id
                                                } else {
                                                    selectedIds + collection.id
                                                }
                                            },
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Checkbox(
                                        checked = collection.id in selectedIds,
                                        onCheckedChange = null,
                                    )
                                    Text(collection.name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                errorRes?.let { value ->
                    Text(stringResource(value), color = PaperTheme.tokens.danger)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = collections is LoadState.Ready && available.isNotEmpty() && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        errorRes = null
                        try {
                            when (onSave(selectedIds)) {
                                SetPaperCollectionsResult.Updated -> onDismiss()
                                SetPaperCollectionsResult.PaperNotFound,
                                is SetPaperCollectionsResult.CollectionNotFound,
                                -> errorRes = R.string.paper_collections_save_failed
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            errorRes = R.string.paper_collections_save_failed
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.save))
            }
        },
    )
}

