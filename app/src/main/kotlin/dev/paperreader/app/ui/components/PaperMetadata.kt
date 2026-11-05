package dev.paperreader.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
fun PaperLabel(text: String, color: Color = PaperTheme.tokens.inkMuted) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PaperSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = PaperTheme.tokens.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) Spacer(Modifier.width(8.dp))
        action?.invoke()
    }
}

@Composable
fun PaperMetaRow(
    source: String?,
    year: String?,
    identifier: String?,
    modifier: Modifier = Modifier,
) {
    val metadata = listOfNotNull(
        source?.takeIf(String::isNotBlank),
        year?.takeIf(String::isNotBlank),
        identifier?.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    Text(
        text = metadata,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        color = PaperTheme.tokens.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PaperIdentityBlock(
    source: String?,
    year: String?,
    identifier: String?,
    title: String,
    authors: String,
    doi: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PaperMetaRow(source = source, year = year, identifier = identifier)
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = PaperTheme.tokens.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = authors,
            style = MaterialTheme.typography.bodyLarge,
            color = PaperTheme.tokens.inkMuted,
        )
        doi?.takeIf(String::isNotBlank)?.let { value ->
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.doi_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = PaperTheme.tokens.inkMuted,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = PaperTheme.tokens.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
