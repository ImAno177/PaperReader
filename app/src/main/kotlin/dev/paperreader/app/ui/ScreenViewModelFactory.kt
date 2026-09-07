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
import dev.paperreader.app.ui.screen.AppearanceViewModel
import dev.paperreader.app.ui.screen.CollectionsViewModel
import dev.paperreader.app.ui.screen.DataBackupViewModel
import dev.paperreader.app.ui.screen.DownloadQueueViewModel
import dev.paperreader.app.ui.screen.MoreViewModel
import dev.paperreader.app.ui.screen.PaperDetailViewModel
import dev.paperreader.app.ui.screen.ReadingImportsViewModel
import dev.paperreader.app.ui.screen.SettingsViewModel
import dev.paperreader.app.ui.screen.SourcesViewModel
import dev.paperreader.app.ui.screen.StatsViewModel

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

    internal fun more(): ViewModelProvider.Factory = viewModelFactory {
        initializer { MoreViewModel(logic, preferences) }
    }

    internal fun downloadQueue(): ViewModelProvider.Factory = viewModelFactory {
        initializer { DownloadQueueViewModel(logic, downloadWorkScheduler) }
    }

    internal fun detail(workId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer { PaperDetailViewModel(logic, downloadWorkScheduler, workId) }
    }

    internal fun collections(): ViewModelProvider.Factory = viewModelFactory {
        initializer { CollectionsViewModel(logic) }
    }

    internal fun readingImports(): ViewModelProvider.Factory = viewModelFactory {
        initializer { ReadingImportsViewModel(logic) }
    }

    internal fun dataBackup(): ViewModelProvider.Factory = viewModelFactory {
        initializer { DataBackupViewModel(logic, metadataRestoreSessionStore) }
    }

    internal fun sources(): ViewModelProvider.Factory = viewModelFactory {
        initializer { SourcesViewModel(logic, preferences) }
    }

    internal fun appearance(): ViewModelProvider.Factory = viewModelFactory {
        initializer { AppearanceViewModel(preferences) }
    }

    internal fun settings(): ViewModelProvider.Factory = viewModelFactory {
        initializer { SettingsViewModel() }
    }

    internal fun stats(): ViewModelProvider.Factory = viewModelFactory {
        initializer { StatsViewModel(logic) }
    }
}
