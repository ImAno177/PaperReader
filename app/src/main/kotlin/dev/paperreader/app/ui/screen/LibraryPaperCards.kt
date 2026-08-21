package dev.paperreader.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.components.PaperMetaRow
import dev.paperreader.app.ui.components.PaperPrimaryButton
import dev.paperreader.app.ui.components.PaperProgress
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.components.StatusBadge
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.displayProviderName
import dev.paperreader.app.ui.model.displayValue
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme
import dev.paperreader.logic.domain.ReadingStatus

@Composable
internal fun LibraryListPaperCard(
    paper: PaperUi,
    onOpen: () -> Unit,
    onRead: () -> Unit,
) {
    PaperSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperDisciplineBadge(paper)
                PaperMetaRow(
                    source = paper.sources.joinToString { it.displayProviderName() }.ifBlank { null },
                    year = paper.publishedDate?.year?.toString(),
                    identifier = paper.primaryIdentifier?.displayValue(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = paper.title,
                style = MaterialTheme.typography.titleLarge,
                color = PaperTheme.tokens.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = paper.authors.joinToString().ifBlank { stringResource(R.string.unknown_authors) },
                color = PaperTheme.tokens.inkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (paper.status != ReadingStatus.UNREAD || paper.progress > 0f || paper.annotationCount > 0) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (paper.status != ReadingStatus.UNREAD || paper.progress > 0f) {
                        LibraryStatusBadge(paper)
                    }
                    if (paper.annotationCount > 0) {
                        LibraryAnnotationBadge(paper.annotationCount)
                    }
                }
                if (paper.progress > 0f) {
                    Spacer(Modifier.height(10.dp))
                    PaperProgress(paper.progress)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LibraryReadButton(onRead)
    }
}

@Composable
internal fun LibraryGridPaperCard(
    paper: PaperUi,
    onOpen: () -> Unit,
    onRead: () -> Unit,
) {
    PaperSurface(contentPadding = PaddingValues(14.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperDisciplineBadge(paper)
                PaperMetaRow(
                    source = paper.sources.firstOrNull()?.displayProviderName(),
                    year = paper.publishedDate?.year?.toString(),
                    identifier = paper.primaryIdentifier?.displayValue(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = compactLibraryGridTitle(paper.title),
                modifier = Modifier.semantics { contentDescription = paper.title },
                style = MaterialTheme.typography.titleLarge,
                color = PaperTheme.tokens.ink,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            LibraryGridStateBadge(paper)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (paper.progress > 0f) PaperProgress(paper.progress)
            }
        }
        Spacer(Modifier.height(8.dp))
        LibraryReadButton(onRead)
    }
}

internal fun compactLibraryGridTitle(title: String): String =
    title.substringBefore(':').trim().ifBlank { title }

@Composable
private fun LibraryGridStateBadge(paper: PaperUi) {
    val status = libraryReadingStatusLabel(paper.status)
    val description = if (paper.annotationCount > 0) {
        stringResource(
            R.string.library_grid_state_description,
            status,
            pluralStringResource(R.plurals.paper_highlight_count, paper.annotationCount, paper.annotationCount),
        )
    } else {
        status
    }
    StatusBadge(
        text = if (paper.annotationCount > 0) {
            stringResource(R.string.library_grid_state_count, status, paper.annotationCount)
        } else {
            status
        },
        color = when (paper.status) {
            ReadingStatus.UNREAD -> PaperTheme.tokens.secondary
            ReadingStatus.READING -> PaperTheme.tokens.primary
            ReadingStatus.FINISHED -> PaperTheme.tokens.success
        },
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    )
}

@Composable
private fun LibraryStatusBadge(paper: PaperUi) {
    StatusBadge(
        text = libraryReadingStatusLabel(paper.status),
        icon = if (paper.status == ReadingStatus.FINISHED) PaperIconKey.DONE else null,
        color = if (paper.status == ReadingStatus.FINISHED) {
            PaperTheme.tokens.success
        } else {
            PaperTheme.tokens.primary
        },
    )
}

@Composable
private fun LibraryAnnotationBadge(count: Int) {
    StatusBadge(
        text = pluralStringResource(R.plurals.paper_highlight_count, count, count),
        color = PaperTheme.tokens.secondary,
    )
}

@Composable
private fun LibraryReadButton(onClick: () -> Unit) {
    PaperPrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.library_read_paper))
    }
}

@Composable
private fun libraryReadingStatusLabel(status: ReadingStatus): String = stringResource(
    when (status) {
        ReadingStatus.UNREAD -> R.string.status_unread
        ReadingStatus.READING -> R.string.status_reading
        ReadingStatus.FINISHED -> R.string.status_finished
    },
)
