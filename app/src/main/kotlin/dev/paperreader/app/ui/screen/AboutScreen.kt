package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.paperreader.app.BuildConfig
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme

private const val PROJECT_URL = "https://github.com/ImAno177/PaperReader"
private const val NOTICES_URL = "$PROJECT_URL/blob/main/THIRD_PARTY_NOTICES.md"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    MoreBranchScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        item {
            PaperSurface {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_description), color = PaperTheme.tokens.inkMuted)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelLarge,
                        color = PaperTheme.tokens.inkMuted,
                    )
                }
            }
        }
        item {
            PaperSecondaryButton(
                onClick = { uriHandler.openUri(PROJECT_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaperIcon(PaperIconKey.OPEN_EXTERNAL, contentDescription = null)
                Text(stringResource(R.string.about_project), modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            PaperSecondaryButton(
                onClick = { uriHandler.openUri(NOTICES_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                PaperIcon(PaperIconKey.INFO, contentDescription = null)
                Text(stringResource(R.string.about_licenses), modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Text(stringResource(R.string.about_inspiration), color = PaperTheme.tokens.inkMuted)
        }
    }
}
