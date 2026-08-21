package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.PaperTextButton
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CollectionsScreen(
    collections: LoadState<List<PaperCollectionUi>>,
    onCreateCollection: suspend (String) -> CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> DeleteCollectionResult,
    onBack: () -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var editorErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<PaperCollectionUi?>(null) }
    var deleteErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun openEditor(collection: PaperCollectionUi?) {
        editingId = collection?.id
        name = collection?.name.orEmpty()
        editorErrorRes = null
        showEditor = true
    }

    MoreBranchScaffold(title = stringResource(R.string.collections_title), onBack = onBack) {
        when (collections) {
            LoadState.Loading -> item {
                PaperStatePanel(
                    title = stringResource(R.string.collections_loading),
                    loading = true,
                    compact = true,
                )
            }
            LoadState.Failed -> item {
                PaperStatePanel(
                    title = stringResource(R.string.collections_load_failed_short),
                    body = stringResource(R.string.collections_load_failed),
                    icon = PaperIconKey.ERROR,
                    compact = true,
                )
            }
            is LoadState.Ready -> if (collections.value.isEmpty()) {
                item {
                    PaperStatePanel(
                        title = stringResource(R.string.collections_empty_title),
                        body = stringResource(R.string.collections_empty),
                        icon = PaperIconKey.FOLDER,
                        compact = true,
                        actionLabel = stringResource(R.string.new_collection),
                        onAction = { openEditor(null) },
                    )
                }
            } else {
                items(collections.value, key = PaperCollectionUi::id) { collection ->
                    CollectionRow(
                        collection = collection,
                        onRename = { openEditor(collection) },
                        onDelete = { deleteErrorRes = null; toDelete = collection },
                    )
                }
            }
        }
        if (collections is LoadState.Ready && collections.value.isNotEmpty()) {
            item {
                PaperSecondaryButton(onClick = { openEditor(null) }, modifier = Modifier.fillMaxWidth()) {
                    PaperIcon(PaperIconKey.ADD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.new_collection))
                }
            }
        }
    }

    if (showEditor) {
        val isRename = editingId != null
        AlertDialog(
            onDismissRequest = { if (!saving) showEditor = false },
            title = { Text(stringResource(if (isRename) R.string.rename_collection else R.string.new_collection)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; editorErrorRes = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !saving,
                        singleLine = true,
                        label = { Text(stringResource(R.string.collection_name)) },
                        supportingText = { Text(stringResource(R.string.collection_name_limit, name.length)) },
                    )
                    editorErrorRes?.let { Text(stringResource(it), color = PaperTheme.tokens.danger) }
                }
            },
            dismissButton = {
                PaperTextButton(onClick = { showEditor = false }, enabled = !saving) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperTextButton(
                    enabled = !saving && name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            saving = true
                            editorErrorRes = null
                            try {
                                val id = editingId
                                editorErrorRes = if (id == null) {
                                    when (onCreateCollection(name)) {
                                        is CreateCollectionResult.Created -> null.also { showEditor = false }
                                        CreateCollectionResult.InvalidName -> R.string.collection_name_invalid
                                        CreateCollectionResult.NameTaken -> R.string.collection_name_taken
                                    }
                                } else {
                                    when (onRenameCollection(id, name)) {
                                        is RenameCollectionResult.Renamed -> null.also { showEditor = false }
                                        RenameCollectionResult.InvalidName -> R.string.collection_name_invalid
                                        RenameCollectionResult.NameTaken -> R.string.collection_name_taken
                                        RenameCollectionResult.NotFound -> R.string.collection_not_found
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                editorErrorRes = R.string.collection_save_failed
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (isRename) R.string.save else R.string.create_collection))
                }
            },
        )
    }

    toDelete?.let { collection ->
        AlertDialog(
            onDismissRequest = { if (!deleting) toDelete = null },
            title = { Text(stringResource(R.string.delete_collection_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_collection_body, collection.name))
                    deleteErrorRes?.let { Text(stringResource(it), color = PaperTheme.tokens.danger) }
                }
            },
            dismissButton = {
                PaperTextButton(onClick = { toDelete = null }, enabled = !deleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperTextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            deleteErrorRes = null
                            try {
                                when (onDeleteCollection(collection.id)) {
                                    DeleteCollectionResult.Deleted,
                                    DeleteCollectionResult.NotFound,
                                    -> toDelete = null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                deleteErrorRes = R.string.collection_delete_failed
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.delete), color = PaperTheme.tokens.danger)
                }
            },
        )
    }
}


@Composable
private fun CollectionRow(
    collection: PaperCollectionUi,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaperIcon(PaperIconKey.FOLDER, contentDescription = null, tint = PaperTheme.tokens.primary)
            Text(
                collection.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                PaperIcon(
                    PaperIconKey.EDIT,
                    contentDescription = stringResource(R.string.rename_collection_accessibility, collection.name),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                PaperIcon(
                    PaperIconKey.DELETE,
                    contentDescription = stringResource(R.string.delete_collection_accessibility, collection.name),
                    tint = PaperTheme.tokens.danger,
                )
            }
        }
    }
}
