package dev.paperreader.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.paperreader.app.R
import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.extensions.CommunityThemeCatalog
import dev.paperreader.app.extensions.CommunityThemeExtensionManager
import dev.paperreader.app.importer.IncomingPdfRequest
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.backup.MetadataBackupFileGateway
import dev.paperreader.app.backup.FileMetadataRestoreSessionStore
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.LibraryLayout
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.plugin.ExtensionStoreRegistryState
import dev.paperreader.logic.backup.MAX_METADATA_BACKUP_ARCHIVE_BYTES
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
                body = stringResource(R.string.app_initializing_body),
                loading = true,
                modifier = Modifier.fillMaxSize().background(PaperTheme.tokens.canvas),
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
    val viewModel: PaperReaderViewModel = viewModel(
        factory = remember(logic, downloadWorkScheduler, restoreSessionStore, preferences) {
            PaperReaderViewModel.factory(logic, downloadWorkScheduler, restoreSessionStore, preferences)
        },
    )
    val library by viewModel.library.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val savedSearches by viewModel.savedSearches.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val downloadActions by viewModel.downloadActions.collectAsStateWithLifecycle()
    val savedSearchActions by viewModel.savedSearchActions.collectAsStateWithLifecycle()
    val metadataBackup by viewModel.metadataBackup.collectAsStateWithLifecycle()
    val localPdfImport by viewModel.localPdfImport.collectAsStateWithLifecycle()
    val extensionStoreState by viewModel.extensionStores.collectAsStateWithLifecycle()
    val extensionStoreAction by viewModel.extensionStoreAction.collectAsStateWithLifecycle()
    val automaticRefreshEnabled by preferences.automaticSavedSearchRefreshEnabled
        .collectAsStateWithLifecycle(false)
    val disabledProviderIds by preferences.disabledProviderIds.collectAsStateWithLifecycle(emptySet())
    val libraryLayout by preferences.libraryLayout.collectAsStateWithLifecycle(LibraryLayout.LIST)
    val notificationPublisher = remember(context) { SavedSearchNotificationPublisher(context) }
    val scope = rememberCoroutineScope()
    var notificationsAvailable by remember { mutableStateOf(notificationPublisher.canPost()) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAvailable = notificationPublisher.canPost()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, notificationPublisher, viewModel, themeExtensionManager, scope) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAvailable = notificationPublisher.canPost()
                viewModel.reconcileSourceExtensions()
                scope.launch { themeExtensionManager.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Activity-local IDs restart after recreation; sourceKey handles durable correlation instead.
    var activeIncomingPdfRequestId by remember { mutableStateOf<Long?>(null) }
    val backupFiles = remember(context) { MetadataBackupFileGateway(context.contentResolver) }
    val createBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(METADATA_BACKUP_MIME_TYPE),
    ) { uri ->
        uri?.let { destination ->
            viewModel.createMetadataBackup(
                write = { export -> backupFiles.write(destination, export.archiveBytes) },
            )
        }
    }
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { source ->
            viewModel.previewMetadataRestore {
                backupFiles.read(source, MAX_METADATA_BACKUP_ARCHIVE_BYTES)
            }
        }
    }
    val openPdfDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { source -> viewModel.prepareLocalPdf(source.toString()) }
    }

    LaunchedEffect(disabledProviderIds) {
        viewModel.setDisabledProviderIds(disabledProviderIds)
    }

    LaunchedEffect(incomingPdfRequest, localPdfImport, activeIncomingPdfRequestId) {
        val request = incomingPdfRequest ?: return@LaunchedEffect
        val currentImport = localPdfImport
        if (
            activeIncomingPdfRequestId == null &&
            currentImport is LocalPdfImportUiState.Confirming &&
            currentImport.candidate.sourceKey == request.sourceKey
        ) {
            onIncomingPdfConsumed(request.id)
            return@LaunchedEffect
        }
        if (
            activeIncomingPdfRequestId != request.id &&
            viewModel.prepareLocalPdf(request.uri.toString())
        ) {
            activeIncomingPdfRequestId = request.id
        }
    }

    LaunchedEffect(localPdfImport, activeIncomingPdfRequestId) {
        val requestId = activeIncomingPdfRequestId ?: return@LaunchedEffect
        if (localPdfImport is LocalPdfImportUiState.Confirming || localPdfImport is LocalPdfImportUiState.Failed) {
            onIncomingPdfConsumed(requestId)
            activeIncomingPdfRequestId = null
        }
    }

    PaperReaderNavigation(
        library = library,
        history = history,
        collections = collections,
        tasks = tasks,
        savedSearches = savedSearches,
        providers = providers,
        extensionStores = ExtensionStoreBindings(
            state = extensionStoreState,
            action = extensionStoreAction,
            onPreview = viewModel::previewExtensionStore,
            onConfirm = viewModel::confirmExtensionStore,
            onDismissAction = viewModel::dismissExtensionStoreAction,
            onRefresh = viewModel::refreshExtensionStore,
            onRemove = viewModel::removeExtensionStore,
            installStates = extensionInstallStates,
            onInstallExtension = extensionInstaller::enqueue,
            onDismissInstallState = extensionInstaller::cancelOrDismiss,
        ),
        search = search,
        downloadActions = downloadActions,
        savedSearchActions = savedSearchActions,
        metadataBackup = metadataBackup,
        localPdfImport = localPdfImport,
        preset = preset,
        themeKey = themeKey,
        themeMode = themeMode,
        libraryLayout = libraryLayout,
        themeCatalog = themeCatalog,
        onSearch = viewModel::search,
        onClearSearch = viewModel::clearSearch,
        onSave = viewModel::save,
        onSaveSearch = viewModel::createSavedSearch,
        onRefreshSavedSearch = viewModel::refreshSavedSearch,
        onDeleteSavedSearch = viewModel::deleteSavedSearch,
        onMarkSavedSearchHitRead = viewModel::markSavedSearchHitRead,
        onSaveSavedSearchHit = viewModel::saveSavedSearchHit,
        onRemoveHistory = viewModel::removeHistory,
        onReadingStatusChange = viewModel::setReadingStatus,
        onRepairSavedPaper = viewModel::repairSavedPaper,
        onRemovePaper = viewModel::removePaper,
        onCreateCollection = viewModel::createCollection,
        onRenameCollection = viewModel::renameCollection,
        onDeleteCollection = viewModel::deleteCollection,
        onSetPaperCollections = viewModel::setPaperCollections,
        onRequestDownload = viewModel::requestDownload,
        onGetDownloadedPaper = viewModel::downloadedPaper,
        onLoadReadablePaper = viewModel::loadReadablePaper,
        onDeleteDownload = viewModel::deleteDownload,
        onCancelDownloadTask = viewModel::cancelDownloadTask,
        onRetryDownloadTask = viewModel::retryDownloadTask,
        onRemoveDownloadTask = viewModel::removeDownloadTask,
        onRequestLocalPdfImport = { openPdfDocument.launch(arrayOf("application/pdf")) },
        onConfirmLocalPdfImport = viewModel::confirmLocalPdfImport,
        onDismissLocalPdfImport = {
            viewModel.dismissLocalPdfImport()
            activeIncomingPdfRequestId?.let(onIncomingPdfConsumed)
            activeIncomingPdfRequestId = null
        },
        onRequestBackupExport = { createBackupDocument.launch(DEFAULT_METADATA_BACKUP_FILE_NAME) },
        onRequestBackupImport = {
            openBackupDocument.launch(
                arrayOf(METADATA_BACKUP_MIME_TYPE, "application/zip", "application/octet-stream"),
            )
        },
        onConfirmBackupRestore = viewModel::confirmMetadataRestore,
        onDismissBackupState = viewModel::dismissMetadataBackupState,
        onThemeChange = { next -> scope.launch { preferences.setThemeKey(next) } },
        onThemeModeChange = { next -> scope.launch { preferences.setThemeMode(next) } },
        onLibraryLayoutChange = { next -> scope.launch { preferences.setLibraryLayout(next) } },
        automaticRefreshEnabled = automaticRefreshEnabled,
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
        onProviderEnabledChange = { providerId, enabled ->
            scope.launch { preferences.setProviderEnabled(providerId, enabled) }
        },
        notificationsAvailable = notificationsAvailable,
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        openUpdatesRequestId = openUpdatesRequestId,
        onOpenUpdatesConsumed = onOpenUpdatesConsumed,
        openExtensionsRequestId = openExtensionsRequestId,
        onOpenExtensionsConsumed = onOpenExtensionsConsumed,
        incomingPaperReferenceRequest = incomingPaperReferenceRequest,
        onIncomingPaperReferenceConsumed = onIncomingPaperReferenceConsumed,
    )
}

