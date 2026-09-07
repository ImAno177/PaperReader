package dev.paperreader.app.ui

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import dev.paperreader.app.R
import dev.paperreader.app.extensions.CommunityThemeCatalog
import dev.paperreader.app.importer.IncomingPdfRequest
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.reader.PdfReaderActivity
import dev.paperreader.app.reader.ReadablePaperActivity
import dev.paperreader.app.search.GoogleSearchActivity
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.PaperDateFormat
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.screen.*
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemeMode
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.backup.MetadataBackupExport
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    screenViewModelFactory: ScreenViewModelFactory,
    preset: PaperThemePreset,
    themeKey: String,
    themeMode: PaperThemeMode,
    dateFormat: PaperDateFormat,
    relativeTimeEnabled: Boolean,
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val googleCanvasColor = PaperTheme.tokens.canvas.toArgb()
    val googleInkColor = PaperTheme.tokens.ink.toArgb()
    val googleDarkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
    val invalidReferenceMessage = stringResource(R.string.share_reference_invalid)
    var activeIncomingPdfRequestId by remember { mutableStateOf<Long?>(null) }

    IncomingPaperReferenceEffect(
        request = incomingPaperReferenceRequest,
        onNavigateToDiscover = {
            if (navController.currentDestination?.route != AppRoutes.DISCOVER) {
                navController.navigate(AppRoutes.DISCOVER) { launchSingleTop = true }
            }
        },
        onSearch = { query ->
            val entry = navController.getBackStackEntry(AppRoutes.DISCOVER)
            ViewModelProvider(entry, screenViewModelFactory.discover())
                .get(DiscoverViewModel::class.java)
                .onAction(DiscoverAction.Search(query))
        },
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

    LaunchedEffect(incomingPdfRequest) {
        val request = incomingPdfRequest ?: return@LaunchedEffect
        if (activeIncomingPdfRequestId != null) return@LaunchedEffect
        navController.navigateToMoreBranch(AppRoutes.MORE_READING_IMPORTS)
        val entry = navController.getBackStackEntry(AppRoutes.MORE)
        val imports = ViewModelProvider(entry, screenViewModelFactory.readingImports())
            .get(ReadingImportsViewModel::class.java)
        if (imports.onAction(ReadingImportsAction.Prepare(request.uri.toString()))) {
            activeIncomingPdfRequestId = request.id
        } else {
            onIncomingPdfConsumed(request.id)
        }
    }

    NavHost(navController = navController, startDestination = AppRoutes.LIBRARY, modifier = modifier) {
        composable(AppRoutes.LIBRARY) {
            val libraryViewModel: LibraryViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.library() },
            )
            val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
            val readLibraryPaper: (PaperUi) -> Unit = { paper ->
                val readable = paper.manifestations.firstOrNull {
                    it.source.equals("arxiv", ignoreCase = true)
                }
                val localPdf = paper.manifestations.firstOrNull { it.localCopy != null }
                when {
                    readable != null -> context.startActivity(
                        ReadablePaperActivity.createIntent(
                            context = context,
                            workId = WorkId(paper.id),
                            manifestationId = ManifestationId(readable.id),
                            title = paper.title,
                            themePreset = preset,
                            themeKey = themeKey,
                            themeMode = themeMode,
                            showImagesInDescription = showImagesInDescription,
                        ),
                    )
                    localPdf != null -> scope.launch {
                        val downloaded = try {
                            libraryViewModel.downloadedPaper(localPdf.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (downloaded == null) {
                            navController.navigate(AppRoutes.detail(paper.id))
                        } else {
                            context.startActivity(
                                PdfReaderActivity.createIntent(
                                    context = context,
                                    downloadedPaper = downloaded,
                                    workId = WorkId(paper.id),
                                    title = paper.title,
                                    themePreset = preset,
                                    themeKey = themeKey,
                                    themeMode = themeMode,
                                ),
                            )
                        }
                    }
                    else -> navController.navigate(AppRoutes.detail(paper.id))
                }
            }
            LibraryScreen(
                state = libraryState,
                onAction = libraryViewModel::onAction,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onDiscover = { navController.navigate(AppRoutes.DISCOVER) },
                onReadPaper = readLibraryPaper,
            )
        }
        composable(AppRoutes.DISCOVER) {
            val discoverViewModel: DiscoverViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.discover() },
            )
            val searchState by discoverViewModel.uiState.collectAsStateWithLifecycle()
            val savedSearches by discoverViewModel.savedSearches.collectAsStateWithLifecycle()
            val savedSearchActions by discoverViewModel.savedSearchActions.collectAsStateWithLifecycle()
            DiscoverScreen(
                state = searchState,
                onSearch = { discoverViewModel.onAction(DiscoverAction.Search(it)) },
                onClear = { discoverViewModel.onAction(DiscoverAction.ClearSearch) },
                onSave = { discoverViewModel.onAction(DiscoverAction.Save(it)) },
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                savedSearches = savedSearches,
                savedSearchActions = savedSearchActions,
                onSaveSearch = { discoverViewModel.onAction(DiscoverAction.CreateSavedSearch(it)) },
                onOpenUpdates = { navController.navigate(AppRoutes.UPDATES) { launchSingleTop = true } },
                onSearchGoogle = { query ->
                    context.startActivity(
                        GoogleSearchActivity.createIntent(
                            context = context,
                            query = query,
                            canvasColor = googleCanvasColor,
                            inkColor = googleInkColor,
                            darkTheme = googleDarkTheme,
                        ),
                    )
                },
            )
        }
        composable(AppRoutes.UPDATES) {
            val updatesViewModel: UpdatesViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.updates() },
            )
            val updatesState by updatesViewModel.uiState.collectAsStateWithLifecycle()
            UpdatesScreen(
                tasks = updatesState.tasks,
                library = updatesState.library,
                providers = updatesState.providers,
                savedSearches = updatesState.savedSearches,
                savedSearchActions = updatesState.savedSearchActions,
                actions = updatesState.downloadActions,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onRefreshSearch = { updatesViewModel.onAction(UpdatesAction.RefreshSearch(it)) },
                onDeleteSearch = { updatesViewModel.onAction(UpdatesAction.DeleteSearch(it)) },
                onMarkHitRead = { updatesViewModel.onAction(UpdatesAction.MarkHitRead(it)) },
                onSaveHit = { updatesViewModel.onAction(UpdatesAction.SaveHit(it)) },
                onCancel = { updatesViewModel.onAction(UpdatesAction.CancelTask(it)) },
                onRetry = { updatesViewModel.onAction(UpdatesAction.RetryTask(it)) },
                onRemove = { updatesViewModel.onAction(UpdatesAction.RemoveTask(it)) },
                dateFormat = dateFormat,
                relativeTimeEnabled = relativeTimeEnabled,
            )
        }
        composable(AppRoutes.HISTORY) {
            val historyViewModel: HistoryViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.history() },
            )
            val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
            HistoryScreen(
                state = historyState,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onRemove = { historyViewModel.onAction(HistoryAction.Remove(it)) },
                dateFormat = dateFormat,
                relativeTimeEnabled = relativeTimeEnabled,
            )
        }
        composable(AppRoutes.MORE) { entry ->
            val moreEntry = remember(entry) { navController.getBackStackEntry(AppRoutes.MORE) }
            val moreViewModel: MoreViewModel = viewModel(
                viewModelStoreOwner = moreEntry,
                factory = remember(screenViewModelFactory) { screenViewModelFactory.more() },
            )
            val importsViewModel: ReadingImportsViewModel = viewModel(
                viewModelStoreOwner = moreEntry,
                factory = remember(screenViewModelFactory) { screenViewModelFactory.readingImports() },
            )
            val backupViewModel: DataBackupViewModel = viewModel(
                viewModelStoreOwner = moreEntry,
                factory = remember(screenViewModelFactory) { screenViewModelFactory.dataBackup() },
            )
            val moreState by moreViewModel.uiState.collectAsStateWithLifecycle()
            val importsState by importsViewModel.uiState.collectAsStateWithLifecycle()
            val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(importsState, backupState, notificationsAvailable) {
                moreViewModel.onAction(MoreAction.SetLocalPdfImportState(importsState))
                moreViewModel.onAction(MoreAction.SetBackupState(backupState))
                moreViewModel.onAction(MoreAction.SetNotificationsAvailable(notificationsAvailable))
            }
            MoreScreen(
                state = moreState,
                selectedPreset = preset,
                selectedThemeName = themeCatalog.themes.firstOrNull { it.storageKey == moreState.themeKey }?.displayName,
                onOpenAppearance = { navController.navigateToMoreBranch(AppRoutes.MORE_APPEARANCE) },
                onOpenCollections = { navController.navigateToMoreBranch(AppRoutes.MORE_COLLECTIONS) },
                onOpenReadingImports = { navController.navigateToMoreBranch(AppRoutes.MORE_READING_IMPORTS) },
                onOpenUpdates = { navController.navigateToMoreBranch(AppRoutes.MORE_UPDATES) },
                onOpenDataBackup = { navController.navigateToMoreBranch(AppRoutes.MORE_DATA_BACKUP) },
                onOpenSources = { navController.navigateToMoreBranch(AppRoutes.MORE_SOURCES) },
                onOpenDownloadQueue = { navController.navigateToMoreBranch(AppRoutes.MORE_DOWNLOAD_QUEUE) },
                onOpenStats = { navController.navigateToMoreBranch(AppRoutes.MORE_STATS) },
                onOpenSettings = { navController.navigateToMoreBranch(AppRoutes.MORE_SETTINGS) },
                onOpenAbout = { navController.navigateToMoreBranch(AppRoutes.MORE_ABOUT) },
                onOpenHelp = { uriHandler.openUri(PAPERREADER_HELP_URL) },
            )
        }
        composable(AppRoutes.MORE_APPEARANCE) {
            val appearanceViewModel: AppearanceViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.appearance() },
            )
            val appearanceState by appearanceViewModel.uiState.collectAsStateWithLifecycle()
            AppearanceScreen(
                selectedThemeKey = appearanceState.themeKey,
                selectedThemeMode = appearanceState.themeMode,
                communityThemes = themeCatalog.themes,
                communityThemesLoading = themeCatalog.loading,
                communityThemeIssues = themeCatalog.issues,
                onThemeChange = { appearanceViewModel.onAction(AppearanceAction.SetThemeKey(it)) },
                onThemeModeChange = { appearanceViewModel.onAction(AppearanceAction.SetThemeMode(it)) },
                selectedLibraryLayout = appearanceState.libraryLayout,
                onLibraryLayoutChange = { appearanceViewModel.onAction(AppearanceAction.SetLibraryLayout(it)) },
                selectedDateFormat = appearanceState.dateFormat,
                onDateFormatChange = { appearanceViewModel.onAction(AppearanceAction.SetDateFormat(it)) },
                relativeTimeEnabled = appearanceState.relativeTimeEnabled,
                onRelativeTimeChange = { appearanceViewModel.onAction(AppearanceAction.SetRelativeTime(it)) },
                tabletUiMode = appearanceState.tabletUiMode,
                onTabletUiModeChange = { appearanceViewModel.onAction(AppearanceAction.SetTabletUiMode(it)) },
                showImagesInDescription = appearanceState.showImagesInDescription,
                onShowImagesInDescriptionChange = { appearanceViewModel.onAction(AppearanceAction.SetShowImagesInDescription(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_COLLECTIONS) {
            val collectionsViewModel: CollectionsViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.collections() },
            )
            val collectionsState by collectionsViewModel.uiState.collectAsStateWithLifecycle()
            CollectionsScreen(
                collections = collectionsState,
                onCreateCollection = collectionsViewModel::createCollection,
                onRenameCollection = collectionsViewModel::renameCollection,
                onDeleteCollection = collectionsViewModel::deleteCollection,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_READING_IMPORTS) { entry ->
            val moreEntry = remember(entry) { navController.getBackStackEntry(AppRoutes.MORE) }
            val importsViewModel: ReadingImportsViewModel = viewModel(
                viewModelStoreOwner = moreEntry,
                factory = remember(screenViewModelFactory) { screenViewModelFactory.readingImports() },
            )
            val importsState by importsViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(importsState, activeIncomingPdfRequestId) {
                val requestId = activeIncomingPdfRequestId
                if (
                    requestId != null &&
                    (importsState is LocalPdfImportUiState.Confirming ||
                        importsState is LocalPdfImportUiState.Failed)
                ) {
                    onIncomingPdfConsumed(requestId)
                    activeIncomingPdfRequestId = null
                }
            }
            ReadingImportsScreen(
                state = importsState,
                onRequestImport = {
                    onRequestLocalPdfImport {
                        importsViewModel.onAction(ReadingImportsAction.Prepare(it))
                    }
                },
                onConfirmImport = { importsViewModel.onAction(ReadingImportsAction.Confirm(it)) },
                onDismissImport = {
                    importsViewModel.onAction(ReadingImportsAction.Dismiss)
                    activeIncomingPdfRequestId?.let(onIncomingPdfConsumed)
                    activeIncomingPdfRequestId = null
                },
                onOpenImportedPaper = { navController.navigate(AppRoutes.detail(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_UPDATES) {
            UpdatesNotificationsScreen(
                automaticRefreshEnabled,
                notificationsAvailable,
                onAutomaticRefreshChange,
                onOpenNotificationSettings,
                navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_DATA_BACKUP) { entry ->
            val moreEntry = remember(entry) { navController.getBackStackEntry(AppRoutes.MORE) }
            val backupViewModel: DataBackupViewModel = viewModel(
                viewModelStoreOwner = moreEntry,
                factory = remember(screenViewModelFactory) { screenViewModelFactory.dataBackup() },
            )
            val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
            DataBackupScreen(
                state = backupState,
                onRequestExport = {
                    onRequestBackupExport { writer -> backupViewModel.createBackup(writer) }
                },
                onRequestImport = {
                    onRequestBackupImport { reader -> backupViewModel.previewRestore(reader) }
                },
                onConfirmRestore = { backupViewModel.onAction(DataBackupAction.ConfirmRestore) },
                onDismissState = { backupViewModel.onAction(DataBackupAction.Dismiss) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_DOWNLOAD_QUEUE) {
            val queueViewModel: DownloadQueueViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.downloadQueue() },
            )
            val queueState by queueViewModel.uiState.collectAsStateWithLifecycle()
            DownloadQueueScreen(
                tasks = queueState.tasks,
                library = queueState.library,
                actions = queueState.actions,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onCancel = { queueViewModel.onAction(DownloadQueueAction.Cancel(it)) },
                onRetry = { queueViewModel.onAction(DownloadQueueAction.Retry(it)) },
                onRemove = { queueViewModel.onAction(DownloadQueueAction.Remove(it)) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_STATS) {
            val statsViewModel: StatsViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.stats() },
            )
            val statsState by statsViewModel.uiState.collectAsStateWithLifecycle()
            StatsScreen(
                library = statsState.library,
                history = statsState.history,
                collections = statsState.collections,
                tasks = statsState.tasks,
                savedSearches = statsState.savedSearches,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.settings() },
            )
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                state = settingsState,
                onAction = settingsViewModel::onAction,
                onOpenAppearance = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_APPEARANCE) },
                onOpenLibrary = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_LIBRARY_SETTINGS) },
                onOpenReader = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_READER_SETTINGS) },
                onOpenDownloads = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_DOWNLOAD_QUEUE) },
                onOpenUpdates = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_UPDATES) },
                onOpenDataBackup = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_DATA_BACKUP) },
                onOpenSources = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_SOURCES) },
                onOpenAbout = { navController.navigateToMoreChild(AppRoutes.MORE_SETTINGS, AppRoutes.MORE_ABOUT) },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_LIBRARY_SETTINGS) {
            val appearanceViewModel: AppearanceViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.appearance() },
            )
            val appearanceState by appearanceViewModel.uiState.collectAsStateWithLifecycle()
            LibrarySettingsScreen(
                selectedLayout = appearanceState.libraryLayout,
                onLayoutChange = { appearanceViewModel.onAction(AppearanceAction.SetLibraryLayout(it)) },
                onOpenCollections = {
                    navController.navigateToMoreChild(AppRoutes.MORE_LIBRARY_SETTINGS, AppRoutes.MORE_COLLECTIONS)
                },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_READER_SETTINGS) {
            ReaderSettingsScreen(onBack = navController::popBackStack)
        }
        composable(AppRoutes.MORE_SOURCES) {
            val sourcesViewModel: SourcesViewModel = viewModel(
                factory = remember(screenViewModelFactory) { screenViewModelFactory.sources() },
            )
            val providers by sourcesViewModel.providers.collectAsStateWithLifecycle()
            val stores by sourcesViewModel.extensionStores.collectAsStateWithLifecycle()
            val storeAction by sourcesViewModel.action.collectAsStateWithLifecycle()
            SourcesScreen(
                providers = providers,
                extensionStores = stores,
                extensionStoreAction = storeAction,
                onPreviewStore = { indexUrl, publicKey ->
                    sourcesViewModel.onAction(SourcesAction.PreviewStore(indexUrl, publicKey))
                },
                onConfirmStore = { sourcesViewModel.onAction(SourcesAction.ConfirmStore) },
                onDismissStoreAction = { sourcesViewModel.onAction(SourcesAction.DismissStoreAction) },
                onRefreshStore = { sourcesViewModel.onAction(SourcesAction.RefreshStore(it)) },
                onRemoveStore = { sourcesViewModel.onAction(SourcesAction.RemoveStore(it)) },
                installStates = extensionInstalls.installStates,
                installedThemeVersions = themeCatalog.installedVersions,
                blockedThemePackages = themeCatalog.issues.mapTo(hashSetOf()) { it.packageName },
                onInstallExtension = extensionInstalls.onInstallExtension,
                onDismissInstallState = extensionInstalls.onDismissInstallState,
                onProviderEnabledChange = { providerId, enabled ->
                    sourcesViewModel.onAction(SourcesAction.SetProviderEnabled(providerId, enabled))
                },
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_ABOUT) { AboutScreen(onBack = navController::popBackStack) }
        composable(AppRoutes.DETAIL, arguments = listOf(navArgument("workId") { type = NavType.StringType })) { entry ->
            val workId = entry.arguments?.getString("workId").orEmpty()
            val detailViewModel: PaperDetailViewModel = viewModel(
                factory = remember(screenViewModelFactory, workId) { screenViewModelFactory.detail(workId) },
            )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()
            DetailScreen(
                state = detailState.paper,
                collections = detailState.collections,
                themePreset = preset,
                themeKey = themeKey,
                themeMode = themeMode,
                downloadTasks = (detailState.tasks as? dev.paperreader.app.ui.state.LoadState.Ready)?.value
                    ?.filter { it.workId?.value == workId }
                    .orEmpty(),
                showImagesInDescription = showImagesInDescription,
                requestingManifestations = detailState.downloadActions.requestingManifestations,
                failedManifestations = detailState.downloadActions.failedManifestations,
                onBack = navController::popBackStack,
                onStatusChange = { detailViewModel.onAction(PaperDetailAction.SetStatus(it)) },
                onRepairSavedPaper = { detailViewModel.onAction(PaperDetailAction.RepairSavedPaper) },
                onRequestDownload = { detailViewModel.onAction(PaperDetailAction.RequestDownload(it)) },
                onGetDownloadedPaper = detailViewModel::downloadedPaper,
                onLoadReadablePaper = detailViewModel::loadReadablePaper,
                onReadReadableAsset = detailViewModel::readReadablePaperAsset,
                onDeleteDownload = detailViewModel::deleteDownload,
                onRemove = detailViewModel::remove,
                onSetCollections = detailViewModel::setCollections,
                onRemoved = navController::popBackStack,
            )
        }
    }
}

private const val PAPERREADER_HELP_URL = "https://github.com/ImAno177/PaperReader#readme"
