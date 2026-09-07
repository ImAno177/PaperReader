package dev.paperreader.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.R
import dev.paperreader.app.backup.FileMetadataRestoreSessionStore
import dev.paperreader.app.backup.MetadataBackupFileGateway
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.extensions.CommunityThemeCatalog
import dev.paperreader.app.extensions.CommunityThemeExtensionManager
import dev.paperreader.app.extensions.ExtensionInstallState
import dev.paperreader.app.importer.IncomingPdfRequest
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.model.PaperDateFormat
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.backup.MAX_METADATA_BACKUP_ARCHIVE_BYTES
import dev.paperreader.logic.backup.MetadataBackupExport
import kotlinx.coroutines.launch

@Composable
fun PaperReaderApp(
    preferences: PaperReaderPreferences,
    themeExtensionManager: CommunityThemeExtensionManager,
    logic: PaperReaderLogic?,
    downloadWorkScheduler: DownloadWorkScheduler,
    savedSearchRefreshScheduler: SavedSearchRefreshScheduler,
    incomingPdfRequest: IncomingPdfRequest?,
    onIncomingPdfConsumed: (Long) -> Unit,
    incomingPaperReferenceRequest: IncomingPaperReferenceRequest?,
    onIncomingPaperReferenceConsumed: (Long) -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
    openExtensionsRequestId: Long?,
    onOpenExtensionsConsumed: (Long) -> Unit,
) {
    val themeKey by preferences.themeKey.collectAsStateWithLifecycle(PaperThemePreset.NEOBRUTALISM.storageKey)
    val themeMode by preferences.themeMode.collectAsStateWithLifecycle(PaperThemeMode.SYSTEM)
    val themeCatalog by themeExtensionManager.catalog.collectAsStateWithLifecycle()
    val preset = PaperThemePreset.fromStorageKey(themeKey)
    val communityTheme = themeCatalog.themes.firstOrNull { it.storageKey == themeKey }
    PaperReaderTheme(preset, communityTheme = communityTheme, themeMode = themeMode) {
        if (logic == null) {
            PaperStatePanel(
                title = stringResource(R.string.app_initializing_title),
                loading = true,
                modifier = Modifier
                    .fillMaxSize()
                    .background(PaperTheme.tokens.canvas)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        } else {
            PaperReaderContent(
                preferences = preferences,
                themeExtensionManager = themeExtensionManager,
                logic = logic,
                preset = preset,
                themeKey = themeKey,
                themeMode = themeMode,
                themeCatalog = themeCatalog,
                downloadWorkScheduler = downloadWorkScheduler,
                savedSearchRefreshScheduler = savedSearchRefreshScheduler,
                incomingPdfRequest = incomingPdfRequest,
                onIncomingPdfConsumed = onIncomingPdfConsumed,
                incomingPaperReferenceRequest = incomingPaperReferenceRequest,
                onIncomingPaperReferenceConsumed = onIncomingPaperReferenceConsumed,
                openUpdatesRequestId = openUpdatesRequestId,
                onOpenUpdatesConsumed = onOpenUpdatesConsumed,
                openExtensionsRequestId = openExtensionsRequestId,
                onOpenExtensionsConsumed = onOpenExtensionsConsumed,
            )
        }
    }
}

@Composable
private fun PaperReaderContent(
    preferences: PaperReaderPreferences,
    themeExtensionManager: CommunityThemeExtensionManager,
    logic: PaperReaderLogic,
    preset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    themeCatalog: CommunityThemeCatalog,
    downloadWorkScheduler: DownloadWorkScheduler,
    savedSearchRefreshScheduler: SavedSearchRefreshScheduler,
    incomingPdfRequest: IncomingPdfRequest?,
    onIncomingPdfConsumed: (Long) -> Unit,
    incomingPaperReferenceRequest: IncomingPaperReferenceRequest?,
    onIncomingPaperReferenceConsumed: (Long) -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
    openExtensionsRequestId: Long?,
    onOpenExtensionsConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val extensionInstaller = remember(context) {
        (context.applicationContext as PaperReaderApplication).extensionInstaller
    }
    val extensionInstallStates by extensionInstaller.states.collectAsStateWithLifecycle()
    val restoreSessionStore = remember(context) {
        FileMetadataRestoreSessionStore(
            directory = java.io.File(context.noBackupFilesDir, "metadata-restore-session"),
            maximumBytes = MAX_METADATA_BACKUP_ARCHIVE_BYTES,
        )
    }
    val screenViewModelFactory = remember(logic, downloadWorkScheduler, restoreSessionStore, preferences) {
        ScreenViewModelFactory(logic, preferences, downloadWorkScheduler, restoreSessionStore)
    }
    val automaticRefreshEnabled by preferences.automaticSavedSearchRefreshEnabled
        .collectAsStateWithLifecycle(false)
    val disabledProviderIds by preferences.disabledProviderIds.collectAsStateWithLifecycle(emptySet())
    val dateFormat by preferences.dateFormat.collectAsStateWithLifecycle(PaperDateFormat.DEFAULT)
    val relativeTimeEnabled by preferences.relativeTime.collectAsStateWithLifecycle(false)
    val tabletUiMode by preferences.tabletUiMode.collectAsStateWithLifecycle(TabletUiMode.AUTOMATIC)
    val showImagesInDescription by preferences.showImagesInDescription.collectAsStateWithLifecycle(true)
    val notificationPublisher = remember(context) { SavedSearchNotificationPublisher(context) }
    val scope = rememberCoroutineScope()
    var notificationsAvailable by remember { mutableStateOf(notificationPublisher.canPost()) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAvailable = notificationPublisher.canPost()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, notificationPublisher, themeExtensionManager, logic) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAvailable = notificationPublisher.canPost()
                scope.launch {
                    runCatching { logic.reconcileSourceExtensions() }
                }
                scope.launch { themeExtensionManager.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(disabledProviderIds) {
        logic.setDisabledProviderIds(disabledProviderIds)
    }

    val backupFiles = remember(context) { MetadataBackupFileGateway(context.contentResolver) }
    var pendingPdfHandler by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingBackupExport by remember { mutableStateOf<RequestBackupExport?>(null) }
    var pendingBackupImport by remember { mutableStateOf<RequestBackupImport?>(null) }
    val createBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(METADATA_BACKUP_MIME_TYPE),
    ) { uri ->
        val request = pendingBackupExport
        pendingBackupExport = null
        if (uri != null && request != null) {
            scope.launch {
                request { export -> backupFiles.write(uri, export.archiveBytes) }
            }
        }
    }
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val request = pendingBackupImport
        pendingBackupImport = null
        if (uri != null && request != null) {
            scope.launch {
                request { backupFiles.read(uri, MAX_METADATA_BACKUP_ARCHIVE_BYTES) }
            }
        }
    }
    val openPdfDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val handler = pendingPdfHandler
        pendingPdfHandler = null
        uri?.let { handler?.invoke(it.toString()) }
    }

    PaperReaderNavigation(
        screenViewModelFactory = screenViewModelFactory,
        preset = preset,
        themeKey = themeKey,
        themeMode = themeMode,
        dateFormat = dateFormat,
        relativeTimeEnabled = relativeTimeEnabled,
        tabletUiMode = tabletUiMode,
        showImagesInDescription = showImagesInDescription,
        themeCatalog = themeCatalog,
        automaticRefreshEnabled = automaticRefreshEnabled,
        notificationsAvailable = notificationsAvailable,
        onAutomaticRefreshChange = { enabled ->
            val changed = savedSearchRefreshScheduler.setEnabled(enabled)
            if (
                changed && enabled && !notificationPublisher.canPost() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            notificationsAvailable = notificationPublisher.canPost()
            changed
        },
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        onRequestLocalPdfImport = { handler ->
            pendingPdfHandler = handler
            openPdfDocument.launch(arrayOf("application/pdf"))
        },
        onRequestBackupExport = { request: RequestBackupExport ->
            pendingBackupExport = request
            createBackupDocument.launch(DEFAULT_METADATA_BACKUP_FILE_NAME)
        },
        onRequestBackupImport = { request: RequestBackupImport ->
            pendingBackupImport = request
            openBackupDocument.launch(
                arrayOf(METADATA_BACKUP_MIME_TYPE, "application/zip", "application/octet-stream"),
            )
        },
        extensionInstalls = ExtensionInstallBindings(
            installStates = extensionInstallStates,
            onInstallExtension = extensionInstaller::enqueue,
            onDismissInstallState = extensionInstaller::cancelOrDismiss,
        ),
        incomingPdfRequest = incomingPdfRequest,
        onIncomingPdfConsumed = onIncomingPdfConsumed,
        incomingPaperReferenceRequest = incomingPaperReferenceRequest,
        onIncomingPaperReferenceConsumed = onIncomingPaperReferenceConsumed,
        openUpdatesRequestId = openUpdatesRequestId,
        onOpenUpdatesConsumed = onOpenUpdatesConsumed,
        openExtensionsRequestId = openExtensionsRequestId,
        onOpenExtensionsConsumed = onOpenExtensionsConsumed,
    )
}

