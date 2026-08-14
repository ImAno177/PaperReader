package dev.paperreader.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.discipline
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
internal fun PaperDisciplineBadge(paper: PaperUi, modifier: Modifier = Modifier) {
    val discipline = paper.discipline()
    val description = stringResource(R.string.paper_discipline_badge, discipline.name.replace('_', ' ').lowercase())
    Surface(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            contentDescription = description
        },
        shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
        color = PaperTheme.tokens.secondaryContainer,
        contentColor = PaperTheme.tokens.onSecondaryContainer,
        border = BorderStroke(PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp), PaperTheme.tokens.border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(discipline.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
