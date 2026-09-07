package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.toPaperCollectionUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.task.DownloadedPaper
import dev.paperreader.logic.domain.ManifestationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val logic: PaperReaderLogic,
    private val preferences: PaperReaderPreferences,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            logic.useCases.observeLibrary
                .subscribe()
                .asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() } }
                .collectLatest { papers -> mutableUiState.value = mutableUiState.value.copy(papers = papers) }
        }
        viewModelScope.launch {
            logic.useCases.observeCollections
                .subscribe()
                .asLoadState(viewModelScope) { collections -> collections.map { it.toPaperCollectionUi() } }
                .collectLatest { collections ->
                    val selected = mutableUiState.value.selectedCollectionId
                    mutableUiState.value = mutableUiState.value.copy(
                        collections = collections,
                        selectedCollectionId = selected.takeUnless { id ->
                            id != null && collections is LoadState.Ready && collections.value.none { it.id == id }
                        },
                    )
                }
        }
        viewModelScope.launch {
            preferences.libraryLayout.collectLatest { layout ->
                mutableUiState.value = mutableUiState.value.copy(layout = layout)
            }
        }
    }

    fun onAction(action: LibraryAction) {
        when (action) {
            is LibraryAction.SetLayout -> viewModelScope.launch { preferences.setLibraryLayout(action.layout) }
            is LibraryAction.SetQuery -> mutableUiState.value = mutableUiState.value.copy(query = action.query)
            is LibraryAction.SetStatusFilter -> mutableUiState.value = mutableUiState.value.copy(statusFilter = action.filter)
            is LibraryAction.SetCollection -> mutableUiState.value = mutableUiState.value.copy(selectedCollectionId = action.id)
            is LibraryAction.SetSortOrder -> mutableUiState.value = mutableUiState.value.copy(sortOrder = action.sortOrder)
        }
    }

    suspend fun downloadedPaper(manifestationId: String): DownloadedPaper? =
        logic.downloads.downloadedPaper(ManifestationId(manifestationId))
}
