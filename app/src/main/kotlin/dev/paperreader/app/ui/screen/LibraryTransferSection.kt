package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.PaperTextButton
import dev.paperreader.app.ui.model.BibliographyPreviewUi
import dev.paperreader.app.ui.model.LibraryArchivePreviewUi
import dev.paperreader.app.ui.model.LibraryTransferFailure
import dev.paperreader.app.ui.model.LibraryTransferUiState
import dev.paperreader.app.ui.model.canStartOperation
import dev.paperreader.app.ui.model.isBusy
import dev.paperreader.app.ui.model.toEnglishDisplayDateTime
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.interop.BibliographyErrorCode
import dev.paperreader.logic.interop.BibliographyFormat

@Composable
fun LibraryTransferSection(
    state: LibraryTransferUiState,
    onExportLibrary: () -> Unit,
    onImportLibrary: () -> Unit,
    onExportBibliography: (BibliographyFormat) -> Unit,
    onImportBibliography: (BibliographyFormat) -> Unit,
    onExportNotes: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean,
) {
    val actionsEnabled = enabled && state.canStartOperation && !state.isBusy
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaperSurface {
            Text(stringResource(R.string.library_transfer_archive_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.library_transfer_archive_body), color = PaperTheme.tokens.inkMuted)
            Spacer(Modifier.height(12.dp))
            PaperPrimaryButton(
                onClick = onExportLibrary,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.library_transfer_create_archive))
            }
            Spacer(Modifier.height(8.dp))
            PaperSecondaryButton(
                onClick = onImportLibrary,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaperIcon(PaperIconKey.UPLOAD, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.library_transfer_restore_archive))
            }
        }
        PaperSurface {
            Text(stringResource(R.string.library_transfer_bibliography_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.library_transfer_bibliography_body), color = PaperTheme.tokens.inkMuted)
            Spacer(Modifier.height(12.dp))
            BibliographyActions(
                format = BibliographyFormat.BIBTEX,
                exportLabel = stringResource(R.string.library_transfer_export_bibtex),
                importLabel = stringResource(R.string.library_transfer_import_bibtex),
                enabled = actionsEnabled,
                onExport = onExportBibliography,
                onImport = onImportBibliography,
            )
            Spacer(Modifier.height(8.dp))
            BibliographyActions(
                format = BibliographyFormat.RIS,
                exportLabel = stringResource(R.string.library_transfer_export_ris),
                importLabel = stringResource(R.string.library_transfer_import_ris),
                enabled = actionsEnabled,
                onExport = onExportBibliography,
                onImport = onImportBibliography,
            )
        }
        PaperSurface {
            Text(stringResource(R.string.library_transfer_notes_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.library_transfer_notes_body), color = PaperTheme.tokens.inkMuted)
            Spacer(Modifier.height(12.dp))
            PaperSecondaryButton(
                onClick = onExportNotes,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.library_transfer_export_notes))
            }
        }
        TransferStatus(state, onDismiss)
    }
}

