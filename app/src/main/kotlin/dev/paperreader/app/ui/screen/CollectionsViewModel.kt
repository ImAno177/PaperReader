package dev.paperreader.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.toPaperCollectionUi
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.state.asLoadState
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import kotlinx.coroutines.flow.StateFlow

sealed interface CollectionsAction {
    data class Create(val name: String) : CollectionsAction
    data class Rename(val id: Long, val name: String) : CollectionsAction
    data class Delete(val id: Long) : CollectionsAction
}

class CollectionsViewModel(
    private val logic: PaperReaderLogic,
) : ViewModel() {
    val uiState: StateFlow<LoadState<List<PaperCollectionUi>>> = logic.useCases.observeCollections
        .subscribe()
        .asLoadState(viewModelScope) { collections -> collections.map { it.toPaperCollectionUi() } }

    suspend fun createCollection(name: String): CreateCollectionResult = logic.useCases.createCollection.await(name)

    suspend fun renameCollection(id: Long, name: String): RenameCollectionResult =
        logic.useCases.renameCollection.await(CollectionId(id), name)

    suspend fun deleteCollection(id: Long): DeleteCollectionResult =
        logic.useCases.deleteCollection.await(CollectionId(id))
}
