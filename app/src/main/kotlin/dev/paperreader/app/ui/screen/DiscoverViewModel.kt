package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.PaperReaderSearchController
import dev.paperreader.app.ui.SavedSearchActionKey
import dev.paperreader.app.ui.SavedSearchActionUiState
import dev.paperreader.app.ui.SearchUiState
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.toPaperUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.SavedSearchFeed
import kotlinx.coroutines.flow.StateFlow

sealed interface DiscoverAction {
    data class Search(val query: String) : DiscoverAction
    data object ClearSearch : DiscoverAction
    data class Save(val result: SearchPaperUi) : DiscoverAction
    data class CreateSavedSearch(val query: String) : DiscoverAction
    data class RefreshSavedSearch(val searchId: String) : DiscoverAction
    data class DeleteSavedSearch(val searchId: String) : DiscoverAction
    data class MarkSavedSearchHitRead(val hitId: String) : DiscoverAction
    data class SaveSavedSearchHit(val hitId: String) : DiscoverAction
}

class DiscoverViewModel(
    private val logic: PaperReaderLogic,
    private val preferences: PaperReaderPreferences,
) : ViewModel() {
    private val library: StateFlow<LoadState<List<PaperUi>>> = logic.useCases.observeLibrary
        .subscribe()
        .asLoadState(viewModelScope) { papers -> papers.map { it.toPaperUi() } }
    private val controller = PaperReaderSearchController(
        logic = logic,
        scope = viewModelScope,
        providers = logic.providers.state,
        preferences = preferences,
        library = library,
    )

    val uiState: StateFlow<SearchUiState> = controller.search
    val savedSearches: StateFlow<LoadState<List<SavedSearchFeed>>> = controller.savedSearches
    val savedSearchActions: StateFlow<SavedSearchActionUiState> = controller.savedSearchActions

    fun onAction(action: DiscoverAction) {
        when (action) {
            is DiscoverAction.Search -> controller.search(action.query)
            DiscoverAction.ClearSearch -> controller.clearSearch()
            is DiscoverAction.Save -> controller.save(action.result)
            is DiscoverAction.CreateSavedSearch -> controller.createSavedSearch(action.query)
            is DiscoverAction.RefreshSavedSearch -> controller.refreshSavedSearch(action.searchId)
            is DiscoverAction.DeleteSavedSearch -> controller.deleteSavedSearch(action.searchId)
            is DiscoverAction.MarkSavedSearchHitRead -> controller.markSavedSearchHitRead(action.hitId)
            is DiscoverAction.SaveSavedSearchHit -> controller.saveSavedSearchHit(action.hitId)
        }
    }
}
