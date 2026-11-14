package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperPreferenceRow
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.state.LoadState
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperThemePreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    state: MoreUiState,
    selectedPreset: PaperThemePreset,
    selectedThemeName: String? = null,
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
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "more-personalize-section") {
                MoreSectionHeader(stringResource(R.string.more_personalize_section))
            }
            item(key = "more-personalize") {
                MorePreferenceGroup {
                    PaperPreferenceRow(
                        title = stringResource(R.string.appearance_title),
                        supportingText = selectedThemeName ?: themeName(selectedPreset),
                        onClick = onOpenAppearance,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.collections_title),
                        supportingText = collectionsHubSummaryCount(state.collectionCount),
                        onClick = onOpenCollections,
                    )
                }
            }
            item(key = "more-reading-section") {
                MoreSectionHeader(stringResource(R.string.more_reading_section))
            }
            item(key = "more-reading") {
                MorePreferenceGroup {
                    PaperPreferenceRow(
                        title = stringResource(R.string.local_pdf_import_title),
                        supportingText = localPdfHubSummary(state.localPdfImportState),
                        onClick = onOpenReadingImports,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.download_queue_title),
                        supportingText = downloadQueueHubSummaryCounts(
                            state.activeDownloadCount,
                            state.failedDownloadCount,
                        ),
                        onClick = onOpenDownloadQueue,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.stats_title),
                        supportingText = statsHubSummaryCount(state.savedPaperCount),
                        onClick = onOpenStats,
                    )
                }
            }
            item(key = "more-data-section") {
                MoreSectionHeader(stringResource(R.string.more_data_section))
            }
            item(key = "more-data") {
                MorePreferenceGroup {
                    PaperPreferenceRow(
                        title = stringResource(R.string.updates_and_notifications_title),
                        supportingText = updatesHubSummary(state.automaticRefreshEnabled, state.notificationsAvailable),
                        onClick = onOpenUpdates,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.data_and_backup),
                        supportingText = backupHubSummary(state.backupState),
                        onClick = onOpenDataBackup,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.sources_title),
                        supportingText = sourcesHubSummaryCounts(
                            state.providerReviewCount,
                            state.availableProviderCount,
                            state.installedProviderCount,
                        ),
                        onClick = onOpenSources,
                    )
                }
            }
            item(key = "more-about-section") {
                MoreSectionHeader(stringResource(R.string.more_about_section))
            }
            item(key = "more-about") {
                MorePreferenceGroup {
                    PaperPreferenceRow(
                        title = stringResource(R.string.settings_title),
                        onClick = onOpenSettings,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.about_title),
                        onClick = onOpenAbout,
                    )
                    MoreHubDivider()
                    PaperPreferenceRow(
                        title = stringResource(R.string.help_title),
                        onClick = onOpenHelp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = PaperTheme.tokens.ink,
    )
}

@Composable
private fun MorePreferenceGroup(content: @Composable () -> Unit) {
    PaperSurface(contentPadding = PaddingValues(0.dp)) { content() }
}

@Composable
private fun MoreHubDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = PaperTheme.tokens.border.copy(alpha = 0.24f),
    )
}

@Composable
private fun statsHubSummaryCount(library: LoadState<Int>): String? = when (library) {
    LoadState.Loading -> stringResource(R.string.stats_loading)
    LoadState.Failed -> stringResource(R.string.stats_unavailable)
    is LoadState.Ready -> pluralStringResource(R.plurals.saved_papers_count, library.value, library.value)
}

@Composable
private fun downloadQueueHubSummaryCounts(
    active: LoadState<Int>,
    failed: LoadState<Int>,
): String? {
    if (active is LoadState.Loading || failed is LoadState.Loading) {
        return stringResource(R.string.download_queue_loading)
    }
    if (active is LoadState.Failed || failed is LoadState.Failed) {
        return stringResource(R.string.download_queue_unavailable)
    }
    val activeCount = (active as LoadState.Ready).value
    val failedCount = (failed as LoadState.Ready).value
    return when {
        activeCount > 0 && failedCount > 0 -> stringResource(
            R.string.download_queue_active_and_failed,
            activeCount,
            failedCount,
        )
        activeCount > 0 -> pluralStringResource(R.plurals.download_queue_pending, activeCount, activeCount)
        failedCount > 0 -> pluralStringResource(R.plurals.download_queue_failed, failedCount, failedCount)
        else -> stringResource(R.string.download_queue_empty_title)
    }
}

@Composable
private fun collectionsHubSummaryCount(collections: LoadState<Int>): String? = when (collections) {
    LoadState.Loading -> stringResource(R.string.collections_loading)
    LoadState.Failed -> stringResource(R.string.collections_load_failed_short)
    is LoadState.Ready -> collections.value.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.collection_count, it, it)
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
private fun sourcesHubSummaryCounts(reviewCount: Int, availableCount: Int, installedCount: Int): String {
    return when {
        reviewCount > 0 -> pluralStringResource(R.plurals.provider_review_count, reviewCount, reviewCount)
        availableCount > 0 -> pluralStringResource(R.plurals.available_provider_count, availableCount, availableCount)
        else -> pluralStringResource(R.plurals.installed_provider_count, installedCount, installedCount)
    }
}
