package dev.paperreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.paperreader.app.ui.model.*
import dev.paperreader.app.ui.screen.*
import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.domain.repository.*
import dev.paperreader.logic.task.*

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
    NavHost(navController = navController, startDestination = AppRoutes.LIBRARY, modifier = modifier) {
        composable(AppRoutes.LIBRARY) { LibraryScreen(library, collections, libraryLayout, onLibraryLayoutChange, { navController.navigate(AppRoutes.detail(it)) }, { navController.navigate(AppRoutes.DISCOVER) }) }
        composable(AppRoutes.DISCOVER) { DiscoverScreen(search, onSearch, onClearSearch, onSave, { navController.navigate(AppRoutes.detail(it)) }, savedSearches, savedSearchActions, onSaveSearch, { navController.navigate(AppRoutes.UPDATES) { launchSingleTop = true } }) }
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
            DetailScreen(detail, collections, preset, themeKey, themeMode, (tasks as? LoadState.Ready)?.value?.filter { it.workId?.value == workId }.orEmpty(), downloadActions.requestingManifestations, downloadActions.failedManifestations, navController::popBackStack, { onReadingStatusChange(workId, it) }, onRepairSavedPaper, { onRequestDownload(workId, it) }, onGetDownloadedPaper, { onDeleteDownload(workId, it) }, { onRemovePaper(workId) }, { onSetPaperCollections(workId, it) }, navController::popBackStack)
        }
    }
}
