package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.provider.OrphanedProviderPlugin
import dev.paperreader.logic.provider.UntrustedProviderPlugin

@Composable
internal fun BlockedProviderRow(
    provider: UntrustedProviderPlugin,
    displayName: String,
) {
    var expanded by rememberSaveable(provider.packageName) { mutableStateOf(false) }
    val detailsDescription = stringResource(
        if (expanded) R.string.hide_provider_details_accessibility else R.string.show_provider_details_accessibility,
        displayName,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = detailsDescription },
            ) {
                PaperIcon(
                    PaperIconKey.FORWARD,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            }
        }
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
    }
}

@Composable
internal fun OrphanedProviderRow(provider: OrphanedProviderPlugin) {
    var expanded by rememberSaveable(provider.packageName) { mutableStateOf(false) }
    val detailsDescription = stringResource(
        if (expanded) R.string.hide_provider_details_accessibility else R.string.show_provider_details_accessibility,
        provider.displayName,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(PaperIconKey.OFFLINE, contentDescription = null, tint = PaperTheme.tokens.ink)
            Text(provider.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = detailsDescription },
            ) {
                PaperIcon(
                    PaperIconKey.FORWARD,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.provider_package, provider.packageName),
                color = PaperTheme.tokens.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.provider_orphaned_body),
                color = PaperTheme.tokens.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ProviderRowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = PaperTheme.tokens.border.copy(alpha = 0.24f),
    )
}
