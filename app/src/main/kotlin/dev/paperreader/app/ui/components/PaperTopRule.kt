package dev.paperreader.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
fun Modifier.paperTopRule(): Modifier {
    val color = PaperTheme.tokens.border
    val width = PaperTheme.tokens.borderWidth
    return drawWithContent {
        drawContent()
        val stroke = width.toPx()
        drawRect(color, size = Size(size.width, stroke))
    }
}
