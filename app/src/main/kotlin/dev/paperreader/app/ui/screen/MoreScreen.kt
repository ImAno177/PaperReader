package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.LoadState
import dev.paperreader.app.ui.components.PaperAppBarTitle
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.PaperCollectionUi
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperThemePreset
import dev.paperreader.logic.provider.ProviderManagerState

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.more_overview_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = PaperTheme.tokens.inkMuted,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.appearance_title),
                    supportingText = selectedThemeName ?: themeName(selectedPreset),
                    icon = PaperIconKey.PALETTE,
                    onClick = onOpenAppearance,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.collections_title),
                    supportingText = collectionsHubSummary(collections),
                    icon = PaperIconKey.FOLDER,
                    onClick = onOpenCollections,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.reading_and_imports_title),
                    supportingText = localPdfHubSummary(localPdfImportState),
                    icon = PaperIconKey.PDF,
                    onClick = onOpenReadingImports,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.updates_and_notifications_title),
                    supportingText = updatesHubSummary(automaticRefreshEnabled, notificationsAvailable),
                    icon = PaperIconKey.NOTIFICATIONS_ON,
                    onClick = onOpenUpdates,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.data_and_backup),
                    supportingText = backupHubSummary(backupState),
                    icon = PaperIconKey.DOWNLOAD,
                    onClick = onOpenDataBackup,
                )
            }
            item {
                MoreHubRow(
                    title = stringResource(R.string.sources_title),
                    supportingText = sourcesHubSummary(providers),
                    icon = PaperIconKey.PUBLIC,
                    onClick = onOpenSources,
                )
            }
        }
    }
}

@Composable
private fun MoreHubRow(
    title: String,
    supportingText: String,
    icon: PaperIconKey,
    onClick: () -> Unit,
) {
    PaperSurface(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
                color = PaperTheme.tokens.primaryContainer,
                contentColor = PaperTheme.tokens.onPrimaryContainer,
                border = BorderStroke(1.dp, PaperTheme.tokens.border),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    PaperIcon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperTheme.tokens.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PaperIcon(
                PaperIconKey.FORWARD,
                contentDescription = null,
                tint = PaperTheme.tokens.inkMuted,
            )
        }
    }
}

@Composable
private fun collectionsHubSummary(collections: LoadState<List<PaperCollectionUi>>): String = when (collections) {
    LoadState.Loading -> stringResource(R.string.collections_loading)
    LoadState.Failed -> stringResource(R.string.collections_load_failed_short)
    is LoadState.Ready -> pluralStringResource(
        R.plurals.collection_count,
        collections.value.size,
        collections.value.size,
    )
}

@Composable
private fun localPdfHubSummary(state: LocalPdfImportUiState): String = stringResource(
    when (state) {
        LocalPdfImportUiState.Idle -> R.string.reading_and_imports_summary
        LocalPdfImportUiState.Preparing -> R.string.local_pdf_hub_preparing
        is LocalPdfImportUiState.Confirming -> R.string.local_pdf_hub_review
        is LocalPdfImportUiState.Importing -> R.string.local_pdf_hub_importing
        is LocalPdfImportUiState.Complete -> R.string.local_pdf_hub_complete
        is LocalPdfImportUiState.Failed -> R.string.local_pdf_hub_failed
    },
)

@Composable
private fun updatesHubSummary(enabled: Boolean, notificationsAvailable: Boolean): String = stringResource(
    when {
        enabled && !notificationsAvailable -> R.string.updates_hub_on_blocked
        enabled -> R.string.updates_hub_on
        else -> R.string.updates_hub_off
    },
)

@Composable
private fun backupHubSummary(state: MetadataBackupUiState): String = stringResource(
    when (state) {
        MetadataBackupUiState.Idle -> R.string.data_backup_summary
        MetadataBackupUiState.Exporting -> R.string.creating_metadata_backup
        MetadataBackupUiState.Inspecting -> R.string.inspecting_metadata_backup
        is MetadataBackupUiState.Preview -> R.string.backup_hub_review
        is MetadataBackupUiState.Restoring -> R.string.restoring_backup
        is MetadataBackupUiState.Exported -> R.string.backup_hub_exported
        is MetadataBackupUiState.Restored -> R.string.backup_hub_restored
        is MetadataBackupUiState.Failed -> R.string.backup_hub_failed
    },
)

@Composable
private fun sourcesHubSummary(state: ProviderManagerState): String = when {
    state.untrusted.isNotEmpty() -> pluralStringResource(
        R.plurals.untrusted_provider_count,
        state.untrusted.size,
        state.untrusted.size,
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
