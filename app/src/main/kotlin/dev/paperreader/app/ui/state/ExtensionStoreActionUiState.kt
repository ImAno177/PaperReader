package dev.paperreader.app.ui.state

import dev.paperreader.logic.plugin.ExtensionStorePreview

enum class ExtensionStoreOperation {
    PREVIEW,
    ADD,
    REFRESH,
    REMOVE,
}

sealed interface ExtensionStoreActionUiState {
    data object Idle : ExtensionStoreActionUiState
    data class Working(val operation: ExtensionStoreOperation) : ExtensionStoreActionUiState
    data class PreviewReady(val preview: ExtensionStorePreview) : ExtensionStoreActionUiState
    data class Failed(val operation: ExtensionStoreOperation, val message: String) : ExtensionStoreActionUiState
}
