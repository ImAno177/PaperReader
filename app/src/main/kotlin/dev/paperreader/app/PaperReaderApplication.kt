package dev.paperreader.app

import android.app.Application
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.logic.PaperReaderConfiguration
import dev.paperreader.logic.PaperReaderLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class PaperReaderApplication : Application() {
    internal val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val readerWriteMutex = Mutex()
    val preferences: PaperReaderPreferences by lazy { PaperReaderPreferences(this) }
    val downloadWorkScheduler: DownloadWorkScheduler by lazy { DownloadWorkScheduler(this) }
    val savedSearchNotificationPublisher: SavedSearchNotificationPublisher by lazy {
        SavedSearchNotificationPublisher(this)
    }
    val savedSearchRefreshScheduler: SavedSearchRefreshScheduler by lazy {
        SavedSearchRefreshScheduler(this, preferences)
    }

    val logic: PaperReaderLogic by lazy {
        PaperReaderLogic.open(
            context = this,
            configuration = PaperReaderConfiguration(
                userAgent = "PaperReader/0.1 (Android)",
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!isMainApplicationProcess(Application.getProcessName(), packageName)) return
        savedSearchNotificationPublisher.createChannel()
        applicationIoScope.launch { savedSearchRefreshScheduler.reconcile() }
    }
}

internal fun isMainApplicationProcess(processName: String, packageName: String): Boolean =
    processName == packageName
