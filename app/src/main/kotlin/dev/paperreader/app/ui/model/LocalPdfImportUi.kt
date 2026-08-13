package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportFailure

sealed interface LocalPdfImportUiState {
    data object Idle : LocalPdfImportUiState
    data object Preparing : LocalPdfImportUiState
    data class Confirming(val candidate: LocalPdfCandidate) : LocalPdfImportUiState
    data class Importing(val candidate: LocalPdfCandidate, val title: String) : LocalPdfImportUiState
    data class Complete(
        val workId: String,
        val title: String,
        val alreadyImported: Boolean,
    ) : LocalPdfImportUiState

    data class Failed(
        val reason: LocalPdfImportFailure,
        val pendingCandidate: LocalPdfCandidate? = null,
    ) : LocalPdfImportUiState
}