@Composable
private fun PaperReaderNavigation(
    screenViewModelFactory: ScreenViewModelFactory,
    preset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    dateFormat: PaperDateFormat,
    relativeTimeEnabled: Boolean,
    tabletUiMode: TabletUiMode,
    showImagesInDescription: Boolean,
    themeCatalog: CommunityThemeCatalog,
    automaticRefreshEnabled: Boolean,
    notificationsAvailable: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    onOpenNotificationSettings: () -> Unit,
    onRequestLocalPdfImport: ((String) -> Unit) -> Unit,
    onRequestBackupExport: (RequestBackupExport) -> Unit,
    onRequestBackupImport: (RequestBackupImport) -> Unit,
    extensionInstalls: ExtensionInstallBindings,
    incomingPdfRequest: IncomingPdfRequest?,
    onIncomingPdfConsumed: (Long) -> Unit,
    incomingPaperReferenceRequest: IncomingPaperReferenceRequest?,
    onIncomingPaperReferenceConsumed: (Long) -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
    openExtensionsRequestId: Long?,
    onOpenExtensionsConsumed: (Long) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val chromeHidden = !shouldShowPrimaryNavigation(currentRoute)
    val destinations = AppDestination.entries
    val content: @Composable (Modifier) -> Unit = { modifier ->
        AppNavHost(
            navController = navController,
            screenViewModelFactory = screenViewModelFactory,
            preset = preset,
            themeKey = themeKey,
            themeMode = themeMode,
            dateFormat = dateFormat,
            relativeTimeEnabled = relativeTimeEnabled,
            showImagesInDescription = showImagesInDescription,
            themeCatalog = themeCatalog,
            automaticRefreshEnabled = automaticRefreshEnabled,
            notificationsAvailable = notificationsAvailable,
            onAutomaticRefreshChange = onAutomaticRefreshChange,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onRequestLocalPdfImport = onRequestLocalPdfImport,
            onRequestBackupExport = onRequestBackupExport,
            onRequestBackupImport = onRequestBackupImport,
            extensionInstalls = extensionInstalls,
            incomingPdfRequest = incomingPdfRequest,
            onIncomingPdfConsumed = onIncomingPdfConsumed,
            incomingPaperReferenceRequest = incomingPaperReferenceRequest,
            onIncomingPaperReferenceConsumed = onIncomingPaperReferenceConsumed,
            openUpdatesRequestId = openUpdatesRequestId,
            onOpenUpdatesConsumed = onOpenUpdatesConsumed,
            openExtensionsRequestId = openExtensionsRequestId,
            onOpenExtensionsConsumed = onOpenExtensionsConsumed,
            modifier = modifier,
        )
    }
    if (chromeHidden) {
        content(Modifier.fillMaxSize())
    } else {
        AdaptiveAppShell(
            destinations = destinations,
            currentRoute = currentRoute,
            tabletUiMode = tabletUiMode,
            onNavigate = { destination ->
                if (navController.currentDestination?.route != destination.route) {
                    navController.navigate(destination.route) {
                        popUpTo(AppRoutes.LIBRARY)
                        launchSingleTop = true
                    }
                }
            },
            content = content,
        )
    }
}

internal typealias RequestBackupExport = (BackupExportHandler) -> Unit
internal typealias BackupExportHandler = suspend (MetadataBackupExport) -> Unit
internal typealias RequestBackupImport = (BackupImportHandler) -> Unit
internal typealias BackupImportHandler = suspend () -> ByteArray

private const val METADATA_BACKUP_MIME_TYPE = "application/vnd.paperreader.backup+zip"
private const val DEFAULT_METADATA_BACKUP_FILE_NAME = "paper-reader-metadata.paperreader.backup"
