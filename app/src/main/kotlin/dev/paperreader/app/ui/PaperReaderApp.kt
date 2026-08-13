package dev.paperreader.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.paperreader.app.R
import dev.paperreader.app.download.DownloadWorkScheduler
import dev.paperreader.app.importer.IncomingPdfRequest
import dev.paperreader.app.backup.MetadataBackupFileGateway
import dev.paperreader.app.backup.FileMetadataRestoreSessionStore
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.MetadataBackupOperation
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.screen.DetailScreen
import dev.paperreader.app.ui.screen.DiscoverScreen
import dev.paperreader.app.ui.screen.AppearanceScreen
import dev.paperreader.app.ui.screen.CollectionsScreen
import dev.paperreader.app.ui.screen.DataBackupScreen
import dev.paperreader.app.ui.screen.HistoryScreen
import dev.paperreader.app.ui.screen.LibraryScreen
import dev.paperreader.app.ui.screen.MoreScreen
import dev.paperreader.app.ui.screen.ReadingImportsScreen
import dev.paperreader.app.ui.screen.SourcesScreen
import dev.paperreader.app.ui.screen.UpdatesNotificationsScreen
import dev.paperreader.app.ui.screen.UpdatesScreen
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.app.settings.PaperReaderPreferences
import dev.paperreader.app.updates.SavedSearchRefreshScheduler
import dev.paperreader.app.updates.SavedSearchNotificationPublisher
import dev.paperreader.logic.PaperReaderLogic
import dev.paperreader.logic.backup.MAX_METADATA_BACKUP_ARCHIVE_BYTES
import kotlinx.coroutines.launch

private object AppRoutes {
    const val LIBRARY = "library"
    const val DISCOVER = "discover"
    const val UPDATES = "updates"
    const val HISTORY = "history"
    const val MORE = "more"
    const val MORE_APPEARANCE = "more/appearance"
    const val MORE_COLLECTIONS = "more/collections"
    const val MORE_READING_IMPORTS = "more/reading-imports"
    const val MORE_UPDATES = "more/updates"
    const val MORE_DATA_BACKUP = "more/data-backup"
    const val MORE_SOURCES = "more/sources"
    const val DETAIL = "detail/{workId}"

    fun detail(workId: String): String = "detail/${Uri.encode(workId)}"
}

internal fun isMoreRoute(route: String?): Boolean =
    route == AppRoutes.MORE || route?.startsWith("${AppRoutes.MORE}/") == true

internal fun shouldShowPrimaryNavigation(route: String?): Boolean =
    route != AppRoutes.DETAIL && route?.startsWith("${AppRoutes.MORE}/") != true

internal const val PRIMARY_NAVIGATION_TEST_TAG = "primary-navigation"

private fun isDestinationSelected(destination: AppDestination, currentRoute: String?): Boolean =
    if (destination == AppDestination.MORE) isMoreRoute(currentRoute) else currentRoute == destination.route

private enum class AppDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    LIBRARY(AppRoutes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Outlined.MenuBook),
    DISCOVER(AppRoutes.DISCOVER, R.string.nav_discover, Icons.Outlined.Search),
    UPDATES(AppRoutes.UPDATES, R.string.nav_updates, Icons.Outlined.Update),
    HISTORY(AppRoutes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    MORE(AppRoutes.MORE, R.string.nav_more, Icons.Outlined.MoreHoriz),
}

@Composable
fun PaperReaderApp(
    preferences: PaperReaderPreferences,
    logic: PaperReaderLogic?,
    downloadWorkScheduler: DownloadWorkScheduler,
    savedSearchRefreshScheduler: SavedSearchRefreshScheduler,
    incomingPdfRequest: IncomingPdfRequest?,
    onIncomingPdfConsumed: (Long) -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
) {
    val preset by preferences.theme.collectAsStateWithLifecycle(PaperThemePreset.NEOBRUTALISM)
    PaperReaderTheme(preset) {
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
                logic = logic,
                preset = preset,
                downloadWorkScheduler = downloadWorkScheduler,
                savedSearchRefreshScheduler = savedSearchRefreshScheduler,
                incomingPdfRequest = incomingPdfRequest,
                onIncomingPdfConsumed = onIncomingPdfConsumed,
                openUpdatesRequestId = openUpdatesRequestId,
                onOpenUpdatesConsumed = onOpenUpdatesConsumed,
            )
        }
    }
}

