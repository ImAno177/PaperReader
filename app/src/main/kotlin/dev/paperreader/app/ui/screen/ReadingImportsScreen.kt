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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.MAX_LOCAL_PDF_TITLE_LENGTH

@Composable
fun ReadingImportsScreen(
    state: LocalPdfImportUiState,
    onRequestImport: () -> Unit,
    onConfirmImport: (String) -> Unit,
    onDismissImport: () -> Unit,
    onOpenImportedPaper: (String) -> Unit,
    onBack: () -> Unit,
) {
    val actionsEnabled = state is LocalPdfImportUiState.Idle ||
        state is LocalPdfImportUiState.Complete || state is LocalPdfImportUiState.Failed
    MoreBranchScaffold(title = stringResource(R.string.local_pdf_import_title), onBack = onBack) {
        item {
            PaperSurface {
                Text(stringResource(R.string.local_pdf_import_body), color = PaperTheme.tokens.inkMuted)
                LocalPdfImportStatus(state, onOpenImportedPaper, onDismissImport)
                Spacer(Modifier.height(12.dp))
                PaperPrimaryButton(
                    onClick = onRequestImport,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PaperIcon(PaperIconKey.UPLOAD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.choose_local_pdf))
                }
            }
        }
    }
    LocalPdfImportDialog(state, onConfirmImport, onDismissImport)
}

@Composable
private fun LocalPdfImportDialog(
    state: LocalPdfImportUiState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val importDialog = when (state) {
        is LocalPdfImportUiState.Confirming -> state.candidate to false
        is LocalPdfImportUiState.Importing -> state.candidate to true
        else -> null
    }
    importDialog?.let { (candidate, importing) ->
        var editableTitle by rememberSaveable(candidate.importToken) { mutableStateOf(candidate.suggestedTitle) }
        val title = (state as? LocalPdfImportUiState.Importing)?.title ?: editableTitle
        val normalizedTitle = title.replace(Regex("\\s+"), " ").trim()
        val titleValid = normalizedTitle.isNotEmpty() &&
            normalizedTitle.length <= MAX_LOCAL_PDF_TITLE_LENGTH &&
            normalizedTitle.none { it.isISOControl() && !it.isWhitespace() }
        AlertDialog(
            onDismissRequest = { if (!importing) onDismiss() },
            title = { Text(stringResource(R.string.confirm_local_pdf_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(candidate.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.local_pdf_selected_size, formatImportFileSize(candidate.byteLength)),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { if (!importing) editableTitle = it },
                        enabled = !importing,
                        label = { Text(stringResource(R.string.local_pdf_paper_title)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.local_pdf_title_count,
                                    title.length,
                                    MAX_LOCAL_PDF_TITLE_LENGTH,
                                ),
                            )
                        },
                        isError = !titleValid,
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importing) {
                        Row(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = PaperTheme.tokens.ink,
                                strokeWidth = 2.dp,
                            )
                            Text(stringResource(R.string.importing_local_pdf))
                        }
                    }
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = { onConfirm(normalizedTitle) }, enabled = titleValid && !importing) {
                    Text(stringResource(R.string.import_local_pdf_action))
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismiss, enabled = !importing) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}


@Composable
private fun LocalPdfImportStatus(
    state: LocalPdfImportUiState,
    onOpenPaper: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        LocalPdfImportUiState.Idle,
        is LocalPdfImportUiState.Confirming,
        -> Unit

        LocalPdfImportUiState.Preparing -> {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = PaperTheme.tokens.ink,
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.inspecting_local_pdf))
            }
        }

        is LocalPdfImportUiState.Importing -> Unit

        is LocalPdfImportUiState.Complete -> {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(
                    text = stringResource(R.string.local_pdf_import_complete_badge),
                    icon = PaperIconKey.DONE,
                    color = PaperTheme.tokens.success,
                )
                Text(
                    stringResource(
                        if (state.alreadyImported) {
                            R.string.local_pdf_already_imported
                        } else {
                            R.string.local_pdf_imported
                        },
                    ),
                    color = PaperTheme.tokens.inkMuted,
                )
                PaperSecondaryButton(
                    onClick = { onOpenPaper(state.workId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_imported_paper))
                }
                PaperSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }

        is LocalPdfImportUiState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperIcon(
                        PaperIconKey.ERROR,
                        contentDescription = null,
                        tint = PaperTheme.tokens.danger,
                    )
                    Text(
                        stringResource(localPdfImportFailureMessage(state.reason)),
                        color = PaperTheme.tokens.danger,
                    )
                }
                PaperSecondaryButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

private fun localPdfImportFailureMessage(reason: LocalPdfImportFailure): Int = when (reason) {
    LocalPdfImportFailure.INVALID_URI -> R.string.local_pdf_invalid_uri
    LocalPdfImportFailure.SOURCE_UNAVAILABLE -> R.string.local_pdf_source_unavailable
    LocalPdfImportFailure.TOO_LARGE -> R.string.local_pdf_too_large
    LocalPdfImportFailure.INVALID_PDF -> R.string.local_pdf_invalid_file
    LocalPdfImportFailure.INVALID_TITLE -> R.string.local_pdf_invalid_title
    LocalPdfImportFailure.IO_FAILURE -> R.string.local_pdf_io_failure
    LocalPdfImportFailure.COMMIT_FAILED -> R.string.local_pdf_commit_failed
}

private fun formatImportFileSize(byteLength: Long): String = when {
    byteLength >= 1024L * 1024L -> String.format(java.util.Locale.ENGLISH, "%.1f MB", byteLength / (1024.0 * 1024.0))
    byteLength >= 1024L -> String.format(java.util.Locale.ENGLISH, "%.1f KB", byteLength / 1024.0)
    else -> "$byteLength B"
}