@Composable
private fun BibliographyActions(
    format: BibliographyFormat,
    exportLabel: String,
    importLabel: String,
    enabled: Boolean,
    onExport: (BibliographyFormat) -> Unit,
    onImport: (BibliographyFormat) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperSecondaryButton(
            onClick = { onExport(format) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(exportLabel)
        }
        PaperSecondaryButton(
            onClick = { onImport(format) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(importLabel)
        }
    }
}

@Composable
private fun TransferStatus(
    state: LibraryTransferUiState,
    onDismiss: () -> Unit,
) {
    val detail = when (state) {
        is LibraryTransferUiState.LibraryRestored -> stringResource(
            R.string.library_transfer_archive_restore_counts,
            state.value.appliedBookmarks,
            state.value.appliedAnnotations,
            state.value.appliedSavedSearches,
            state.value.appliedSavedSearchHits,
        )
        is LibraryTransferUiState.BibliographyImported -> state.value.partialDatePrecisionLoss.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.library_transfer_partial_date_warning, it, it)
        }
        else -> null
    }

    val message = when (state) {
        LibraryTransferUiState.Recovering -> stringResource(R.string.library_transfer_recovering)
        LibraryTransferUiState.ExportingLibrary -> stringResource(R.string.library_transfer_exporting_archive)
        LibraryTransferUiState.InspectingLibrary -> stringResource(R.string.library_transfer_inspecting_archive)
        LibraryTransferUiState.DiscardingLibraryPreview -> stringResource(R.string.library_transfer_discarding_archive_preview)
        is LibraryTransferUiState.RestoringLibrary -> stringResource(R.string.library_transfer_restoring_archive)
        is LibraryTransferUiState.ExportingBibliography -> stringResource(
            R.string.library_transfer_exporting_bibliography,
            formatLabel(state.format),
        )
        is LibraryTransferUiState.InspectingBibliography -> stringResource(
            R.string.library_transfer_inspecting_bibliography,
            formatLabel(state.format),
        )
        is LibraryTransferUiState.ImportingBibliography -> stringResource(
            R.string.library_transfer_importing_bibliography,
            formatLabel(state.value.format),
        )
        LibraryTransferUiState.ExportingNotes -> stringResource(R.string.library_transfer_exporting_notes)
        is LibraryTransferUiState.LibraryExported -> stringResource(
            R.string.library_transfer_archive_exported,
            state.papers,
            state.documents,
            state.documentBytes,
        ) + if (state.skippedDocuments > 0) {
            " " + pluralStringResource(
                R.plurals.library_transfer_archive_skipped_documents,
                state.skippedDocuments,
                state.skippedDocuments,
            )
        } else {
            ""
        }
        is LibraryTransferUiState.BibliographyExported -> stringResource(
            R.string.library_transfer_bibliography_exported,
            formatLabel(state.format),
        )
        LibraryTransferUiState.NotesExported -> stringResource(R.string.library_transfer_notes_exported)
        is LibraryTransferUiState.LibraryRestored -> stringResource(
            R.string.library_transfer_archive_restored,
            state.value.appliedWorks,
            state.value.restoredDocuments,
            state.value.skippedDocuments,
            state.value.skippedRecords,
        )
        is LibraryTransferUiState.BibliographyImported -> stringResource(
            R.string.library_transfer_bibliography_imported,
            formatLabel(state.value.format),
            state.value.imported,
            state.value.merged,
            state.value.skipped,
        )
        is LibraryTransferUiState.Failed -> stringResource(state.reason.toStringResource())
        LibraryTransferUiState.Idle,
        is LibraryTransferUiState.LibraryPreview,
        is LibraryTransferUiState.BibliographyPreview,
        -> null
    }
    if (message == null) return
    val statusPosition = remember { BringIntoViewRequester() }
    LaunchedEffect(message, state.isBusy) {
        if (!state.isBusy) statusPosition.bringIntoView()
    }
    PaperSurface(modifier = Modifier.bringIntoViewRequester(statusPosition)) {
        Text(
            message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = when {
                state.isBusy -> PaperTheme.tokens.inkMuted
                state is LibraryTransferUiState.Failed -> PaperTheme.tokens.danger
                else -> PaperTheme.tokens.success
            },
        )
        detail?.let { Text(it, color = PaperTheme.tokens.inkMuted) }
        if (state.isBusy) {
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = PaperTheme.tokens.ink,
                strokeWidth = 2.dp,
            )
        } else {
            PaperTextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
internal fun LibraryTransferPreviewDialog(
    state: LibraryTransferUiState,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val archivePreview = when (state) {
        is LibraryTransferUiState.LibraryPreview -> state.value
        is LibraryTransferUiState.RestoringLibrary -> state.value
        else -> null
    }
    val bibliographyPreview = when (state) {
        is LibraryTransferUiState.BibliographyPreview -> state
        is LibraryTransferUiState.ImportingBibliography -> null
        else -> null
    }
    archivePreview?.let { preview ->
        val restoring = state is LibraryTransferUiState.RestoringLibrary
        val previewState = state as? LibraryTransferUiState.LibraryPreview
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) onDismiss() },
            title = { Text(stringResource(R.string.library_transfer_archive_preview_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArchivePreviewDetails(preview)
                    previewState?.restoreFailure?.let { failure ->
                        Text(
                            stringResource(failure.toStringResource()),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    if (previewState?.discardFailed == true) {
                        Text(
                            stringResource(R.string.library_transfer_preview_discard_failed),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    Text(stringResource(R.string.restore_backup_merge_notice), color = PaperTheme.tokens.inkMuted)
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = enabled && !state.isBusy) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = onConfirm, enabled = enabled && !state.isBusy) {
                    if (restoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(if (restoring) R.string.restoring_backup else R.string.library_transfer_confirm_restore))
                }
            },
        )
    }
    bibliographyPreview?.let { previewState ->
        val preview = previewState.value
        val importFailed = previewState.importFailure
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) onDismiss() },
            title = { Text(stringResource(R.string.library_transfer_bibliography_preview_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BibliographyPreviewDetails(preview)
                    preview.error?.let { error ->
                        Text(stringResource(error.toStringResource()), color = PaperTheme.tokens.danger)
                    }
                    importFailed?.let { failure ->
                        Text(stringResource(failure.toStringResource()), color = PaperTheme.tokens.danger)
                    }
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = enabled && !state.isBusy) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperPrimaryButton(
                    onClick = onConfirm,
                    enabled = enabled && !state.isBusy && preview.records > 0 && preview.error == null,
                ) {
                    Text(stringResource(R.string.library_transfer_confirm_import))
                }
            },
        )
    }
}

