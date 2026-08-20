package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.extensions.ExtensionInstallState
import dev.paperreader.app.ui.ExtensionStoreActionUiState
import dev.paperreader.app.ui.ExtensionStoreOperation
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSectionHeader
import dev.paperreader.app.ui.components.PaperStatePanel
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.logic.provider.ProviderManagerState
import dev.paperreader.logic.provider.ProviderOrigin
import dev.paperreader.logic.plugin.ExtensionStoreRegistryState
import dev.paperreader.logic.plugin.VerifiedExtensionRelease

@Composable
fun SourcesScreen(
    providers: ProviderManagerState,
    extensionStores: ExtensionStoreRegistryState = ExtensionStoreRegistryState(),
    extensionStoreAction: ExtensionStoreActionUiState = ExtensionStoreActionUiState.Idle,
    onPreviewStore: (String, String) -> Unit = { _, _ -> },
    onConfirmStore: () -> Unit = {},
    onDismissStoreAction: () -> Unit = {},
    onRefreshStore: (String) -> Unit = {},
    onRemoveStore: (String) -> Unit = {},
    installStates: Map<String, ExtensionInstallState> = emptyMap(),
    installedThemeVersions: Map<String, Long> = emptyMap(),
    blockedThemePackages: Set<String> = emptySet(),
    onInstallExtension: (VerifiedExtensionRelease) -> Unit = {},
    onDismissInstallState: (String) -> Unit = {},
    onProviderEnabledChange: (String, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    var addStoreOpen by rememberSaveable { mutableStateOf(false) }
    var storeUrl by rememberSaveable { mutableStateOf("") }
    var publicKey by rememberSaveable { mutableStateOf("") }
    var pendingRemovalStoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedUntrustedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val storeBusy = extensionStoreAction is ExtensionStoreActionUiState.Working
    val releasesByPackage = extensionStores.stores
        .flatMap { it.index.releases }
        .associateBy(VerifiedExtensionRelease::packageName)
    val blockedPackages = providers.untrusted.filterNot { it.updateCanRemediate }
        .mapTo(blockedThemePackages.toHashSet()) { it.packageName }
    val installedVersions = providers.available
        .mapNotNull { available ->
            available.installedVersionCode?.let { available.packageName to it }
        }
        .toMap() + providers.installed
        .mapNotNull { installed ->
            installed.packageName?.let { packageName ->
                installed.versionCode?.let { versionCode -> packageName to versionCode }
            }
        }
        .toMap() + installedThemeVersions
    LaunchedEffect(extensionStoreAction) {
        if (extensionStoreAction is ExtensionStoreActionUiState.PreviewReady) addStoreOpen = false
    }
    MoreBranchScaffold(title = stringResource(R.string.sources_title), onBack = onBack) {
        item {
            PaperSurface(contentPadding = PaddingValues(12.dp)) {
                Text(stringResource(R.string.extension_stores_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.extension_stores_body),
                    color = PaperTheme.tokens.inkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                PaperPrimaryButton(
                    onClick = { addStoreOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !storeBusy,
                ) {
                    PaperIcon(PaperIconKey.ADD, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_store))
                }
            }
        }
        if (extensionStores.issues.isNotEmpty()) {
            items(extensionStores.issues, key = { "store-issue:${it.storeId}:${it.message}" }) { issue ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    StatusBadge(
                        text = stringResource(R.string.store_verification_issue),
                        icon = PaperIconKey.ERROR,
                        color = PaperTheme.tokens.danger,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(issue.message, color = PaperTheme.tokens.inkMuted)
                }
            }
        }
        items(extensionStores.stores, key = { "store:${it.index.storeId}" }) { store ->
            ExtensionStoreCard(
                store = store,
                refreshing = store.index.storeId in extensionStores.refreshingStoreIds,
                enabled = !storeBusy,
                onRefresh = { onRefreshStore(store.index.storeId) },
                onRemove = { pendingRemovalStoreId = store.index.storeId },
                installStates = installStates,
                blockedPackages = blockedPackages,
                installedVersions = installedVersions,
                onInstallExtension = onInstallExtension,
                onDismissInstallState = onDismissInstallState,
            )
        }
        if (providers.installed.isEmpty() && providers.available.isEmpty() && providers.untrusted.isEmpty() && providers.orphaned.isEmpty()) {
            item { PaperStatePanel(stringResource(R.string.no_providers), stringResource(R.string.sources_empty_body)) }
        }
        if (providers.untrusted.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_security_attention)) }
            items(providers.untrusted, key = { "untrusted:${it.packageName}" }) { provider ->
                val displayName = releasesByPackage[provider.packageName]?.displayName
                    ?: provider.packageName.substringAfterLast('.').replace('-', ' ')
                        .replaceFirstChar(Char::uppercase)
                val expanded = expandedUntrustedPackage == provider.packageName
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.danger)
                        Text(
                            displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusBadge(
                            text = stringResource(R.string.provider_blocked),
                            color = PaperTheme.tokens.danger,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.provider_blocked_body), color = PaperTheme.tokens.inkMuted)
                    if (expanded) {
                        Text(
                            stringResource(R.string.provider_package, provider.packageName),
                            color = PaperTheme.tokens.inkMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.provider_block_reason, provider.reason.take(240)),
                            color = PaperTheme.tokens.inkMuted,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.provider_signer,
                                provider.signerSha256.take(64).chunked(8).joinToString(" "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    TextButton(
                        onClick = {
                            expandedUntrustedPackage = if (expanded) null else provider.packageName
                        },
                    ) {
                        Text(stringResource(if (expanded) R.string.hide_details else R.string.show_details))
                    }
                }
            }
        }
        if (providers.orphaned.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_orphaned_section)) }
            items(providers.orphaned, key = { "orphaned:${it.packageName}" }) { provider ->
                OrphanedProviderCard(provider)
            }
        }
        if (providers.installed.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_installed_section)) }
            items(providers.installed, key = { it.descriptor.id }) { provider ->
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.descriptor.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Switch(
                            checked = provider.descriptor.id !in providers.disabledProviderIds,
                            onCheckedChange = { onProviderEnabledChange(provider.descriptor.id, it) },
                            modifier = Modifier.semantics {
                                contentDescription = provider.descriptor.displayName
                            },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (provider.origin == ProviderOrigin.BUILT_IN) {
                            stringResource(R.string.provider_builtin)
                        } else {
                            provider.packageName.orEmpty()
                        },
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(
                        stringResource(
                            R.string.provider_interval,
                            "${provider.descriptor.minimumRequestIntervalMillis} ms",
                        ),
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
        if (providers.available.isNotEmpty()) {
            item { PaperSectionHeader(stringResource(R.string.provider_available_section)) }
            item {
                Text(
                    stringResource(R.string.provider_available_notice),
                    color = PaperTheme.tokens.inkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(providers.available, key = { "available:${it.packageName}" }) { provider ->
                val installedVersion = provider.installedVersionCode
                PaperSurface(contentPadding = PaddingValues(12.dp)) {
                    Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.provider_package, provider.packageName),
                        color = PaperTheme.tokens.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.provider_version_code, provider.versionCode),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    if (installedVersion != null) {
                        StatusBadge(
                            text = stringResource(R.string.provider_update_available),
                            icon = PaperIconKey.UPDATES,
                            color = PaperTheme.tokens.warning,
                        )
                        Text(
                            stringResource(
                                R.string.provider_version_transition,
                                installedVersion,
                                provider.versionCode,
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    Text(
                        pluralStringResource(
                            R.plurals.provider_id_count,
                            provider.providerIds.size,
                            provider.providerIds.size,
                        ),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    releasesByPackage[provider.packageName]?.let { release ->
                        Spacer(Modifier.height(8.dp))
                        val artifactReady = release.apkSha256 != null && release.apkSizeBytes != null
                        if (provider.packageName !in blockedPackages && release.compatible && artifactReady) {
                            ExtensionInstallAction(
                                release = release,
                                state = installStates[provider.packageName],
                                update = installedVersion != null,
                                onInstall = onInstallExtension,
                                onDismissState = onDismissInstallState,
                            )
                        }
                    }
                }
            }
        }
    }
    if (addStoreOpen) {
        AlertDialog(
            onDismissRequest = { if (!storeBusy) addStoreOpen = false },
            title = { Text(stringResource(R.string.add_store)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.add_store_security_notice), color = PaperTheme.tokens.inkMuted)
                    OutlinedTextField(
                        value = storeUrl,
                        onValueChange = { storeUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.store_index_url)) },
                        placeholder = { Text("https://example.org/paperreader-index.json") },
                        singleLine = true,
                        enabled = !storeBusy,
                    )
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { publicKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.store_public_key)) },
                        supportingText = { Text(stringResource(R.string.store_public_key_hint)) },
                        singleLine = true,
                        enabled = !storeBusy,
                    )
                    if (extensionStoreAction is ExtensionStoreActionUiState.Failed &&
                        extensionStoreAction.operation == ExtensionStoreOperation.PREVIEW
                    ) {
                        Text(extensionStoreAction.message, color = PaperTheme.tokens.danger)
                    }
                }
            },
            dismissButton = {
                PaperSecondaryButton(
                    onClick = {
                        addStoreOpen = false
                        onDismissStoreAction()
                    },
                    enabled = !storeBusy,
                ) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                PaperPrimaryButton(
                    onClick = { onPreviewStore(storeUrl.trim(), publicKey.trim()) },
                    enabled = !storeBusy && storeUrl.isNotBlank() && publicKey.isNotBlank(),
                ) {
                    if (storeBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.verify_store))
                }
            },
        )
    }
    val preview = (extensionStoreAction as? ExtensionStoreActionUiState.PreviewReady)?.preview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = onDismissStoreAction,
            title = { Text(stringResource(R.string.confirm_store_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.index.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(preview.indexUrl, color = PaperTheme.tokens.inkMuted)
                    Text(
                        stringResource(R.string.store_sequence, preview.index.sequence),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(
                        stringResource(R.string.store_release_count, preview.index.releases.size),
                        color = PaperTheme.tokens.inkMuted,
                    )
                    Text(stringResource(R.string.store_key_fingerprint_label), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(
                            preview.index.publicKeySha256.chunked(8).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(stringResource(R.string.confirm_store_security_warning), color = PaperTheme.tokens.danger)
                }
            },
            dismissButton = {
                PaperSecondaryButton(onClick = onDismissStoreAction) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = onConfirmStore) { Text(stringResource(R.string.trust_and_add_store)) }
            },
        )
    }
    val removalStore = extensionStores.stores.firstOrNull { it.index.storeId == pendingRemovalStoreId }
    if (removalStore != null) {
        AlertDialog(
            onDismissRequest = { pendingRemovalStoreId = null },
            title = { Text(stringResource(R.string.remove_store_title)) },
            text = { Text(stringResource(R.string.remove_store_body, removalStore.index.displayName)) },
            dismissButton = {
                PaperSecondaryButton(onClick = { pendingRemovalStoreId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                PaperPrimaryButton(onClick = {
                    pendingRemovalStoreId = null
                    onRemoveStore(removalStore.index.storeId)
                }) { Text(stringResource(R.string.remove)) }
            },
        )
    }
    val failedAction = extensionStoreAction as? ExtensionStoreActionUiState.Failed
    if (failedAction != null && failedAction.operation != ExtensionStoreOperation.PREVIEW) {
        AlertDialog(
            onDismissRequest = onDismissStoreAction,
            title = { Text(stringResource(R.string.store_operation_failed)) },
            text = { Text(failedAction.message) },
            confirmButton = {
                PaperPrimaryButton(onClick = onDismissStoreAction) { Text(stringResource(R.string.dismiss)) }
            },
        )
    }
}
