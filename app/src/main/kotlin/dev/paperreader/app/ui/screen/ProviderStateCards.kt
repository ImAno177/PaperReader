package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.provider.OrphanedProviderPlugin

@Composable
internal fun OrphanedProviderCard(provider: OrphanedProviderPlugin) {
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(PaperIconKey.OFFLINE, contentDescription = null, tint = PaperTheme.tokens.warning)
            Text(provider.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StatusBadge(stringResource(R.string.provider_orphaned), color = PaperTheme.tokens.warning)
        }
        Text(provider.packageName, color = PaperTheme.tokens.inkMuted)
        Text(stringResource(R.string.provider_orphaned_body), color = PaperTheme.tokens.inkMuted)
    }
}
