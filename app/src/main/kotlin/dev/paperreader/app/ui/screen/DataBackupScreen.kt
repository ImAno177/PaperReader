package dev.paperreader.app.ui.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.MetadataBackupFailure
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.MetadataRestoreIssueUi
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey

@Composable
fun DataBackupScreen(
    state: MetadataBackupUiState,
    onRequestExport: () -> Unit,
    onRequestImport: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissState: () -> Unit,
    onBack: () -> Unit,
) {
    val actionsEnabled = state is MetadataBackupUiState.Idle ||
        state is MetadataBackupUiState.Exported || state is MetadataBackupUiState.Restored ||
        state is MetadataBackupUiState.Failed
    MoreBranchScaffold(title = stringResource(R.string.data_and_backup), onBack = onBack) {
        item {
            PaperSurface {
                Text(stringResource(R.string.metadata_backup_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.metadata_backup_body), color = PaperTheme.tokens.inkMuted)
                MetadataBackupStatus(state, onDismissState)
                Spacer(Modifier.height(12.dp))
                PaperPrimaryButton(
                    onClick = onRequestExport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.create_metadata_backup))
                }
                Spacer(Modifier.height(8.dp))
                PaperSecondaryButton(
                    onClick = onRequestImport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.UPLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.restore_metadata_backup))
                }
            }
        }
    }
    MetadataRestoreDialog(state, onConfirmRestore, onDismissState)
}