@Composable
private fun PaperReaderContent(
    preferences: PaperReaderPreferences,
    logic: PaperReaderLogic,
    preset: PaperThemePreset,
    downloadWorkScheduler: DownloadWorkScheduler,
    savedSearchRefreshScheduler: SavedSearchRefreshScheduler,
    incomingPdfRequest: IncomingPdfRequest?,
    onIncomingPdfConsumed: (Long) -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val restoreSessionStore = remember(context) {
        FileMetadataRestoreSessionStore(
            directory = java.io.File(context.noBackupFilesDir, "metadata-restore-session"),
            maximumBytes = MAX_METADATA_BACKUP_ARCHIVE_BYTES,
        )
    }
    val viewModel: PaperReaderViewModel = viewModel(
        factory = remember(logic, downloadWorkScheduler, restoreSessionStore) {
            PaperReaderViewModel.factory(logic, downloadWorkScheduler, restoreSessionStore)
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
    val automaticRefreshEnabled by preferences.automaticSavedSearchRefreshEnabled
        .collectAsStateWithLifecycle(false)
    val notificationPublisher = remember(context) { SavedSearchNotificationPublisher(context) }
    var notificationsAvailable by remember { mutableStateOf(notificationPublisher.canPost()) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAvailable = notificationPublisher.canPost()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, notificationPublisher) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAvailable = notificationPublisher.canPost()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Activity-local IDs restart after recreation; sourceKey handles durable correlation instead.
    var activeIncomingPdfRequestId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
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
        search = search,
        downloadActions = downloadActions,
        savedSearchActions = savedSearchActions,
        metadataBackup = metadataBackup,
        localPdfImport = localPdfImport,
        preset = preset,
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
        onRemovePaper = viewModel::removePaper,
        onCreateCollection = viewModel::createCollection,
        onRenameCollection = viewModel::renameCollection,
        onDeleteCollection = viewModel::deleteCollection,
        onSetPaperCollections = viewModel::setPaperCollections,
        onRequestDownload = viewModel::requestDownload,
        onGetDownloadedPaper = viewModel::downloadedPaper,
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
        onPresetChange = { next -> scope.launch { preferences.setTheme(next) } },
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
        notificationsAvailable = notificationsAvailable,
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        openUpdatesRequestId = openUpdatesRequestId,
        onOpenUpdatesConsumed = onOpenUpdatesConsumed,
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
    search: SearchUiState,
    downloadActions: DownloadActionUiState,
    savedSearchActions: SavedSearchActionUiState,
    metadataBackup: dev.paperreader.app.ui.model.MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
    preset: PaperThemePreset,
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
    onRemovePaper: suspend (String) -> dev.paperreader.logic.domain.repository.RemovePaperResult,
    onCreateCollection: suspend (String) -> dev.paperreader.logic.domain.repository.CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> dev.paperreader.logic.domain.repository.RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> dev.paperreader.logic.domain.repository.DeleteCollectionResult,
    onSetPaperCollections: suspend (String, Set<Long>) -> dev.paperreader.logic.domain.repository.SetPaperCollectionsResult,
    onRequestDownload: (String, String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> dev.paperreader.logic.task.DownloadedPaper?,
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
    onPresetChange: (PaperThemePreset) -> Unit,
    automaticRefreshEnabled: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    notificationsAvailable: Boolean,
    onOpenNotificationSettings: () -> Unit,
    openUpdatesRequestId: Long?,
    onOpenUpdatesConsumed: (Long) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val chromeHidden = !shouldShowPrimaryNavigation(currentRoute)
    val destinations = AppDestination.entries

    LaunchedEffect(openUpdatesRequestId) {
        val requestId = openUpdatesRequestId ?: return@LaunchedEffect
        if (navController.currentDestination?.route != AppRoutes.UPDATES) {
            navController.navigate(AppRoutes.UPDATES) { launchSingleTop = true }
        }
        onOpenUpdatesConsumed(requestId)
    }

    LaunchedEffect(metadataBackup, localPdfImport) {
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
            search = search,
            downloadActions = downloadActions,
            savedSearchActions = savedSearchActions,
            metadataBackup = metadataBackup,
            localPdfImport = localPdfImport,
            preset = preset,
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
            onRemovePaper = onRemovePaper,
            onCreateCollection = onCreateCollection,
            onRenameCollection = onRenameCollection,
            onDeleteCollection = onDeleteCollection,
            onSetPaperCollections = onSetPaperCollections,
            onRequestDownload = onRequestDownload,
            onGetDownloadedPaper = onGetDownloadedPaper,
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
            onPresetChange = onPresetChange,
            automaticRefreshEnabled = automaticRefreshEnabled,
            onAutomaticRefreshChange = onAutomaticRefreshChange,
            notificationsAvailable = notificationsAvailable,
            onOpenNotificationSettings = onOpenNotificationSettings,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        AdaptiveAppShell(
            destinations = destinations,
            currentRoute = currentRoute,
            onNavigate = { destination ->
                navController.navigate(destination.route) {
                    popUpTo(AppRoutes.LIBRARY) { saveState = true }
                    launchSingleTop = true
                    // The library is already the retained start destination. Restoring the
                    // state just popped above can resurrect the previous tab over it.
                    restoreState = destination != AppDestination.LIBRARY
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
                search = search,
                downloadActions = downloadActions,
                savedSearchActions = savedSearchActions,
                metadataBackup = metadataBackup,
                localPdfImport = localPdfImport,
                preset = preset,
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
                onRemovePaper = onRemovePaper,
                onCreateCollection = onCreateCollection,
                onRenameCollection = onRenameCollection,
                onDeleteCollection = onDeleteCollection,
                onSetPaperCollections = onSetPaperCollections,
                onRequestDownload = onRequestDownload,
                onGetDownloadedPaper = onGetDownloadedPaper,
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
                onPresetChange = onPresetChange,
                automaticRefreshEnabled = automaticRefreshEnabled,
                onAutomaticRefreshChange = onAutomaticRefreshChange,
                notificationsAvailable = notificationsAvailable,
                onOpenNotificationSettings = onOpenNotificationSettings,
                modifier = modifier,
            )
        }
    }
}

internal fun shouldOpenMoreForLocalPdfImport(state: LocalPdfImportUiState): Boolean = when (state) {
    LocalPdfImportUiState.Idle,
    LocalPdfImportUiState.Preparing,
    -> false

    is LocalPdfImportUiState.Confirming,
    is LocalPdfImportUiState.Importing,
    is LocalPdfImportUiState.Complete,
    is LocalPdfImportUiState.Failed,
    -> true
}

internal enum class MoreAttentionDestination {
    DATA_BACKUP,
    READING_IMPORTS,
}

internal fun moreAttentionDestination(
    metadataBackup: MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
): MoreAttentionDestination? {
    val restoreNeedsAttention = metadataBackup is MetadataBackupUiState.Preview ||
        metadataBackup is MetadataBackupUiState.Restoring ||
        (metadataBackup is MetadataBackupUiState.Failed &&
            metadataBackup.operation == MetadataBackupOperation.PREVIEW)
    return when {
        restoreNeedsAttention -> MoreAttentionDestination.DATA_BACKUP
        shouldOpenMoreForLocalPdfImport(localPdfImport) -> MoreAttentionDestination.READING_IMPORTS
        else -> null
    }
}

private fun NavHostController.navigateToMoreBranch(route: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == route) return
    if (!isMoreRoute(currentRoute)) {
        navigate(AppRoutes.MORE) { launchSingleTop = true }
    }
    navigate(route) {
        popUpTo(AppRoutes.MORE) { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
private fun AdaptiveAppShell(
    destinations: List<AppDestination>,
    currentRoute: String?,
    onNavigate: (AppDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(PaperTheme.tokens.canvas)) {
        if (maxWidth < 600.dp) {
            androidx.compose.material3.Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = PaperTheme.tokens.canvas,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.testTag(PRIMARY_NAVIGATION_TEST_TAG),
                        containerColor = PaperTheme.tokens.canvas,
                        contentColor = PaperTheme.tokens.ink,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            val selected = isDestinationSelected(destination, currentRoute)
                            val label = stringResource(destination.labelRes)
                            PaperDestinationItem(
                                modifier = Modifier.weight(1f).padding(horizontal = 1.dp, vertical = 4.dp),
                                selected = selected,
                                onClick = { onNavigate(destination) },
                                icon = destination.icon,
                                label = label,
                            )
                        }
                    }
                },
            ) { padding ->
                content(Modifier.fillMaxSize().padding(padding))
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.testTag(PRIMARY_NAVIGATION_TEST_TAG),
                    containerColor = PaperTheme.tokens.canvas,
                    contentColor = PaperTheme.tokens.ink,
                    windowInsets = NavigationRailDefaults.windowInsets,
                ) {
                    destinations.forEach { destination ->
                        val selected = isDestinationSelected(destination, currentRoute)
                        val label = stringResource(destination.labelRes)
                        PaperDestinationItem(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(min = 76.dp),
                            selected = selected,
                            onClick = { onNavigate(destination) },
                            icon = destination.icon,
                            label = label,
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun PaperDestinationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = PaperTheme.tokens
    Surface(
        modifier = modifier
            .heightIn(min = 72.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        shape = RoundedCornerShape(tokens.cornerRadius),
        color = if (selected) tokens.primaryContainer else Color.Transparent,
        contentColor = if (selected) tokens.onPrimaryContainer else tokens.inkMuted,
        border = if (selected) BorderStroke(tokens.borderWidth.coerceAtLeast(1.dp), tokens.border) else null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 1.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    library: LoadState<List<PaperUi>>,
    history: LoadState<List<dev.paperreader.app.ui.model.ReadingHistoryUi>>,
    collections: LoadState<List<PaperCollectionUi>>,
    tasks: LoadState<List<dev.paperreader.logic.task.PaperTask>>,
    savedSearches: LoadState<List<dev.paperreader.logic.domain.SavedSearchFeed>>,
    providers: dev.paperreader.logic.provider.ProviderManagerState,
    search: SearchUiState,
    downloadActions: DownloadActionUiState,
    savedSearchActions: SavedSearchActionUiState,
    metadataBackup: dev.paperreader.app.ui.model.MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
    preset: PaperThemePreset,
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
    onRemovePaper: suspend (String) -> dev.paperreader.logic.domain.repository.RemovePaperResult,
    onCreateCollection: suspend (String) -> dev.paperreader.logic.domain.repository.CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> dev.paperreader.logic.domain.repository.RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> dev.paperreader.logic.domain.repository.DeleteCollectionResult,
    onSetPaperCollections: suspend (String, Set<Long>) -> dev.paperreader.logic.domain.repository.SetPaperCollectionsResult,
    onRequestDownload: (String, String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> dev.paperreader.logic.task.DownloadedPaper?,
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
    onPresetChange: (PaperThemePreset) -> Unit,
    automaticRefreshEnabled: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    notificationsAvailable: Boolean,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppRoutes.LIBRARY,
        modifier = modifier,
    ) {
        composable(AppRoutes.LIBRARY) {
            LibraryScreen(
                state = library,
                collections = collections,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onDiscover = { navController.navigate(AppRoutes.DISCOVER) },
            )
        }
        composable(AppRoutes.DISCOVER) {
            DiscoverScreen(
                state = search,
                onSearch = onSearch,
                onClear = onClearSearch,
                onSave = onSave,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                savedSearches = savedSearches,
                savedSearchActions = savedSearchActions,
                onSaveSearch = onSaveSearch,
                onOpenUpdates = { navController.navigate(AppRoutes.UPDATES) { launchSingleTop = true } },
            )
        }
        composable(AppRoutes.UPDATES) {
            UpdatesScreen(
                tasks = tasks,
                library = library,
                providers = providers,
                savedSearches = savedSearches,
                savedSearchActions = savedSearchActions,
                actions = downloadActions,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onRefreshSearch = onRefreshSavedSearch,
                onDeleteSearch = onDeleteSavedSearch,
                onMarkHitRead = onMarkSavedSearchHitRead,
                onSaveHit = onSaveSavedSearchHit,
                onCancel = onCancelDownloadTask,
                onRetry = onRetryDownloadTask,
                onRemove = onRemoveDownloadTask,
            )
        }
        composable(AppRoutes.HISTORY) {
            HistoryScreen(
                state = history,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onRemove = onRemoveHistory,
            )
        }
        composable(AppRoutes.MORE) {
            MoreScreen(
                selectedPreset = preset,
                automaticRefreshEnabled = automaticRefreshEnabled,
                notificationsAvailable = notificationsAvailable,
                providers = providers,
                collections = collections,
                localPdfImportState = localPdfImport,
                backupState = metadataBackup,
                onOpenAppearance = { navController.navigateToMoreBranch(AppRoutes.MORE_APPEARANCE) },
                onOpenCollections = { navController.navigateToMoreBranch(AppRoutes.MORE_COLLECTIONS) },
                onOpenReadingImports = { navController.navigateToMoreBranch(AppRoutes.MORE_READING_IMPORTS) },
                onOpenUpdates = { navController.navigateToMoreBranch(AppRoutes.MORE_UPDATES) },
                onOpenDataBackup = { navController.navigateToMoreBranch(AppRoutes.MORE_DATA_BACKUP) },
                onOpenSources = { navController.navigateToMoreBranch(AppRoutes.MORE_SOURCES) },
            )
        }
        composable(AppRoutes.MORE_APPEARANCE) {
            AppearanceScreen(
                selectedPreset = preset,
                onPresetChange = onPresetChange,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_COLLECTIONS) {
            CollectionsScreen(
                collections = collections,
                onCreateCollection = onCreateCollection,
                onRenameCollection = onRenameCollection,
                onDeleteCollection = onDeleteCollection,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_READING_IMPORTS) {
            ReadingImportsScreen(
                state = localPdfImport,
                onRequestImport = onRequestLocalPdfImport,
                onConfirmImport = onConfirmLocalPdfImport,
                onDismissImport = onDismissLocalPdfImport,
                onOpenImportedPaper = { workId -> navController.navigate(AppRoutes.detail(workId)) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_UPDATES) {
            UpdatesNotificationsScreen(
                automaticRefreshEnabled = automaticRefreshEnabled,
                notificationsAvailable = notificationsAvailable,
                onAutomaticRefreshChange = onAutomaticRefreshChange,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_DATA_BACKUP) {
            DataBackupScreen(
                state = metadataBackup,
                onRequestExport = onRequestBackupExport,
                onRequestImport = onRequestBackupImport,
                onConfirmRestore = onConfirmBackupRestore,
                onDismissState = onDismissBackupState,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_SOURCES) {
            SourcesScreen(
                providers = providers,
                onBack = navController::popBackStack,
            )
        }
        composable(
            route = AppRoutes.DETAIL,
            arguments = listOf(navArgument("workId") { type = NavType.StringType }),
        ) { entry ->
            val workId = entry.arguments?.getString("workId").orEmpty()
            val detail = when (library) {
                LoadState.Loading -> LoadState.Loading
                LoadState.Failed -> LoadState.Failed
                is LoadState.Ready -> LoadState.Ready(library.value.firstOrNull { it.id == workId })
            }
            DetailScreen(
                state = detail,
                collections = collections,
                themePreset = preset,
                downloadTasks = (tasks as? LoadState.Ready)?.value
                    ?.filter { it.workId?.value == workId }
                    .orEmpty(),
                requestingManifestations = downloadActions.requestingManifestations,
                failedManifestations = downloadActions.failedManifestations,
                onBack = { navController.popBackStack() },
                onStatusChange = { status -> onReadingStatusChange(workId, status) },
                onRequestDownload = { manifestationId -> onRequestDownload(workId, manifestationId) },
                onGetDownloadedPaper = onGetDownloadedPaper,
                onDeleteDownload = { manifestationId -> onDeleteDownload(workId, manifestationId) },
                onRemove = { onRemovePaper(workId) },
                onSetCollections = { selected -> onSetPaperCollections(workId, selected) },
                onRemoved = { navController.popBackStack() },
            )
        }
    }
}

private const val METADATA_BACKUP_MIME_TYPE = "application/vnd.paperreader.backup+zip"
private const val DEFAULT_METADATA_BACKUP_FILE_NAME = "paper-reader-metadata.paperreader.backup"