@Composable
private fun ArchivePreviewDetails(preview: LibraryArchivePreviewUi) {
    var detailsExpanded by rememberSaveable(preview.createdAt.toEpochMilli()) { mutableStateOf(false) }
    val hasDetails = listOf(
        preview.readingStates,
        preview.bookmarks,
        preview.annotations,
        preview.historyEntries,
        preview.savedSearches,
        preview.savedSearchHits,
        preview.dormantReadingStates,
        preview.dormantBookmarks,
        preview.dormantAnnotations,
    ).any { it > 0 } || preview.conflicts.isNotEmpty() || preview.missingProviders.isNotEmpty()
    Text(stringResource(R.string.library_transfer_archive_preview_date, preview.createdAt.toEnglishDisplayDateTime()))
    Text(
        stringResource(
            R.string.library_transfer_archive_primary_counts,
            preview.works,
            preview.collections,
            preview.manifestations,
            preview.documents,
        ),
        color = PaperTheme.tokens.inkMuted,
    )
    Text(
        stringResource(
            R.string.library_transfer_archive_plan,
            preview.newWorks,
            preview.mergedWorks,
            preview.skippedWorks,
            preview.skippedRecords,
        ),
        color = if (preview.skippedWorks + preview.skippedRecords == 0) PaperTheme.tokens.inkMuted else PaperTheme.tokens.danger,
    )
    Text(
        stringResource(
            R.string.library_transfer_archive_documents,
            preview.documents,
            preview.documentBytes,
            preview.conflictingDocuments,
        ),
        color = if (preview.conflictingDocuments == 0) PaperTheme.tokens.inkMuted else PaperTheme.tokens.danger,
    )
    if (preview.missingProviders.isNotEmpty()) {
        Text(
            stringResource(R.string.library_transfer_archive_missing_providers, preview.missingProviders.size),
            color = PaperTheme.tokens.danger,
        )
    }
    if (detailsExpanded) {
        Text(
            stringResource(
                R.string.library_transfer_archive_detail_counts,
                preview.readingStates,
                preview.bookmarks,
                preview.annotations,
                preview.historyEntries,
                preview.savedSearches,
                preview.savedSearchHits,
            ),
            color = PaperTheme.tokens.inkMuted,
        )
        if (preview.conflicts.isNotEmpty()) {
            Text(stringResource(R.string.library_transfer_archive_conflicts, preview.conflicts.size), color = PaperTheme.tokens.danger)
            preview.conflicts.take(PREVIEW_DETAIL_LIMIT).forEach { issue ->
                val detail = issue.detail.take(CONFLICT_DETAIL_LIMIT)
                val suffix = if (issue.detail.length > detail.length) "…" else ""
                Text("${issue.code}: $detail$suffix", color = PaperTheme.tokens.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        preview.missingProviders.take(PREVIEW_DETAIL_LIMIT).forEach { provider ->
            Text(provider.take(PROVIDER_ID_PREVIEW_LIMIT), color = PaperTheme.tokens.inkMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (preview.dormantReadingStates > 0 || preview.dormantBookmarks > 0 || preview.dormantAnnotations > 0) {
            Text(
                stringResource(
                    R.string.restore_backup_dormant_anchors,
                    preview.dormantReadingStates,
                    preview.dormantBookmarks,
                    preview.dormantAnnotations,
                ),
                color = PaperTheme.tokens.inkMuted,
            )
        }
    }
    if (hasDetails) {
        PaperTextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(
                stringResource(
                    if (detailsExpanded) R.string.library_transfer_preview_hide_details
                    else R.string.library_transfer_preview_show_details,
                ),
            )
        }
    }
}

@Composable
private fun BibliographyPreviewDetails(preview: BibliographyPreviewUi) {
    Text(stringResource(R.string.library_transfer_bibliography_preview_format, formatLabel(preview.format)))
    Text(
        stringResource(
            R.string.library_transfer_bibliography_counts,
            preview.records,
            preview.newRecords,
            preview.exactMatches,
            preview.ambiguousRecords,
        ),
        color = PaperTheme.tokens.inkMuted,
    )
    if (preview.ambiguousRecords > 0) {
        Text(stringResource(R.string.library_transfer_ambiguous_examples), color = PaperTheme.tokens.danger)
        preview.ambiguousExamples.forEach { title ->
            Text(title, color = PaperTheme.tokens.inkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (preview.partialDatePrecisionLoss > 0) {
        Text(
            pluralStringResource(
                R.plurals.library_transfer_partial_date_warning,
                preview.partialDatePrecisionLoss,
                preview.partialDatePrecisionLoss,
            ),
            color = PaperTheme.tokens.danger,
        )
    }
}

@Composable
private fun formatLabel(format: BibliographyFormat): String = stringResource(
    when (format) {
        BibliographyFormat.BIBTEX -> R.string.library_transfer_format_bibtex
        BibliographyFormat.RIS -> R.string.library_transfer_format_ris
    },
)

private fun LibraryTransferFailure.toStringResource(): Int = when (this) {
    LibraryTransferFailure.FILE_TOO_LARGE -> R.string.library_transfer_failure_file_too_large
    LibraryTransferFailure.FILE_UNAVAILABLE -> R.string.library_transfer_failure_file_unavailable
    LibraryTransferFailure.INCOMPLETE_FILE_MAY_REMAIN -> R.string.library_transfer_failure_incomplete_file
    LibraryTransferFailure.INVALID_OR_UNSUPPORTED -> R.string.library_transfer_failure_invalid
    LibraryTransferFailure.EXPORT_FAILED -> R.string.library_transfer_failure_export
    LibraryTransferFailure.RESTORE_FAILED -> R.string.library_transfer_failure_restore
    LibraryTransferFailure.IMPORT_FAILED -> R.string.library_transfer_failure_import
    LibraryTransferFailure.IMPORT_FAILED_MAY_BE_PARTIAL -> R.string.library_transfer_failure_import_partial
}

private fun BibliographyErrorCode.toStringResource(): Int = when (this) {
    BibliographyErrorCode.INVALID_UTF8 -> R.string.library_transfer_bibliography_error_invalid_utf8
    BibliographyErrorCode.TOO_LARGE -> R.string.library_transfer_bibliography_error_too_large
    BibliographyErrorCode.MALFORMED -> R.string.library_transfer_bibliography_error_malformed
    BibliographyErrorCode.UNSUPPORTED_SYNTAX -> R.string.library_transfer_bibliography_error_unsupported
    BibliographyErrorCode.DUPLICATE_FIELD -> R.string.library_transfer_bibliography_error_duplicate_field
    BibliographyErrorCode.DUPLICATE_RECORD -> R.string.library_transfer_bibliography_error_duplicate_record
    BibliographyErrorCode.INVALID_METADATA -> R.string.library_transfer_bibliography_error_invalid_metadata
    BibliographyErrorCode.LIMIT_EXCEEDED -> R.string.library_transfer_bibliography_error_limit
}

private const val PREVIEW_DETAIL_LIMIT = 5

private const val CONFLICT_DETAIL_LIMIT = 240
private const val PROVIDER_ID_PREVIEW_LIMIT = 120
