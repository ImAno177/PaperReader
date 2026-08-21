package dev.paperreader.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

@Composable
fun UpdatesNotificationsScreen(
    automaticRefreshEnabled: Boolean,
    notificationsAvailable: Boolean,
    onAutomaticRefreshChange: suspend (Boolean) -> Boolean,
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var changing by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MoreBranchScaffold(title = stringResource(R.string.updates_branch_title), onBack = onBack) {
        item {
            PaperSurface {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.automatic_saved_search_refresh),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                if (automaticRefreshEnabled) {
                                    R.string.automatic_saved_search_refresh_on
                                } else {
                                    R.string.automatic_saved_search_refresh_off
                                },
                            ),
                            color = PaperTheme.tokens.inkMuted,
                        )
                    }
                    val label = stringResource(R.string.automatic_saved_search_refresh)
                    val updatingLabel = stringResource(R.string.automatic_saved_search_refresh_updating)
                    if (changing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = updatingLabel },
                            color = PaperTheme.tokens.ink,
                            strokeWidth = 2.dp,
                        )
                    }
                    Switch(
                        checked = automaticRefreshEnabled,
                        enabled = !changing,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                changing = true
                                error = false
                                try {
                                    error = !onAutomaticRefreshChange(enabled)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = true
                                } finally {
                                    changing = false
                                }
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = label
                            if (changing) {
                                stateDescription = updatingLabel
                                liveRegion = LiveRegionMode.Polite
                            }
                        },
                    )
                }
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.automatic_saved_search_refresh_failed),
                        color = PaperTheme.tokens.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (automaticRefreshEnabled && !notificationsAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon(PaperIconKey.NOTIFICATIONS_OFF, contentDescription = null, tint = PaperTheme.tokens.danger)
                        Text(
                            stringResource(R.string.saved_search_notifications_blocked),
                            color = PaperTheme.tokens.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PaperSecondaryButton(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_notification_settings))
                    }
                }
            }
        }
    }
}
