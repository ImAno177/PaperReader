package dev.paperreader.app.ui.screen

import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.model.LibrarySortOrder
import dev.paperreader.app.ui.model.LibraryStatusFilter
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.state.LoadState

data class LibraryUiState(
    val papers: LoadState<List<PaperUi>> = LoadState.Loading,
    val collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    val layout: LibraryLayout = LibraryLayout.LIST,
    val query: String = "",
    val statusFilter: LibraryStatusFilter = LibraryStatusFilter.ALL,
    val selectedCollectionId: Long? = null,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.RECENTLY_SAVED,
)

sealed interface LibraryAction {
    data class SetLayout(val layout: LibraryLayout) : LibraryAction
    data class SetQuery(val query: String) : LibraryAction
    data class SetStatusFilter(val filter: LibraryStatusFilter) : LibraryAction
    data class SetCollection(val id: Long?) : LibraryAction
    data class SetSortOrder(val sortOrder: LibrarySortOrder) : LibraryAction
}
