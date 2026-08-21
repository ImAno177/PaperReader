package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.reader.PdfReaderActivity
import dev.paperreader.app.reader.ReadablePaperActivity
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.ManifestationUi
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.model.safeWebUrlOrNull
import dev.paperreader.app.ui.model.toEnglishDisplayDate
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.LOCAL_PDF_SOURCE_ID
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.task.DeleteDownloadResult
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MobileReadAction(
    workId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    manifestationId: String,
) {
    val context = LocalContext.current
    PaperPrimaryButton(
        onClick = {
            openReadablePaper(
                context = context,
                workId = workId,
                manifestationId = manifestationId,
                paperTitle = paperTitle,
                themePreset = themePreset,
                themeKey = themeKey,
                themeMode = themeMode,
            )
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.read_mobile_version))
    }
}

@Composable
internal fun ManifestationCard(
    workId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    manifestation: ManifestationUi,
    task: PaperTask?,
    requesting: Boolean,
    requestFailed: Boolean,
    showMobileReadAction: Boolean = true,
    onRequestDownload: () -> Unit,
    onGetDownloadedPaper: suspend () -> DownloadedPaper?,
    onLoadReadablePaper: suspend () -> ReadablePaperResult,
    onDeleteDownload: suspend () -> DeleteDownloadResult,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val landingPageUrl = manifestation.landingPageUrl?.safeWebUrlOrNull()
    val pdfUrl = manifestation.pdfUrl?.safeWebUrlOrNull()
    var opening by remember(manifestation.id) { mutableStateOf(false) }
    var deleting by remember(manifestation.id) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(manifestation.id) { mutableStateOf(false) }
    var showMoreOptions by rememberSaveable(manifestation.id) { mutableStateOf(false) }
    var actionErrorRes by rememberSaveable(manifestation.id) { mutableStateOf<Int?>(null) }
    val active = requesting || task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
    val downloadFailed = requestFailed || task?.state == TaskState.FAILED
    val isImportedLocalPdf = manifestation.source == LOCAL_PDF_SOURCE_ID
    val supportsMobileReading = manifestation.source.equals("arxiv", ignoreCase = true)
    val openLocalCopy: () -> Unit = {
        scope.launch {
            opening = true
            actionErrorRes = null
            try {
                val downloaded = onGetDownloadedPaper()
                if (downloaded == null) {
                    actionErrorRes = if (isImportedLocalPdf) {
                        R.string.imported_local_pdf_missing
                    } else {
                        R.string.local_pdf_missing
                    }
                } else {
                    openDownloadedPdf(
                        context = context,
                        downloadedPaper = downloaded,
                        workId = workId,
                        paperTitle = paperTitle,
                        themePreset = themePreset,
                        themeKey = themeKey,
                        themeMode = themeMode,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionErrorRes = R.string.open_local_pdf_failed
            } finally {
                opening = false
            }
        }
    }
    val localCopyButtonLabel = stringResource(
        if (opening) R.string.opening_pdf
        else if (isImportedLocalPdf) R.string.open_imported_local_pdf
        else R.string.open_downloaded_pdf,
    )
    val localCopyButtonContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        PaperIcon(PaperIconKey.OFFLINE, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(localCopyButtonLabel)
    }
    PaperSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (isImportedLocalPdf) {
                            stringResource(R.string.manifestation_imported_pdf)
                        } else {
                            manifestationTypeLabel(manifestation.type)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = manifestation.source.displayProviderName(),
                        modifier = Modifier.weight(1f),
                        color = PaperTheme.tokens.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (manifestation.version != null) {
                        Text(
                            text = stringResource(R.string.version_label, manifestation.version),
                            modifier = Modifier.weight(1f),
                            color = PaperTheme.tokens.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (manifestation.publishedDate != null) {
                        Text(
                            text = manifestation.publishedDate.toEnglishDisplayDate(),
                            modifier = Modifier.weight(1f),
                            color = PaperTheme.tokens.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusBadge(
                        text = manifestation.license ?: stringResource(
                            if (supportsMobileReading) {
                                R.string.license_available_in_mobile_source
                            } else {
                                R.string.license_unknown
                            },
                        ),
                        color = when {
                            manifestation.license != null -> PaperTheme.tokens.success
                            supportsMobileReading -> PaperTheme.tokens.primary
                            else -> PaperTheme.tokens.warning
                        },
                    )
                    if (supportsMobileReading) {
                        StatusBadge(
                            text = stringResource(R.string.mobile_reading_available),
                            color = PaperTheme.tokens.primary,
                        )
                    }
                    manifestation.localCopy?.let { localCopy ->
                        StatusBadge(
                            text = stringResource(
                                if (isImportedLocalPdf) R.string.local_file_size else R.string.downloaded_file_size,
                                formatFileSize(localCopy.byteLength),
                            ),
                            color = PaperTheme.tokens.success,
                        )
                    }
                }
            }
        }
        if (active) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (task?.state == TaskState.RUNNING) {
                        stringResource(R.string.download_running, (task.progress * 100).toInt())
                    } else {
                        stringResource(R.string.download_queued)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = PaperTheme.tokens.inkMuted,
                )
                if (task?.state == TaskState.RUNNING) {
                    LinearProgressIndicator(
                        progress = { task.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (downloadFailed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = downloadFailureMessage(task?.failureCode),
                color = PaperTheme.tokens.danger,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        actionErrorRes?.let { errorRes ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(errorRes), color = PaperTheme.tokens.danger)
        }
        if (supportsMobileReading || landingPageUrl != null || pdfUrl != null || manifestation.localCopy != null) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (supportsMobileReading && showMobileReadAction) {
                    MobileReadAction(
                        workId = workId,
                        paperTitle = paperTitle,
                        themePreset = themePreset,
                        themeKey = themeKey,
                        themeMode = themeMode,
                        manifestationId = manifestation.id,
                    )
                }
                if (manifestation.localCopy != null) {
                    if (supportsMobileReading) {
                        PaperSecondaryButton(
                            enabled = !opening && !deleting,
                            onClick = openLocalCopy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = localCopyButtonContent,
                        )
                    } else {
                        PaperPrimaryButton(
                            enabled = !opening && !deleting,
                            onClick = openLocalCopy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = localCopyButtonContent,
                        )
                    }
                } else if (pdfUrl != null) {
                    val downloadAction = {
                        actionErrorRes = null
                        onRequestDownload()
                    }
                    val downloadContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                        PaperIcon(PaperIconKey.DOWNLOAD, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(
                                when {
                                    active -> R.string.download_in_progress
                                    downloadFailed -> R.string.retry_pdf_download
                                    else -> R.string.download_pdf
                                },
                            ),
                        )
                    }
                    if (supportsMobileReading) {
                        PaperSecondaryButton(
                            enabled = !active,
                            onClick = downloadAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = downloadContent,
                        )
                    } else {
                        PaperPrimaryButton(
                            enabled = !active,
                            onClick = downloadAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            content = downloadContent,
                        )
                    }
                }
                if (supportsMobileReading) {
                    ReadableHtmlDownloadAction(
                        manifestationId = manifestation.id,
                        onLoadReadablePaper = onLoadReadablePaper,
                        onError = { actionErrorRes = it },
                    )
                }
                val hasMoreOptions = manifestation.localCopy != null || pdfUrl != null || landingPageUrl != null
                if (hasMoreOptions) {
                    TextButton(
                        onClick = { showMoreOptions = !showMoreOptions },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (showMoreOptions) R.string.fewer_file_options else R.string.more_file_options,
                            ),
                        )
                    }
                }
                if (showMoreOptions) {
                    if (manifestation.localCopy != null) {
                        PaperSecondaryButton(
                            enabled = !opening && !deleting,
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            PaperIcon(PaperIconKey.DELETE, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.delete_local_copy))
                        }
                    }
                    pdfUrl?.let { url ->
                        PaperSecondaryButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            PaperIcon(PaperIconKey.OPEN_EXTERNAL, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.open_pdf_source))
                        }
                    }
                    landingPageUrl?.let { url ->
                        PaperSecondaryButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            PaperIcon(PaperIconKey.OPEN_EXTERNAL, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.open_landing_page))
                        }
                    }
                }
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_local_copy_title)) },
            text = {
                Text(
                    stringResource(
                        if (isImportedLocalPdf) R.string.delete_imported_local_copy_body
                        else R.string.delete_local_copy_body,
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, enabled = !deleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            actionErrorRes = null
                            try {
                                when (onDeleteDownload()) {
                                    DeleteDownloadResult.Deleted,
                                    DeleteDownloadResult.NotFound,
                                    -> showDeleteDialog = false

                                    DeleteDownloadResult.ActiveTask -> {
                                        actionErrorRes = R.string.delete_download_active
                                        showDeleteDialog = false
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                actionErrorRes = R.string.delete_local_copy_failed
                                showDeleteDialog = false
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

private fun openDownloadedPdf(
    context: android.content.Context,
    downloadedPaper: DownloadedPaper,
    workId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
) {
    context.startActivity(
        PdfReaderActivity.createIntent(
            context = context,
            downloadedPaper = downloadedPaper,
            workId = WorkId(workId),
            title = paperTitle,
            themePreset = themePreset,
            themeKey = themeKey,
            themeMode = themeMode,
        ),
    )
}

private fun openReadablePaper(
    context: android.content.Context,
    workId: String,
    manifestationId: String,
    paperTitle: String,
    themePreset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
) {
    context.startActivity(
        ReadablePaperActivity.createIntent(
            context = context,
            workId = WorkId(workId),
            manifestationId = ManifestationId(manifestationId),
            title = paperTitle,
            themePreset = themePreset,
            themeKey = themeKey,
            themeMode = themeMode,
        ),
    )
}

private fun formatFileSize(byteLength: Long): String = when {
    byteLength >= 1024L * 1024L -> String.format(Locale.ENGLISH, "%.1f MB", byteLength / (1024.0 * 1024.0))
    byteLength >= 1024L -> String.format(Locale.ENGLISH, "%.1f KB", byteLength / 1024.0)
    else -> "$byteLength B"
}

@Composable
internal fun downloadFailureMessage(failureCode: String?): String = when {
    failureCode == "PDF_TOO_LARGE" -> stringResource(R.string.download_failed_too_large)
    failureCode == "INVALID_PDF" -> stringResource(R.string.download_failed_invalid_pdf)
    failureCode == "PDF_UNAVAILABLE" -> stringResource(R.string.download_failed_unavailable)
    failureCode == "HTTP_401" -> stringResource(R.string.download_failed_auth_required)
    failureCode == "HTTP_403" -> stringResource(R.string.download_failed_forbidden)
    failureCode in setOf("HTTP_404", "HTTP_410") -> stringResource(R.string.download_failed_missing)
    failureCode?.endsWith("_RETRY_EXHAUSTED") == true -> stringResource(R.string.download_failed_retry_exhausted)
    failureCode?.startsWith("HTTP_") == true -> stringResource(
        R.string.download_failed_http,
        failureCode.removePrefix("HTTP_"),
    )
    else -> stringResource(R.string.download_failed_generic)
}

@Composable
private fun manifestationTypeLabel(type: ManifestationType): String = stringResource(
    when (type) {
        ManifestationType.PREPRINT -> R.string.manifestation_preprint
        ManifestationType.ACCEPTED_MANUSCRIPT -> R.string.manifestation_accepted
        ManifestationType.VERSION_OF_RECORD -> R.string.manifestation_record
        ManifestationType.OTHER -> R.string.manifestation_other
    },
)
