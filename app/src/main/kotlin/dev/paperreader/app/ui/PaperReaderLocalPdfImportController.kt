package dev.paperreader.app.ui

import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.LocalPdfImportFailure
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PaperReaderLocalPdfImportController(
    private val logic: PaperReaderLogic,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<LocalPdfImportUiState>(LocalPdfImportUiState.Preparing)
    val state: StateFlow<LocalPdfImportUiState> = mutableState

    init {
        recoverPendingImport()
    }

    fun prepare(sourceUri: String): Boolean {
        if (!mutableState.value.canStartLocalPdfImport()) return false
        mutableState.value = LocalPdfImportUiState.Preparing
        scope.launch {
            try {
                mutableState.value = when (val result = logic.useCases.prepareLocalPdf.await(sourceUri)) {
                    is PrepareLocalPdfResult.Ready -> LocalPdfImportUiState.Confirming(result.candidate)
                    is PrepareLocalPdfResult.Rejected -> LocalPdfImportUiState.Failed(result.reason)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = LocalPdfImportUiState.Idle
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = LocalPdfImportUiState.Failed(LocalPdfImportFailure.IO_FAILURE)
            }
        }
        return true
    }

    fun confirm(title: String) {
        val candidate = (mutableState.value as? LocalPdfImportUiState.Confirming)?.candidate ?: return
        mutableState.value = LocalPdfImportUiState.Importing(candidate, title)
        scope.launch {
            try {
                mutableState.value = when (
                    val result = logic.useCases.importLocalPdf.await(candidate.importToken, title)
                ) {
                    is LocalPdfImportResult.Imported -> LocalPdfImportUiState.Complete(
                        workId = result.document.workId.value,
                        title = title.trim(),
                        alreadyImported = false,
                    )

                    is LocalPdfImportResult.AlreadyImported -> LocalPdfImportUiState.Complete(
                        workId = result.document.workId.value,
                        title = title.trim(),
                        alreadyImported = true,
                    )

                    is LocalPdfImportResult.Rejected -> LocalPdfImportUiState.Failed(
                        reason = result.reason,
                        pendingCandidate = candidate.takeIf {
                            result.reason == LocalPdfImportFailure.IO_FAILURE ||
                                result.reason == LocalPdfImportFailure.INVALID_TITLE
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = LocalPdfImportUiState.Confirming(candidate)
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = LocalPdfImportUiState.Failed(
                    reason = LocalPdfImportFailure.IO_FAILURE,
                    pendingCandidate = candidate,
                )
            }
        }
    }

    fun dismiss() {
        when (val current = mutableState.value) {
            LocalPdfImportUiState.Preparing,
            is LocalPdfImportUiState.Importing,
            -> Unit

            is LocalPdfImportUiState.Confirming -> {
                mutableState.value = LocalPdfImportUiState.Idle
                discard(current.candidate.importToken)
            }

            is LocalPdfImportUiState.Failed -> {
                mutableState.value = LocalPdfImportUiState.Idle
                current.pendingCandidate?.let { discard(it.importToken) }
            }

            else -> mutableState.value = LocalPdfImportUiState.Idle
        }
    }

    private fun discard(importToken: String) {
        scope.launch {
            withContext(NonCancellable) {
                logic.useCases.discardPendingLocalPdf.await(importToken)
            }
        }
    }

    private fun recoverPendingImport() {
        scope.launch {
            try {
                mutableState.value = logic.useCases.recoverPendingLocalPdf.await()
                    ?.let(LocalPdfImportUiState::Confirming)
                    ?: LocalPdfImportUiState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = LocalPdfImportUiState.Failed(LocalPdfImportFailure.IO_FAILURE)
            }
        }
    }
}

private fun LocalPdfImportUiState.canStartLocalPdfImport(): Boolean = when (this) {
    LocalPdfImportUiState.Idle,
    is LocalPdfImportUiState.Complete,
    is LocalPdfImportUiState.Failed,
    -> true

    LocalPdfImportUiState.Preparing,
    is LocalPdfImportUiState.Confirming,
    is LocalPdfImportUiState.Importing,
    -> false
}