@Composable
private fun PaperReaderNavigation(
    library: LoadState<List<PaperUi>>,
    history: LoadState<List<dev.paperreader.app.ui.model.ReadingHistoryUi>>,
    collections: LoadState<List<PaperCollectionUi>>,
    tasks: LoadState<List<dev.paperreader.logic.task.PaperTask>>,
    savedSearches: LoadState<List<dev.paperreader.logic.domain.SavedSearchFeed>>,
    providers: dev.paperreader.logic.provider.ProviderManagerState,
    extensionStores: ExtensionStoreBindings,
    search: SearchUiState,
    downloadActions: DownloadActionUiState,
    savedSearchActions: SavedSearchActionUiState,
    metadataBackup: dev.paperreader.app.ui.model.MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
    preset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    libraryLayout: LibraryLayout,
    themeCatalog: CommunityThemeCatalog,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSave: (dev.paperreader.app.ui.model.SearchPaperUi) -> Unit,
    onSaveSearch: (String) -> Unit,
    onRefreshSavedSearch: (String) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
    onMarkSavedSearchHitRead: (String) -> Unit,
    onSaveSavedSearchHit: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onReadingStatusChange: (String, dev.paperreader.logic.domain.ReadingStatus) -> Unit,
    onRepairSavedPaper: (String) -> Unit,
    onRemovePaper: suspend (String) -> dev.paperreader.logic.domain.repository.RemovePaperResult,
    onCreateCollection: suspend (String) -> dev.paperreader.logic.domain.repository.CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> dev.paperreader.logic.domain.repository.RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> dev.paperreader.logic.domain.repository.DeleteCollectionResult,
    onSetPaperCollections: suspend (String, Set<Long>) -> dev.paperreader.logic.domain.repository.SetPaperCollectionsResult,
    onRequestDownload: (String, String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> dev.paperreader.logic.task.DownloadedPaper?,
    onLoadReadablePaper: suspend (String, String) -> dev.paperreader.logic.reader.ReadablePaperResult,
    onDeleteDownload: suspend (String, String) -> dev.paperreader.logic.task.DeleteDownloadResult,
    onCancelDownloadTask: (String) -> Unit,
    onRetryDownloadTask: (String) -> Unit,
    onRemoveDownloadTask: (String) -> Unit,
    onRequestLocalPdfImport: () -> Unit,
    onConfirmLocalPdfImport: (String) -> Unit,
    onDismissLocalPdfImport: () -> Unit,
    onRequestBackupExport: () -> Unit,
    onRequestBackupImport: () -> Unit,
    onConfirmBackupRestore: () -> Unit,
    onDismissBackupState: () -> Unit,
    onThemeChange: (String) -> Unit,
    onThemeModeChange: (PaperThemeMode) -> Unit,
    onLibraryLayoutChange: (LibraryLayout) -> Unit,
    automaticRefreshEnabled: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    onProviderEnabledChange: (String, Boolean) -> Unit,
    notificationsAvailable: Boolean,
    onOpenNotificationSettings: () -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
    openExtensionsRequestId: Long?,
    onOpenExtensionsConsumed: (Long) -> Unit,
    incomingPaperReferenceRequest: IncomingPaperReferenceRequest?,
    onIncomingPaperReferenceConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val chromeHidden = !shouldShowPrimaryNavigation(currentRoute)
    val destinations = AppDestination.entries
    val invalidReferenceMessage = stringResource(R.string.share_reference_invalid)

    IncomingPaperReferenceEffect(
        request = incomingPaperReferenceRequest,
        onNavigateToDiscover = {
            if (navController.currentDestination?.route != AppRoutes.DISCOVER) {
                navController.navigate(AppRoutes.DISCOVER) { launchSingleTop = true }
            }
        },
        onSearch = onSearch,
        onInvalid = {
            Toast.makeText(context, invalidReferenceMessage, Toast.LENGTH_LONG).show()
        },
        onConsumed = onIncomingPaperReferenceConsumed,
    )

    LaunchedEffect(openUpdatesRequestId) {
        val requestId = openUpdatesRequestId ?: return@LaunchedEffect
        if (navController.currentDestination?.route != AppRoutes.UPDATES) {
            navController.navigate(AppRoutes.UPDATES) { launchSingleTop = true }
        }
        onOpenUpdatesConsumed(requestId)
    }

    LaunchedEffect(openExtensionsRequestId) {
        val requestId = openExtensionsRequestId ?: return@LaunchedEffect
        if (navController.currentDestination?.route != AppRoutes.MORE_SOURCES) {
            navController.navigateToMoreBranch(AppRoutes.MORE_SOURCES)
        }
        onOpenExtensionsConsumed(requestId)
    }

    LaunchedEffect(metadataBackup, localPdfImport) {
        if (incomingPaperReferenceRequest != null || openUpdatesRequestId != null || openExtensionsRequestId != null) {
            return@LaunchedEffect
        }
        val route = when (moreAttentionDestination(metadataBackup, localPdfImport)) {
            MoreAttentionDestination.DATA_BACKUP -> AppRoutes.MORE_DATA_BACKUP
            MoreAttentionDestination.READING_IMPORTS -> AppRoutes.MORE_READING_IMPORTS
            null -> null
        }
        if (route != null && navController.currentDestination?.route != route) {
            navController.navigateToMoreBranch(route)
        }
    }

    if (chromeHidden) {
        AppNavHost(
            navController = navController,
            library = library,
            history = history,
            collections = collections,
            tasks = tasks,
            savedSearches = savedSearches,
            providers = providers,
            extensionStores = extensionStores,
            search = search,
            downloadActions = downloadActions,
            savedSearchActions = savedSearchActions,
            metadataBackup = metadataBackup,
            localPdfImport = localPdfImport,
            preset = preset,
            themeKey = themeKey,
            themeMode = themeMode,
            libraryLayout = libraryLayout,
            themeCatalog = themeCatalog,
            onSearch = onSearch,
            onClearSearch = onClearSearch,
            onSave = onSave,
            onSaveSearch = onSaveSearch,
            onRefreshSavedSearch = onRefreshSavedSearch,
            onDeleteSavedSearch = onDeleteSavedSearch,
            onMarkSavedSearchHitRead = onMarkSavedSearchHitRead,
            onSaveSavedSearchHit = onSaveSavedSearchHit,
            onRemoveHistory = onRemoveHistory,
            onReadingStatusChange = onReadingStatusChange,
            onRepairSavedPaper = onRepairSavedPaper,
            onRemovePaper = onRemovePaper,
            onCreateCollection = onCreateCollection,
            onRenameCollection = onRenameCollection,
            onDeleteCollection = onDeleteCollection,
            onSetPaperCollections = onSetPaperCollections,
            onRequestDownload = onRequestDownload,
            onGetDownloadedPaper = onGetDownloadedPaper,
            onLoadReadablePaper = onLoadReadablePaper,
            onDeleteDownload = onDeleteDownload,
            onCancelDownloadTask = onCancelDownloadTask,
            onRetryDownloadTask = onRetryDownloadTask,
            onRemoveDownloadTask = onRemoveDownloadTask,
            onRequestLocalPdfImport = onRequestLocalPdfImport,
            onConfirmLocalPdfImport = onConfirmLocalPdfImport,
            onDismissLocalPdfImport = onDismissLocalPdfImport,
            onRequestBackupExport = onRequestBackupExport,
            onRequestBackupImport = onRequestBackupImport,
            onConfirmBackupRestore = onConfirmBackupRestore,
            onDismissBackupState = onDismissBackupState,
            onThemeChange = onThemeChange,
            onThemeModeChange = onThemeModeChange,
            onLibraryLayoutChange = onLibraryLayoutChange,
            automaticRefreshEnabled = automaticRefreshEnabled,
            onAutomaticRefreshChange = onAutomaticRefreshChange,
            onProviderEnabledChange = onProviderEnabledChange,
            notificationsAvailable = notificationsAvailable,
            onOpenNotificationSettings = onOpenNotificationSettings,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        AdaptiveAppShell(
            destinations = destinations,
            currentRoute = currentRoute,
            onNavigate = { destination ->
                if (navController.currentDestination?.route != destination.route) {
                    navController.navigate(destination.route) {
                        popUpTo(AppRoutes.LIBRARY)
                        launchSingleTop = true
                    }
                }
            },
        ) { modifier ->
            AppNavHost(
                navController = navController,
                library = library,
                history = history,
                collections = collections,
                tasks = tasks,
                savedSearches = savedSearches,
                providers = providers,
                extensionStores = extensionStores,
                search = search,
                downloadActions = downloadActions,
                savedSearchActions = savedSearchActions,
                metadataBackup = metadataBackup,
                localPdfImport = localPdfImport,
                preset = preset,
                themeKey = themeKey,
                themeMode = themeMode,
                libraryLayout = libraryLayout,
                themeCatalog = themeCatalog,
                onSearch = onSearch,
                onClearSearch = onClearSearch,
                onSave = onSave,
                onSaveSearch = onSaveSearch,
                onRefreshSavedSearch = onRefreshSavedSearch,
                onDeleteSavedSearch = onDeleteSavedSearch,
                onMarkSavedSearchHitRead = onMarkSavedSearchHitRead,
                onSaveSavedSearchHit = onSaveSavedSearchHit,
                onRemoveHistory = onRemoveHistory,
                onReadingStatusChange = onReadingStatusChange,
                onRepairSavedPaper = onRepairSavedPaper,
                onRemovePaper = onRemovePaper,
                onCreateCollection = onCreateCollection,
                onRenameCollection = onRenameCollection,
                onDeleteCollection = onDeleteCollection,
                onSetPaperCollections = onSetPaperCollections,
                onRequestDownload = onRequestDownload,
                onGetDownloadedPaper = onGetDownloadedPaper,
                onLoadReadablePaper = onLoadReadablePaper,
                onDeleteDownload = onDeleteDownload,
                onCancelDownloadTask = onCancelDownloadTask,
                onRetryDownloadTask = onRetryDownloadTask,
                onRemoveDownloadTask = onRemoveDownloadTask,
                onRequestLocalPdfImport = onRequestLocalPdfImport,
                onConfirmLocalPdfImport = onConfirmLocalPdfImport,
                onDismissLocalPdfImport = onDismissLocalPdfImport,
                onRequestBackupExport = onRequestBackupExport,
                onRequestBackupImport = onRequestBackupImport,
                onConfirmBackupRestore = onConfirmBackupRestore,
                onDismissBackupState = onDismissBackupState,
                onThemeChange = onThemeChange,
                onThemeModeChange = onThemeModeChange,
                onLibraryLayoutChange = onLibraryLayoutChange,
                automaticRefreshEnabled = automaticRefreshEnabled,
                onAutomaticRefreshChange = onAutomaticRefreshChange,
                onProviderEnabledChange = onProviderEnabledChange,
                notificationsAvailable = notificationsAvailable,
                onOpenNotificationSettings = onOpenNotificationSettings,
                modifier = modifier,
            )
        }
    }
}

private const val METADATA_BACKUP_MIME_TYPE = "application/vnd.paperreader.backup+zip"
private const val DEFAULT_METADATA_BACKUP_FILE_NAME = "paper-reader-metadata.paperreader.backup"
