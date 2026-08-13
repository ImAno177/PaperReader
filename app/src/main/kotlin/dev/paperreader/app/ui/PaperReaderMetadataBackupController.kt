package dev.paperreader.app.ui

import dev.paperreader.app.backup.BackupDestinationRecoveryCancellationException
import dev.paperreader.app.backup.BackupDestinationWriteException
import dev.paperreader.app.backup.BackupFileTooLargeException
import dev.paperreader.app.backup.MetadataRestoreSessionStore
import dev.paperreader.app.ui.model.MetadataBackupFailure
import dev.paperreader.app.ui.model.MetadataBackupOperation
import dev.paperreader.app.ui.model.MetadataBackupSummaryUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.MetadataRestoreIssueUi
import dev.paperreader.app.ui.model.MetadataRestorePreviewUi
import dev.paperreader.app.ui.model.MetadataRestoreReportUi
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.backup.MetadataBackupExportResult
import dev.paperreader.logic.backup.MetadataBackupSummary
import dev.paperreader.logic.backup.MetadataRestorePreview
import dev.paperreader.logic.backup.MetadataRestorePreviewResult
import dev.paperreader.logic.backup.MetadataRestoreResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PaperReaderMetadataBackupController(
    private val logic: PaperReaderLogic,
    private val sessionStore: MetadataRestoreSessionStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<MetadataBackupUiState>(MetadataBackupUiState.Inspecting)
    val state: StateFlow<MetadataBackupUiState> = mutableState
    private var pendingRestoreBytes: ByteArray? = null
    private val operationMutex = Mutex()

    init {
        recoverPendingRestore()
    }

    fun create(write: suspend (MetadataBackupExport) -> Unit) {
        if (!mutableState.value.canStartNewOperation()) return
        mutableState.value = MetadataBackupUiState.Exporting
        scope.launch {
            operationMutex.withLock {
                pendingRestoreBytes = null
                if (!clearRestoreSession()) {
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.EXPORT,
                        reason = MetadataBackupFailure.FILE_UNAVAILABLE,
                    )
                    return@withLock
                }
                try {
                    mutableState.value = executeMetadataBackupExport(
                        export = { logic.useCases.createMetadataBackup.await() },
                        write = write,
                    )
                } catch (cancelled: CancellationException) {
                    mutableState.value = metadataBackupStateAfterCancellation(cancelled)
                    throw cancelled
                }
            }
        }
    }

    fun previewRestore(read: suspend () -> ByteArray) {
        if (!mutableState.value.canStartNewOperation()) return
        pendingRestoreBytes = null
        mutableState.value = MetadataBackupUiState.Inspecting
        scope.launch {
            operationMutex.withLock {
                try {
                    if (!clearRestoreSession()) {
                        mutableState.value = MetadataBackupUiState.Failed(
                            operation = MetadataBackupOperation.PREVIEW,
                            reason = MetadataBackupFailure.FILE_UNAVAILABLE,
                        )
                        return@withLock
                    }
                    val bytes = read()
                    sessionStore.save(bytes)
                    inspectRestore(bytes)
                } catch (cancelled: CancellationException) {
                    pendingRestoreBytes = null
                    withContext(NonCancellable) { clearRestoreSession() }
                    mutableState.value = MetadataBackupUiState.Idle
                    throw cancelled
                } catch (_: BackupFileTooLargeException) {
                    clearRestoreSessionQuietly()
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.PREVIEW,
                        reason = MetadataBackupFailure.FILE_TOO_LARGE,
                    )
                } catch (_: java.io.IOException) {
                    clearRestoreSessionQuietly()
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.PREVIEW,
                        reason = MetadataBackupFailure.FILE_UNAVAILABLE,
                    )
                } catch (_: Exception) {
                    clearRestoreSessionQuietly()
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.PREVIEW,
                        reason = MetadataBackupFailure.INVALID_OR_UNSUPPORTED,
                    )
                }
            }
        }
    }

    fun confirmRestore() {
        val bytes = pendingRestoreBytes ?: return
        val preview = (mutableState.value as? MetadataBackupUiState.Preview)?.value ?: return
        mutableState.value = MetadataBackupUiState.Restoring(preview)
        scope.launch {
            operationMutex.withLock {
                try {
                    when (val result = logic.useCases.restoreMetadataBackup.await(bytes)) {
                        is MetadataRestoreResult.Applied -> {
                            pendingRestoreBytes = null
                            clearRestoreSessionQuietly()
                            mutableState.value = MetadataBackupUiState.Restored(
                                MetadataRestoreReportUi(
                                    summary = result.preview.summary.toUi(),
                                    appliedWorks = result.appliedWorks,
                                    skippedWorks = result.skippedWorks,
                                    appliedBookmarks = result.appliedBookmarks,
                                    appliedAnnotations = result.appliedAnnotations,
                                    appliedSavedSearches = result.appliedSavedSearches,
                                    appliedSavedSearchHits = result.appliedSavedSearchHits,
                                    skippedRecords = result.skippedRecords,
                                ),
                            )
                        }

                        is MetadataRestoreResult.Rejected -> {
                            pendingRestoreBytes = null
                            clearRestoreSessionQuietly()
                            mutableState.value = MetadataBackupUiState.Failed(
                                operation = MetadataBackupOperation.RESTORE,
                                reason = MetadataBackupFailure.INVALID_OR_UNSUPPORTED,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    mutableState.value = MetadataBackupUiState.Preview(preview)
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.RESTORE,
                        reason = MetadataBackupFailure.RESTORE_FAILED,
                    )
                }
            }
        }
    }

    fun dismiss() {
        if (mutableState.value.isBusy()) return
        mutableState.value = MetadataBackupUiState.Inspecting
        scope.launch {
            operationMutex.withLock {
                pendingRestoreBytes = null
                mutableState.value = if (clearRestoreSession()) {
                    MetadataBackupUiState.Idle
                } else {
                    MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.PREVIEW,
                        reason = MetadataBackupFailure.FILE_UNAVAILABLE,
                    )
                }
            }
        }
    }

    private fun recoverPendingRestore() {
        scope.launch {
            operationMutex.withLock {
                try {
                    val bytes = sessionStore.load()
                    if (bytes == null) {
                        mutableState.value = MetadataBackupUiState.Idle
                    } else {
                        inspectRestore(bytes)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    clearRestoreSessionQuietly()
                    mutableState.value = MetadataBackupUiState.Failed(
                        operation = MetadataBackupOperation.PREVIEW,
                        reason = MetadataBackupFailure.INVALID_OR_UNSUPPORTED,
                    )
                }
            }
        }
    }

    private suspend fun inspectRestore(bytes: ByteArray) {
        when (val result = logic.useCases.previewMetadataRestore.await(bytes)) {
            is MetadataRestorePreviewResult.Ready -> {
                pendingRestoreBytes = bytes
                mutableState.value = MetadataBackupUiState.Preview(result.preview.toUi())
            }

            is MetadataRestorePreviewResult.Rejected -> {
                pendingRestoreBytes = null
                clearRestoreSessionQuietly()
                mutableState.value = MetadataBackupUiState.Failed(
                    operation = MetadataBackupOperation.PREVIEW,
                    reason = MetadataBackupFailure.INVALID_OR_UNSUPPORTED,
                )
            }
        }
    }

    private suspend fun clearRestoreSessionQuietly() {
        clearRestoreSession()
    }

    private suspend fun clearRestoreSession(): Boolean = try {
        sessionStore.clear()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun MetadataBackupUiState.isBusy(): Boolean = this == MetadataBackupUiState.Exporting ||
    this == MetadataBackupUiState.Inspecting || this is MetadataBackupUiState.Restoring

private fun MetadataBackupUiState.canStartNewOperation(): Boolean = when (this) {
    MetadataBackupUiState.Idle,
    is MetadataBackupUiState.Exported,
    is MetadataBackupUiState.Restored,
    is MetadataBackupUiState.Failed,
    -> true

    MetadataBackupUiState.Exporting,
    MetadataBackupUiState.Inspecting,
    is MetadataBackupUiState.Preview,
    is MetadataBackupUiState.Restoring,
    -> false
}

internal suspend fun executeMetadataBackupExport(
    export: suspend () -> MetadataBackupExportResult,
    write: suspend (MetadataBackupExport) -> Unit,
): MetadataBackupUiState = try {
    when (val result = export()) {
        is MetadataBackupExportResult.Success -> {
            write(result.export)
            MetadataBackupUiState.Exported(result.export.summary.toUi())
        }

        is MetadataBackupExportResult.Rejected -> MetadataBackupUiState.Failed(
            operation = MetadataBackupOperation.EXPORT,
            reason = if (result.error.code.endsWith("too_large")) {
                MetadataBackupFailure.LIBRARY_TOO_LARGE
            } else {
                MetadataBackupFailure.EXPORT_FAILED
            },
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: BackupDestinationWriteException) {
    MetadataBackupUiState.Failed(
        operation = MetadataBackupOperation.EXPORT,
        reason = if (failure.destinationRecovered) {
            MetadataBackupFailure.FILE_UNAVAILABLE
        } else {
            MetadataBackupFailure.INCOMPLETE_FILE_MAY_REMAIN
        },
    )
} catch (_: java.io.IOException) {
    MetadataBackupUiState.Failed(
        operation = MetadataBackupOperation.EXPORT,
        reason = MetadataBackupFailure.FILE_UNAVAILABLE,
    )
} catch (_: Exception) {
    MetadataBackupUiState.Failed(
        operation = MetadataBackupOperation.EXPORT,
        reason = MetadataBackupFailure.EXPORT_FAILED,
    )
}

internal fun metadataBackupStateAfterCancellation(
    cancellation: CancellationException,
): MetadataBackupUiState = if (cancellation is BackupDestinationRecoveryCancellationException) {
    MetadataBackupUiState.Failed(
        operation = MetadataBackupOperation.EXPORT,
        reason = MetadataBackupFailure.INCOMPLETE_FILE_MAY_REMAIN,
    )
} else {
    MetadataBackupUiState.Idle
}

private fun MetadataBackupSummary.toUi() = MetadataBackupSummaryUi(
    works = works,
    collections = collections,
    manifestations = manifestations,
    readingStates = readingStates,
    bookmarks = bookmarks,
    annotations = annotations,
    historyEntries = history,
    savedSearches = savedSearches,
    savedSearchHits = savedSearchHits,
)

private fun MetadataRestorePreview.toUi() = MetadataRestorePreviewUi(
    createdAt = createdAt,
    summary = summary.toUi(),
    newWorks = newWorks,
    mergedWorks = mergedWorks,
    skippedWorks = skippedWorks,
    conflicts = conflicts.map { MetadataRestoreIssueUi(it.code, it.detail) },
    missingProviders = missingProviders.sorted(),
    dormantReadingStates = dormantReadingStates,
    dormantBookmarks = dormantBookmarks,
    dormantAnnotations = dormantAnnotations,
    skippedRecords = skippedRecords,
)
