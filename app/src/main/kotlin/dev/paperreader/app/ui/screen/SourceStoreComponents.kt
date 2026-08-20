package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.extensions.ExtensionInstallState
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.plugin.ExtensionStoreRecord
import dev.paperreader.logic.plugin.VerifiedExtensionRelease

@Composable
internal fun ExtensionStoreCard(
    store: ExtensionStoreRecord,
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    installStates: Map<String, ExtensionInstallState>,
    blockedPackages: Set<String>,
    installedVersions: Map<String, Long>,
    onInstallExtension: (VerifiedExtensionRelease) -> Unit,
    onDismissInstallState: (String) -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    store.index.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.store_sequence_and_release_count,
                        store.index.sequence,
                        store.index.releases.size,
                    ),
                    color = PaperTheme.tokens.inkMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRefresh, enabled = enabled && !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    PaperIcon(PaperIconKey.SYNC, contentDescription = stringResource(R.string.refresh_store))
                }
            }
            if (store.pinned) {
                StatusBadge(
                    text = stringResource(R.string.store_pinned),
                    color = PaperTheme.tokens.success,
                )
            } else {
                IconButton(onClick = onRemove, enabled = enabled) {
                    PaperIcon(PaperIconKey.DELETE, contentDescription = stringResource(R.string.remove_store))
                }
            }
        }
        if (store.index.releases.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            store.index.releases.forEach { release ->
                ExtensionReleaseRow(
                    release = release,
                    installState = installStates[release.packageName],
                    blocked = release.packageName in blockedPackages,
                    installedVersionCode = installedVersions[release.packageName],
                    onInstallExtension = onInstallExtension,
                    onDismissInstallState = onDismissInstallState,
                )
            }
        }
    }
}

@Composable
private fun ExtensionReleaseRow(
    release: VerifiedExtensionRelease,
    installState: ExtensionInstallState?,
    blocked: Boolean,
    installedVersionCode: Long?,
    onInstallExtension: (VerifiedExtensionRelease) -> Unit,
    onDismissInstallState: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        color = PaperTheme.tokens.canvas,
        shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
        border = BorderStroke(PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp), PaperTheme.tokens.border),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val artifactReady = release.apkSha256 != null && release.apkSizeBytes != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        release.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.extension_version_and_license, release.versionName, release.license),
                        color = PaperTheme.tokens.inkMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusBadge(
                        text = if (release.compatible) {
                            release.kind.name.lowercase().replaceFirstChar(Char::uppercase)
                        } else {
                            stringResource(R.string.extension_incompatible)
                        },
                        color = if (release.compatible) PaperTheme.tokens.success else PaperTheme.tokens.danger,
                    )
                    when {
                        blocked -> StatusBadge(
                            text = stringResource(R.string.provider_blocked),
                            icon = PaperIconKey.ERROR,
                            color = PaperTheme.tokens.danger,
                        )
                        installedVersionCode != null && installedVersionCode >= release.versionCode -> StatusBadge(
                            text = stringResource(R.string.provider_installed_section),
                            color = PaperTheme.tokens.success,
                        )
                    }
                }
            }
            if (!blocked &&
                (installedVersionCode == null || installedVersionCode < release.versionCode) &&
                release.compatible &&
                artifactReady
            ) {
                ExtensionInstallAction(
                    release = release,
                    state = installState,
                    update = installedVersionCode != null,
                    onInstall = onInstallExtension,
                    onDismissState = onDismissInstallState,
                )
            }
        }
    }
}

@Composable
internal fun ExtensionInstallAction(
    release: VerifiedExtensionRelease,
    state: ExtensionInstallState?,
    update: Boolean,
    onInstall: (VerifiedExtensionRelease) -> Unit,
    onDismissState: (String) -> Unit,
) {
    val busy = state is ExtensionInstallState.Pending ||
        state is ExtensionInstallState.Downloading ||
        state is ExtensionInstallState.Installing ||
        state is ExtensionInstallState.AwaitingConfirmation
    val terminal = state is ExtensionInstallState.Installed ||
        state is ExtensionInstallState.Cancelled ||
        state is ExtensionInstallState.Failed
    PaperSecondaryButton(
        onClick = {
            if (busy || terminal) onDismissState(release.packageName) else onInstall(release)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            PaperIcon(
                if (terminal) PaperIconKey.CLOSE else PaperIconKey.DOWNLOAD,
                contentDescription = null,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            when (state) {
                ExtensionInstallState.Pending -> stringResource(R.string.extension_install_pending)
                is ExtensionInstallState.Downloading -> {
                    val percentage = ((state.bytesRead * 100L) / state.totalBytes.coerceAtLeast(1L)).coerceIn(0L, 100L)
                    stringResource(R.string.extension_download_progress, percentage)
                }
                ExtensionInstallState.Installing -> stringResource(R.string.extension_installing)
                ExtensionInstallState.AwaitingConfirmation -> stringResource(R.string.extension_awaiting_confirmation)
                ExtensionInstallState.Installed -> stringResource(R.string.extension_installed)
                ExtensionInstallState.Cancelled -> stringResource(R.string.extension_install_cancelled)
                is ExtensionInstallState.Failed -> stringResource(R.string.dismiss)
                null -> stringResource(if (update) R.string.update_extension else R.string.install_extension)
            },
        )
    }
    if (state is ExtensionInstallState.Failed) {
        Text(
            stringResource(R.string.extension_install_failed, state.message),
            color = PaperTheme.tokens.danger,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