@Composable
private fun MetadataRestoreDialog(
    state: MetadataBackupUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val preview = when (state) {
        is MetadataBackupUiState.Preview -> state.value
        is MetadataBackupUiState.Restoring -> state.preview
        else -> null
    }
    preview?.let {
        val restoring = state is MetadataBackupUiState.Restoring
        AlertDialog(
            onDismissRequest = { if (!restoring) onDismiss() },
            title = { Text(stringResource(R.string.restore_backup_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.restore_backup_created, it.createdAt.toEnglishDisplayDateTime()))
                    val counts = listOf(
                        pluralStringResource(R.plurals.restore_count_papers, it.summary.works, it.summary.works),
                        pluralStringResource(
                            R.plurals.restore_count_manifestations,
                            it.summary.manifestations,
                            it.summary.manifestations,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_collections,
                            it.summary.collections,
                            it.summary.collections,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_reading_states,
                            it.summary.readingStates,
                            it.summary.readingStates,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_history_entries,
                            it.summary.historyEntries,
                            it.summary.historyEntries,
                        ),
                        pluralStringResource(R.plurals.restore_count_bookmarks, it.summary.bookmarks, it.summary.bookmarks),
                        pluralStringResource(
                            R.plurals.restore_count_annotations,
                            it.summary.annotations,
                            it.summary.annotations,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_saved_searches,
                            it.summary.savedSearches,
                            it.summary.savedSearches,
                        ),
                        pluralStringResource(
                            R.plurals.restore_count_saved_search_hits,
                            it.summary.savedSearchHits,
                            it.summary.savedSearchHits,
                        ),
                    )
                    Text(counts.take(3).joinToString(" · ") + "\n" + counts.drop(3).joinToString(" · "))
                    Text(
                        stringResource(
                            R.string.restore_backup_plan,
                            it.newWorks,
                            it.mergedWorks,
                            it.skippedWorks,
                            it.skippedRecords,
                        ),
                        color = if (it.skippedWorks + it.skippedRecords == 0) {
                            PaperTheme.tokens.inkMuted
                        } else {
                            PaperTheme.tokens.danger
                        },
                    )
                    if (it.missingProviders.isNotEmpty()) {
                        val visible = it.missingProviders.take(
                            if (detailsExpanded) RESTORE_DETAIL_LIMIT else RESTORE_DETAIL_COLLAPSED_LIMIT,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.restore_backup_missing_providers,
                                it.missingProviders.size,
                                it.missingProviders.size,
                                visible.joinToString(),
                                hiddenItemSuffix(it.missingProviders.size - visible.size),
                            ),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    if (it.conflicts.isNotEmpty()) {
                        val visible = it.conflicts.take(
                            if (detailsExpanded) RESTORE_DETAIL_LIMIT else RESTORE_DETAIL_COLLAPSED_LIMIT,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.restore_backup_conflicts,
                                it.conflicts.size,
                                it.conflicts.size,
                            ),
                            color = PaperTheme.tokens.danger,
                        )
                        visible.forEach { issue ->
                            Text(
                                text = "• ${issue.toEnglishRestoreIssue()}",
                                color = PaperTheme.tokens.inkMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val hidden = it.conflicts.size - visible.size
                        if (hidden > 0) {
                            Text(
                                pluralStringResource(R.plurals.restore_backup_more_items, hidden, hidden),
                                color = PaperTheme.tokens.inkMuted,
                            )
                        }
                    }
                    if (!detailsExpanded &&
                        (it.missingProviders.size > RESTORE_DETAIL_COLLAPSED_LIMIT ||
                            it.conflicts.size > RESTORE_DETAIL_COLLAPSED_LIMIT)
                    ) {
                        TextButton(onClick = { detailsExpanded = true }) {
                            Text(stringResource(R.string.restore_backup_show_details))
                        }
                    }
                    if (it.dormantReadingStates + it.dormantBookmarks + it.dormantAnnotations > 0) {
                        Text(
                            stringResource(
                                R.string.restore_backup_dormant_anchors,
                                it.dormantReadingStates,
                                it.dormantBookmarks,
                                it.dormantAnnotations,
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    Text(stringResource(R.string.restore_backup_merge_notice), color = PaperTheme.tokens.inkMuted)
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = !restoring) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = onConfirm, enabled = !restoring) {
                    if (restoring) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (restoring) R.string.restoring_backup else R.string.restore_backup))
                }
            },
        )
    }
}


@Composable
private fun MetadataBackupStatus(
    state: MetadataBackupUiState,
    onDismiss: () -> Unit,
) {
    when (state) {
        MetadataBackupUiState.Idle,
        is MetadataBackupUiState.Preview,
        is MetadataBackupUiState.Restoring,
        -> Unit

        MetadataBackupUiState.Exporting,
        MetadataBackupUiState.Inspecting,
        -> {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(
                        if (state == MetadataBackupUiState.Exporting) {
                            R.string.creating_metadata_backup
                        } else {
                            R.string.inspecting_metadata_backup
                        },
                    ),
                    color = PaperTheme.tokens.inkMuted,
                )
            }
        }

        is MetadataBackupUiState.Exported -> {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.metadata_backup_saved_details,
                    state.summary.works,
                    state.summary.savedSearches,
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.success,
            )
        }

        is MetadataBackupUiState.Restored -> {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.metadata_backup_restored,
                    state.report.appliedWorks,
                    state.report.appliedSavedSearches,
                    state.report.appliedSavedSearchHits,
                    state.report.skippedWorks,
                    state.report.skippedRecords,
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.success,
            )
        }

        is MetadataBackupUiState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    when (state.reason) {
                        MetadataBackupFailure.FILE_TOO_LARGE -> R.string.metadata_backup_too_large
                        MetadataBackupFailure.FILE_UNAVAILABLE -> R.string.metadata_backup_file_unavailable
                        MetadataBackupFailure.INCOMPLETE_FILE_MAY_REMAIN -> {
                            R.string.metadata_backup_incomplete_file_may_remain
                        }
                        MetadataBackupFailure.INVALID_OR_UNSUPPORTED -> R.string.metadata_backup_invalid
                        MetadataBackupFailure.LIBRARY_TOO_LARGE -> R.string.metadata_backup_library_too_large
                        MetadataBackupFailure.EXPORT_FAILED -> R.string.metadata_backup_export_failed
                        MetadataBackupFailure.RESTORE_FAILED -> R.string.metadata_backup_restore_failed
                    },
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PaperTheme.tokens.danger,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

private fun MetadataRestoreIssueUi.toEnglishRestoreIssue(): String {
    val label = when (code) {
        "alias_conflict" -> "Exact identifier matches multiple local papers"
        "work_id_conflict" -> "Paper identity collides with different local data"
        "manifestation_id_conflict" -> "Document identity collides with different local data"
        "annotation_conflict" -> "Annotation ID belongs to a different local anchor"
        else -> code.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    return "$label ($detail)"
}

private fun hiddenItemSuffix(hiddenCount: Int): String =
    if (hiddenCount > 0) " (+$hiddenCount more)" else ""

private const val RESTORE_DETAIL_COLLAPSED_LIMIT = 3
private const val RESTORE_DETAIL_LIMIT = 20
