package dev.paperreader.app.ui.state

data class DownloadActionUiState(
    val requestingManifestations: Set<String> = emptySet(),
    val failedManifestations: Set<String> = emptySet(),
    val actingTaskIds: Set<String> = emptySet(),
    val failedTaskIds: Set<String> = emptySet(),
)
