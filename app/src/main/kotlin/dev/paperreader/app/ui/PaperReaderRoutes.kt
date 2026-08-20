package dev.paperreader.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.paperreader.app.ui.model.*
import dev.paperreader.app.ui.screen.*
import dev.paperreader.app.reader.PdfReaderActivity
import dev.paperreader.app.search.GoogleSearchActivity
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.reader.ReadablePaperActivity
import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.reader.ReadablePaperResult
import dev.paperreader.logic.domain.repository.*
import dev.paperreader.logic.task.*
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    library: LoadState<List<PaperUi>>,
    history: LoadState<List<ReadingHistoryUi>>,
    collections: LoadState<List<PaperCollectionUi>>,
    tasks: LoadState<List<PaperTask>>,
    savedSearches: LoadState<List<dev.paperreader.logic.domain.SavedSearchFeed>>,
    providers: ProviderManagerState,
    extensionStores: ExtensionStoreBindings,
    search: SearchUiState,
    downloadActions: DownloadActionUiState,
    savedSearchActions: SavedSearchActionUiState,
    metadataBackup: MetadataBackupUiState,
    localPdfImport: LocalPdfImportUiState,
    preset: dev.paperreader.app.ui.theme.PaperThemePreset,
    themeKey: String,
    themeMode: dev.paperreader.app.ui.theme.PaperThemeMode,
    libraryLayout: LibraryLayout,
    themeCatalog: dev.paperreader.app.extensions.CommunityThemeCatalog,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSave: (SearchPaperUi) -> Unit,
    onSaveSearch: (String) -> Unit,
    onRefreshSavedSearch: (String) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
    onMarkSavedSearchHitRead: (String) -> Unit,
    onSaveSavedSearchHit: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onReadingStatusChange: (String, ReadingStatus) -> Unit,
    onRepairSavedPaper: (String) -> Unit,
    onRemovePaper: suspend (String) -> RemovePaperResult,
    onCreateCollection: suspend (String) -> CreateCollectionResult,
    onRenameCollection: suspend (Long, String) -> RenameCollectionResult,
    onDeleteCollection: suspend (Long) -> DeleteCollectionResult,
    onSetPaperCollections: suspend (String, Set<Long>) -> SetPaperCollectionsResult,
    onRequestDownload: (String, String) -> Unit,
    onGetDownloadedPaper: suspend (String) -> DownloadedPaper?,
    onLoadReadablePaper: suspend (String, String) -> ReadablePaperResult,
    onDeleteDownload: suspend (String, String) -> DeleteDownloadResult,
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
    onThemeModeChange: (dev.paperreader.app.ui.theme.PaperThemeMode) -> Unit,
    onLibraryLayoutChange: (LibraryLayout) -> Unit,
    automaticRefreshEnabled: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    onProviderEnabledChange: (String, Boolean) -> Unit,
    notificationsAvailable: Boolean,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleCanvasColor = PaperTheme.tokens.canvas.toArgb()
    val googleInkColor = PaperTheme.tokens.ink.toArgb()
    val googleDarkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
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
                ),
            )
            localPdf != null -> scope.launch {
                val downloaded = try {
                    onGetDownloadedPaper(localPdf.id)
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
    NavHost(navController = navController, startDestination = AppRoutes.LIBRARY, modifier = modifier) {
        composable(AppRoutes.LIBRARY) {
            LibraryScreen(
                state = library,
                collections = collections,
                layout = libraryLayout,
                onLayoutChange = onLibraryLayoutChange,
                onOpenPaper = { navController.navigate(AppRoutes.detail(it)) },
                onDiscover = { navController.navigate(AppRoutes.DISCOVER) },
                onReadPaper = readLibraryPaper,
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
        composable(AppRoutes.UPDATES) { UpdatesScreen(tasks, library, providers, savedSearches, savedSearchActions, downloadActions, { navController.navigate(AppRoutes.detail(it)) }, onRefreshSavedSearch, onDeleteSavedSearch, onMarkSavedSearchHitRead, onSaveSavedSearchHit, onCancelDownloadTask, onRetryDownloadTask, onRemoveDownloadTask) }
        composable(AppRoutes.HISTORY) { HistoryScreen(history, { navController.navigate(AppRoutes.detail(it)) }, onRemoveHistory) }
        composable(AppRoutes.MORE) {
            MoreScreen(preset, themeCatalog.themes.firstOrNull { it.storageKey == themeKey }?.displayName, automaticRefreshEnabled, notificationsAvailable, providers, collections, localPdfImport, metadataBackup,
                { navController.navigateToMoreBranch(AppRoutes.MORE_APPEARANCE) }, { navController.navigateToMoreBranch(AppRoutes.MORE_COLLECTIONS) }, { navController.navigateToMoreBranch(AppRoutes.MORE_READING_IMPORTS) }, { navController.navigateToMoreBranch(AppRoutes.MORE_UPDATES) }, { navController.navigateToMoreBranch(AppRoutes.MORE_DATA_BACKUP) }, { navController.navigateToMoreBranch(AppRoutes.MORE_SOURCES) }, { navController.navigateToMoreBranch(AppRoutes.MORE_ABOUT) })
        }
        composable(AppRoutes.MORE_APPEARANCE) {
            AppearanceScreen(
                selectedThemeKey = themeKey,
                selectedThemeMode = themeMode,
                communityThemes = themeCatalog.themes,
                communityThemesLoading = themeCatalog.loading,
                communityThemeIssues = themeCatalog.issues,
                onThemeChange = onThemeChange,
                onThemeModeChange = onThemeModeChange,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_COLLECTIONS) { CollectionsScreen(collections, onCreateCollection, onRenameCollection, onDeleteCollection, navController::popBackStack) }
        composable(AppRoutes.MORE_READING_IMPORTS) { ReadingImportsScreen(localPdfImport, onRequestLocalPdfImport, onConfirmLocalPdfImport, onDismissLocalPdfImport, { navController.navigate(AppRoutes.detail(it)) }, navController::popBackStack) }
        composable(AppRoutes.MORE_UPDATES) { UpdatesNotificationsScreen(automaticRefreshEnabled, notificationsAvailable, onAutomaticRefreshChange, onOpenNotificationSettings, navController::popBackStack) }
        composable(AppRoutes.MORE_DATA_BACKUP) { DataBackupScreen(metadataBackup, onRequestBackupExport, onRequestBackupImport, onConfirmBackupRestore, onDismissBackupState, navController::popBackStack) }
        composable(AppRoutes.MORE_SOURCES) {
            SourcesScreen(
                providers = providers,
                extensionStores = extensionStores.state,
                extensionStoreAction = extensionStores.action,
                onPreviewStore = extensionStores.onPreview,
                onConfirmStore = extensionStores.onConfirm,
                onDismissStoreAction = extensionStores.onDismissAction,
                onRefreshStore = extensionStores.onRefresh,
                onRemoveStore = extensionStores.onRemove,
                installStates = extensionStores.installStates,
                installedThemeVersions = themeCatalog.installedVersions,
                blockedThemePackages = themeCatalog.issues.mapTo(hashSetOf()) { it.packageName },
                onInstallExtension = extensionStores.onInstallExtension,
                onDismissInstallState = extensionStores.onDismissInstallState,
                onProviderEnabledChange = onProviderEnabledChange,
                onBack = navController::popBackStack,
            )
        }
        composable(AppRoutes.MORE_ABOUT) { AboutScreen(onBack = navController::popBackStack) }
        composable(AppRoutes.DETAIL, arguments = listOf(navArgument("workId") { type = NavType.StringType })) { entry ->
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
                themeKey = themeKey,
                themeMode = themeMode,
                downloadTasks = (tasks as? LoadState.Ready)?.value
                    ?.filter { it.workId?.value == workId }
                    .orEmpty(),
                requestingManifestations = downloadActions.requestingManifestations,
                failedManifestations = downloadActions.failedManifestations,
                onBack = navController::popBackStack,
                onStatusChange = { onReadingStatusChange(workId, it) },
                onRepairSavedPaper = onRepairSavedPaper,
                onRequestDownload = { onRequestDownload(workId, it) },
                onGetDownloadedPaper = onGetDownloadedPaper,
                onLoadReadablePaper = { manifestationId ->
                    onLoadReadablePaper(workId, manifestationId)
                },
                onDeleteDownload = { onDeleteDownload(workId, it) },
                onRemove = { onRemovePaper(workId) },
                onSetCollections = { onSetPaperCollections(workId, it) },
                onRemoved = navController::popBackStack,
            )
        }
    }
}
