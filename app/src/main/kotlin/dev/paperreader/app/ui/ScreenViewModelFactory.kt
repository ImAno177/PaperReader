package dev.paperreader.app.ui

import dev.paperreader.app.backup.MetadataRestoreSessionStore
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.logic.PaperReaderLogic

/** Explicit dependency seam for destination ViewModels. Initializers are added with each screen. */
internal class ScreenViewModelFactory(
    internal val logic: PaperReaderLogic,
    internal val preferences: PaperReaderPreferences,
    internal val downloadWorkScheduler: DownloadWorkScheduler,
    internal val metadataRestoreSessionStore: MetadataRestoreSessionStore,
)
