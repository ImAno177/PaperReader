package dev.paperreader.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.paperreader.app.R
import dev.paperreader.app.ui.ProviderFailureKind
import dev.paperreader.app.ui.ProviderSearchFailure
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperSecondaryButton
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.model.SearchPaperUi
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
internal fun ProviderFailureRow(
    failure: ProviderSearchFailure,
    onRetry: (() -> Unit)?,
) {
    PaperSurface(contentPadding = PaddingValues(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperIcon(PaperIconKey.ERROR, contentDescription = null, tint = PaperTheme.tokens.warning)
            Text(
                text = when (failure.kind) {
                    ProviderFailureKind.RATE_LIMITED -> failure.retryAfterMillis?.let { retryAfterMillis ->
                        stringResource(
                            R.string.provider_rate_limited_retry,
                            failure.providerName,
                            ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L),
                        )
                    } ?: stringResource(R.string.provider_rate_limited, failure.providerName)
                    ProviderFailureKind.UNAVAILABLE -> stringResource(R.string.provider_unavailable, failure.providerName)
                    ProviderFailureKind.INVALID_RESPONSE -> stringResource(R.string.provider_invalid_response, failure.providerName)
                },
                modifier = Modifier.weight(1f),
                color = PaperTheme.tokens.ink,
            )
            onRetry?.let { retry ->
                PaperSecondaryButton(onClick = retry) { Text(stringResource(R.string.search_retry)) }
            }
        }
    }
}

@Composable
internal fun SearchResultCard(
    result: SearchPaperUi,
    saving: Boolean,
    savedWorkId: String?,
    saveFailed: Boolean,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val previewDescription = stringResource(R.string.search_preview_open, result.title)
    PaperSurface {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPreview).semantics {
                contentDescription = previewDescription
            },
        ) {
            PaperMetaRow(
                source = result.sourceDisplayNames.joinToString(),
                year = result.publishedDate?.year?.toString(),
                identifier = result.primaryIdentifier?.displayValue(),
            )
            Spacer(Modifier.height(8.dp))
            Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                result.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
                color = PaperTheme.tokens.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            result.abstractText?.let { abstractText ->
                Spacer(Modifier.height(10.dp))
                Text(abstractText, maxLines = 4, overflow = TextOverflow.Ellipsis, color = PaperTheme.tokens.inkMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (savedWorkId == null) {
            PaperPrimaryButton(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = PaperTheme.tokens.ink,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(if (saving) R.string.saving_paper else R.string.save_paper))
            }
        } else {
            PaperSecondaryButton(onClick = { onOpen(savedWorkId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_saved_paper))
            }
        }
        if (saveFailed) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.save_failed), color = PaperTheme.tokens.danger)
        }
    }
}
