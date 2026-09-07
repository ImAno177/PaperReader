package dev.paperreader.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.paperreader.app.backup.MetadataRestoreSessionStore
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.app.ui.screen.LibraryViewModel
import dev.paperreader.app.ui.screen.DiscoverViewModel
import dev.paperreader.app.ui.screen.HistoryViewModel
import dev.paperreader.app.ui.screen.UpdatesViewModel

/** Explicit dependency seam for destination ViewModels. Initializers are added with each screen. */
internal class ScreenViewModelFactory(
    internal val logic: PaperReaderLogic,
    internal val preferences: PaperReaderPreferences,
    internal val downloadWorkScheduler: DownloadWorkScheduler,
    internal val metadataRestoreSessionStore: MetadataRestoreSessionStore,
) {
    internal fun library(): ViewModelProvider.Factory = viewModelFactory {
        initializer { LibraryViewModel(logic, preferences) }
    }

    internal fun discover(): ViewModelProvider.Factory = viewModelFactory {
        initializer { DiscoverViewModel(logic, preferences) }
    }

    internal fun history(): ViewModelProvider.Factory = viewModelFactory {
        initializer { HistoryViewModel(logic) }
    }

    internal fun updates(): ViewModelProvider.Factory = viewModelFactory {
        initializer { UpdatesViewModel(logic, downloadWorkScheduler, preferences) }
    }
}
