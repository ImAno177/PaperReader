package dev.paperreader.app.ui.screen

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.paperreader.app.R
import dev.paperreader.app.ui.model.PaperDiscipline
import dev.paperreader.app.ui.model.PaperUi
import dev.paperreader.app.ui.model.discipline
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
internal fun PaperDisciplineBadge(
    paper: PaperUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val discipline = paper.discipline()
    val description = if (discipline == PaperDiscipline.GENERAL) {
        stringResource(R.string.paper_discipline_general)
    } else {
        stringResource(R.string.paper_discipline_badge, stringResource(discipline.nameRes))
    }
    val size = if (compact) 32.dp else 40.dp
    Surface(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius),
        color = PaperTheme.tokens.secondaryContainer,
        contentColor = PaperTheme.tokens.onSecondaryContainer,
        border = BorderStroke(PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp), PaperTheme.tokens.border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(discipline.iconRes),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
            )
        }
    }
}

private val PaperDiscipline.iconRes: Int
    @DrawableRes get() = when (this) {
        PaperDiscipline.COMPUTER_SCIENCE -> R.drawable.ic_material_symbol_discipline_computer_science
        PaperDiscipline.MATHEMATICS -> R.drawable.ic_material_symbol_discipline_mathematics
        PaperDiscipline.PHYSICS -> R.drawable.ic_material_symbol_discipline_physics
        PaperDiscipline.LIFE_SCIENCES -> R.drawable.ic_material_symbol_discipline_life_sciences
        PaperDiscipline.MEDICINE -> R.drawable.ic_material_symbol_discipline_medicine
        PaperDiscipline.SOCIAL_SCIENCES -> R.drawable.ic_material_symbol_discipline_social_sciences
        PaperDiscipline.ECONOMICS -> R.drawable.ic_material_symbol_discipline_economics
        PaperDiscipline.ENGINEERING -> R.drawable.ic_material_symbol_discipline_engineering
        PaperDiscipline.GENERAL -> R.drawable.ic_material_symbol_discipline_general
    }

private val PaperDiscipline.nameRes: Int
    @StringRes get() = when (this) {
        PaperDiscipline.COMPUTER_SCIENCE -> R.string.paper_discipline_computer_science
        PaperDiscipline.MATHEMATICS -> R.string.paper_discipline_mathematics
        PaperDiscipline.PHYSICS -> R.string.paper_discipline_physics
        PaperDiscipline.LIFE_SCIENCES -> R.string.paper_discipline_life_sciences
        PaperDiscipline.MEDICINE -> R.string.paper_discipline_medicine
        PaperDiscipline.SOCIAL_SCIENCES -> R.string.paper_discipline_social_sciences
        PaperDiscipline.ECONOMICS -> R.string.paper_discipline_economics
        PaperDiscipline.ENGINEERING -> R.string.paper_discipline_engineering
        PaperDiscipline.GENERAL -> R.string.paper_discipline_general
    }
