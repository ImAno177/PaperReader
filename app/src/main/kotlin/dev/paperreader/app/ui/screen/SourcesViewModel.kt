package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.plugin.ExtensionStoreRegistryState
import dev.paperreader.app.ui.state.ExtensionStoreActionUiState
import dev.paperreader.app.ui.state.ExtensionStoreOperation
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SourcesAction {
    data class PreviewStore(val indexUrl: String, val publicKeyBase64: String) : SourcesAction
    data object ConfirmStore : SourcesAction
    data object DismissStoreAction : SourcesAction
    data class RefreshStore(val storeId: String) : SourcesAction
    data class RemoveStore(val storeId: String) : SourcesAction
    data class SetProviderEnabled(val providerId: String, val enabled: Boolean) : SourcesAction
}

class SourcesViewModel(
    private val logic: PaperReaderLogic,
    private val preferences: PaperReaderPreferences,
) : ViewModel() {
    val providers = logic.providers.state
    val extensionStores: StateFlow<ExtensionStoreRegistryState> = logic.extensionStores.state
    private val mutableAction = MutableStateFlow<ExtensionStoreActionUiState>(ExtensionStoreActionUiState.Idle)
    val action: StateFlow<ExtensionStoreActionUiState> = mutableAction.asStateFlow()
    private val mutex = Mutex()

    fun onAction(action: SourcesAction) {
        when (action) {
            is SourcesAction.PreviewStore -> previewStore(action.indexUrl, action.publicKeyBase64)
            SourcesAction.ConfirmStore -> confirmStore()
            SourcesAction.DismissStoreAction -> dismissStoreAction()
            is SourcesAction.RefreshStore -> refreshStore(action.storeId)
            is SourcesAction.RemoveStore -> removeStore(action.storeId)
            is SourcesAction.SetProviderEnabled -> {
                logic.setDisabledProviderIds(
                    providers.value.disabledProviderIds.toMutableSet().apply {
                        if (action.enabled) remove(action.providerId) else add(action.providerId)
                    },
                )
                viewModelScope.launch { preferences.setProviderEnabled(action.providerId, action.enabled) }
            }
        }
    }

    fun previewStore(indexUrl: String, publicKeyBase64: String) {
        if (mutableAction.value is ExtensionStoreActionUiState.Working) return
        mutableAction.value = ExtensionStoreActionUiState.Working(ExtensionStoreOperation.PREVIEW)
        viewModelScope.launch {
            mutex.withLock {
                mutableAction.value = try {
                    ExtensionStoreActionUiState.PreviewReady(logic.extensionStores.preview(indexUrl, publicKeyBase64))
                } catch (cancelled: CancellationException) {
                    mutableAction.value = ExtensionStoreActionUiState.Idle
                    throw cancelled
                } catch (error: Exception) {
                    ExtensionStoreActionUiState.Failed(
                        ExtensionStoreOperation.PREVIEW,
                        error.extensionStoreMessage("Extension store verification failed"),
                    )
                }
            }
        }
    }

    fun confirmStore() {
        val preview = (mutableAction.value as? ExtensionStoreActionUiState.PreviewReady)?.preview ?: return
        mutableAction.value = ExtensionStoreActionUiState.Working(ExtensionStoreOperation.ADD)
        viewModelScope.launch {
            mutex.withLock {
                try {
                    logic.extensionStores.addPreview(preview.token)
                    logic.reconcileSourceExtensions()
                    mutableAction.value = ExtensionStoreActionUiState.Idle
                } catch (cancelled: CancellationException) {
                    mutableAction.value = ExtensionStoreActionUiState.PreviewReady(preview)
                    throw cancelled
                } catch (error: Exception) {
                    mutableAction.value = ExtensionStoreActionUiState.Failed(
                        ExtensionStoreOperation.ADD,
                        error.extensionStoreMessage("Extension store could not be added"),
                    )
                }
            }
        }
    }

    fun refreshStore(storeId: String) = runMutation(ExtensionStoreOperation.REFRESH) {
        logic.extensionStores.refresh(storeId)
        logic.reconcileSourceExtensions()
    }

    fun removeStore(storeId: String) = runMutation(ExtensionStoreOperation.REMOVE) {
        logic.extensionStores.remove(storeId)
        logic.reconcileSourceExtensions()
    }

    fun dismissStoreAction() {
        if (mutableAction.value !is ExtensionStoreActionUiState.Working) {
            mutableAction.value = ExtensionStoreActionUiState.Idle
        }
    }

    fun reconcileSourceExtensions() {
        viewModelScope.launch {
            try {
                logic.reconcileSourceExtensions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Store actions surface operation errors; the last verified provider state remains usable.
            }
        }
    }

    private fun runMutation(operation: ExtensionStoreOperation, action: suspend () -> Unit) {
        if (mutableAction.value is ExtensionStoreActionUiState.Working) return
        mutableAction.value = ExtensionStoreActionUiState.Working(operation)
        viewModelScope.launch {
            mutex.withLock {
                try {
                    action()
                    mutableAction.value = ExtensionStoreActionUiState.Idle
                } catch (cancelled: CancellationException) {
                    mutableAction.value = ExtensionStoreActionUiState.Idle
                    throw cancelled
                } catch (error: Exception) {
                    mutableAction.value = ExtensionStoreActionUiState.Failed(
                        operation,
                        error.extensionStoreMessage("Extension store operation failed"),
                    )
                }
            }
        }
    }
}

private fun Exception.extensionStoreMessage(fallback: String): String =
    message?.trim()?.take(180)?.takeIf(String::isNotBlank) ?: fallback
