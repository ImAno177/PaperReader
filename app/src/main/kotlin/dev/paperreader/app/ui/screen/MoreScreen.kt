package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperPreferenceRow
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    selectedPreset: PaperThemePreset,
    selectedThemeName: String? = null,
    automaticRefreshEnabled: Boolean = false,
    notificationsAvailable: Boolean = true,
    providers: ProviderManagerState = ProviderManagerState(),
    collections: LoadState<List<PaperCollectionUi>> = LoadState.Loading,
    localPdfImportState: LocalPdfImportUiState = LocalPdfImportUiState.Idle,
    backupState: MetadataBackupUiState = MetadataBackupUiState.Idle,
    onOpenAppearance: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenReadingImports: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenDataBackup: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDownloadQueue: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    library: LoadState<List<PaperUi>> = LoadState.Loading,
    tasks: LoadState<List<PaperTask>> = LoadState.Loading,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { PaperAppBarTitle(stringResource(R.string.more_title)) },
                colors = topBarColors(),
            )
        },
        containerColor = PaperTheme.tokens.canvas,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "more-hub") {
                PaperSurface(contentPadding = PaddingValues(0.dp)) {
                    PaperPreferenceRow(
                        title = stringResource(R.string.appearance_title),
                        supportingText = selectedThemeName ?: themeName(selectedPreset),
                        icon = PaperIconKey.PALETTE,
                        onClick = onOpenAppearance,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.collections_title),
                        supportingText = collectionsHubSummary(collections),
                        icon = PaperIconKey.FOLDER,
                        onClick = onOpenCollections,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.local_pdf_import_title),
                        supportingText = localPdfHubSummary(localPdfImportState),
                        icon = PaperIconKey.PDF,
                        onClick = onOpenReadingImports,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.updates_and_notifications_title),
                        supportingText = updatesHubSummary(automaticRefreshEnabled, notificationsAvailable),
                        icon = PaperIconKey.NOTIFICATIONS_ON,
                        onClick = onOpenUpdates,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.data_and_backup),
                        supportingText = backupHubSummary(backupState),
                        icon = PaperIconKey.DOWNLOAD,
                        onClick = onOpenDataBackup,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.sources_title),
                        supportingText = sourcesHubSummary(providers),
                        icon = PaperIconKey.PUBLIC,
                        onClick = onOpenSources,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.download_queue_title),
                        supportingText = downloadQueueHubSummary(tasks),
                        icon = PaperIconKey.DOWNLOAD,
                        onClick = onOpenDownloadQueue,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.stats_title),
                        supportingText = statsHubSummary(library),
                        icon = PaperIconKey.UPDATES,
                        onClick = onOpenStats,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.settings_title),
                        supportingText = stringResource(R.string.settings_summary),
                        icon = PaperIconKey.INFO,
                        onClick = onOpenSettings,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.about_title),
                        icon = PaperIconKey.INFO,
                        onClick = onOpenAbout,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.help_title),
                        supportingText = stringResource(R.string.help_summary),
                        icon = PaperIconKey.INFO,
                        onClick = onOpenHelp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreHubDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = PaperTheme.tokens.border.copy(alpha = 0.24f),
    )
}

@Composable
private fun statsHubSummary(library: LoadState<List<PaperUi>>): String? = when (library) {
    LoadState.Loading -> stringResource(R.string.stats_loading)
    LoadState.Failed -> stringResource(R.string.stats_unavailable)
    is LoadState.Ready -> pluralStringResource(R.plurals.saved_papers_count, library.value.size, library.value.size)
}

@Composable
private fun downloadQueueHubSummary(tasks: LoadState<List<PaperTask>>): String? = when (tasks) {
    LoadState.Loading -> stringResource(R.string.download_queue_loading)
    LoadState.Failed -> stringResource(R.string.download_queue_unavailable)
    is LoadState.Ready -> {
        val active = tasks.value.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
        val failed = tasks.value.count { it.state == TaskState.FAILED }
        when {
            active > 0 && failed > 0 -> stringResource(R.string.download_queue_active_and_failed, active, failed)
            active > 0 -> pluralStringResource(R.plurals.download_queue_pending, active, active)
            failed > 0 -> pluralStringResource(R.plurals.download_queue_failed, failed, failed)
            else -> stringResource(R.string.download_queue_empty_title)
        }
    }
}

@Composable
private fun collectionsHubSummary(collections: LoadState<List<PaperCollectionUi>>): String? = when (collections) {
    LoadState.Loading -> stringResource(R.string.collections_loading)
    LoadState.Failed -> stringResource(R.string.collections_load_failed_short)
    is LoadState.Ready -> collections.value.takeIf { it.isNotEmpty() }?.let { values ->
        pluralStringResource(R.plurals.collection_count, values.size, values.size)
    }
}

@Composable
private fun localPdfHubSummary(state: LocalPdfImportUiState): String? {
    val label = when (state) {
        LocalPdfImportUiState.Idle -> null
        LocalPdfImportUiState.Preparing -> R.string.local_pdf_hub_preparing
        is LocalPdfImportUiState.Confirming -> R.string.local_pdf_hub_review
        is LocalPdfImportUiState.Importing -> R.string.local_pdf_hub_importing
        is LocalPdfImportUiState.Complete -> R.string.local_pdf_hub_complete
        is LocalPdfImportUiState.Failed -> R.string.local_pdf_hub_failed
    }
    return if (label == null) null else stringResource(label)
}

@Composable
private fun updatesHubSummary(enabled: Boolean, notificationsAvailable: Boolean): String = stringResource(
    when {
        enabled && !notificationsAvailable -> R.string.updates_hub_on_blocked
        enabled -> R.string.updates_hub_on
        else -> R.string.updates_hub_off
    },
)

@Composable
private fun backupHubSummary(state: MetadataBackupUiState): String? {
    val label = when (state) {
        MetadataBackupUiState.Idle -> null
        MetadataBackupUiState.Exporting -> R.string.creating_metadata_backup
        MetadataBackupUiState.Inspecting -> R.string.inspecting_metadata_backup
        is MetadataBackupUiState.Preview -> R.string.backup_hub_review
        is MetadataBackupUiState.Restoring -> R.string.restoring_backup
        is MetadataBackupUiState.Exported -> R.string.backup_hub_exported
        is MetadataBackupUiState.Restored -> R.string.backup_hub_restored
        is MetadataBackupUiState.Failed -> R.string.backup_hub_failed
    }
    return if (label == null) null else stringResource(label)
}

@Composable
private fun sourcesHubSummary(state: ProviderManagerState): String {
    val reviewCount = state.untrusted.size + state.orphaned.size
    return when {
        reviewCount > 0 -> pluralStringResource(
            R.plurals.provider_review_count,
            reviewCount,
            reviewCount,
        )
        state.available.isNotEmpty() -> pluralStringResource(
            R.plurals.available_provider_count,
            state.available.size,
            state.available.size,
        )
        else -> pluralStringResource(
            R.plurals.installed_provider_count,
            state.installed.size,
            state.installed.size,
        )
    }
}
