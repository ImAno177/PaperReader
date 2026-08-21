package dev.paperreader.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import dev.paperreader.app.ui.theme.PaperDecoration
import dev.paperreader.app.ui.theme.PaperIcon
import dev.paperreader.app.ui.theme.PaperIconKey
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
fun PaperAppBarTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = PaperTheme.tokens.titleFont,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun PaperPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = PaperTheme.tokens
    PaperButtonFrame(modifier = modifier, enabled = enabled) { buttonModifier, interactionSource ->
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = RoundedCornerShape(tokens.cornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.primary,
                contentColor = tokens.onPrimary,
                disabledContainerColor = tokens.surfaceMuted,
                disabledContentColor = tokens.inkMuted,
            ),
            border = BorderStroke(tokens.borderWidth.coerceAtLeast(1.dp), tokens.border),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
                disabledElevation = 0.dp,
            ),
            interactionSource = interactionSource,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
fun PaperSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = PaperTheme.tokens
    PaperButtonFrame(modifier = modifier, enabled = enabled) { buttonModifier, interactionSource ->
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = RoundedCornerShape(tokens.cornerRadius),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = tokens.surface,
                contentColor = tokens.ink,
                disabledContainerColor = tokens.surfaceMuted,
                disabledContentColor = tokens.inkMuted,
            ),
            border = BorderStroke(tokens.borderWidth.coerceAtLeast(1.dp), tokens.border),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
                disabledElevation = 0.dp,
            ),
            interactionSource = interactionSource,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
private fun PaperButtonFrame(
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable (Modifier, MutableInteractionSource) -> Unit,
) {
    val tokens = PaperTheme.tokens
    val horizontalShadowOffset = tokens.shadowOffset / 2
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedOffsetX by animateDpAsState(
        targetValue = if (enabled && pressed) horizontalShadowOffset else 0.dp,
        animationSpec = tween(durationMillis = if (pressed) 100 else 180),
        label = "paper button horizontal press offset",
    )
    val pressedOffsetY by animateDpAsState(
        targetValue = if (enabled && pressed) tokens.shadowOffset else 0.dp,
        animationSpec = tween(durationMillis = if (pressed) 100 else 180),
        label = "paper button vertical press offset",
    )
    Box(
        modifier = modifier.paperHardShadow(
            color = tokens.hardShadow.copy(alpha = if (enabled) 1f else 0.35f),
            cornerRadius = tokens.cornerRadius,
            horizontalOffset = horizontalShadowOffset,
            verticalOffset = tokens.shadowOffset,
        ),
        propagateMinConstraints = true,
    ) {
        content(
            Modifier
                .heightIn(min = 48.dp)
                .offset(x = pressedOffsetX, y = pressedOffsetY),
            interactionSource,
        )
    }
}

@Composable
fun PaperSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = PaperTheme.tokens
    val shape = RoundedCornerShape(tokens.cornerRadius)
    val horizontalShadowOffset = tokens.shadowOffset / 2
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressedOffsetX by animateDpAsState(
        targetValue = if (onClick != null && pressed) horizontalShadowOffset else 0.dp,
        animationSpec = tween(durationMillis = if (pressed) 100 else 180),
        label = "paper surface horizontal press offset",
    )
    val pressedOffsetY by animateDpAsState(
        targetValue = if (onClick != null && pressed) tokens.shadowOffset else 0.dp,
        animationSpec = tween(durationMillis = if (pressed) 100 else 180),
        label = "paper surface vertical press offset",
    )
    Box(
        modifier = modifier.paperHardShadow(
            color = tokens.hardShadow,
            cornerRadius = tokens.cornerRadius,
            horizontalOffset = horizontalShadowOffset,
            verticalOffset = tokens.shadowOffset,
        ),
    ) {
        val clickModifier = if (onClick == null) {
            Modifier
        } else {
            Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = pressedOffsetX, y = pressedOffsetY)
                .then(clickModifier),
            shape = shape,
            color = tokens.surface,
            contentColor = tokens.ink,
            border = BorderStroke(tokens.borderWidth, tokens.border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content,
            )
        }
    }
}

@Composable
fun PaperLabel(text: String, color: Color = PaperTheme.tokens.inkMuted) {
    Text(
        text = text.uppercase(),
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
            style = MaterialTheme.typography.titleLarge,
            color = PaperTheme.tokens.ink,
        )
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
fun PaperStatePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: PaperIconKey? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentModifier = if (constraints.hasBoundedHeight) {
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
        } else {
            // Lazy containers measure their items with an unbounded height. Adding a
            // second vertical scroller there crashes during measurement; the parent
            // list already owns overflow in that case.
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = contentModifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = PaperTheme.tokens.primary,
                    strokeWidth = 3.dp,
                )
                icon != null -> PaperStateIcon(icon)
            }
            Text(
                text = title,
                modifier = Modifier.widthIn(max = 520.dp),
                style = MaterialTheme.typography.titleLarge,
                color = PaperTheme.tokens.emptyStateAccent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                modifier = Modifier.widthIn(max = 560.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = PaperTheme.tokens.inkMuted,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                PaperPrimaryButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PaperStateIcon(icon: PaperIconKey) {
    val tokens = PaperTheme.tokens
    val shape = RoundedCornerShape(tokens.cornerRadius)
    val tileSize = 48.dp
    Box(
        modifier = Modifier.paperHardShadow(
            color = tokens.hardShadow,
            cornerRadius = tokens.cornerRadius,
            horizontalOffset = tokens.shadowOffset / 2,
            verticalOffset = tokens.shadowOffset,
        ),
    ) {
        Surface(
            modifier = Modifier.size(tileSize),
            shape = shape,
            color = tokens.primary,
            contentColor = tokens.onPrimary,
            border = BorderStroke(tokens.borderWidth.coerceAtLeast(1.dp), tokens.border),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                PaperIcon(
                    key = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = tokens.onPrimary,
                )
            }
        }
    }
}

private fun Modifier.paperHardShadow(
    color: Color,
    cornerRadius: Dp,
    horizontalOffset: Dp,
    verticalOffset: Dp,
): Modifier = drawBehind {
    val offsetX = horizontalOffset.toPx()
    val offsetY = verticalOffset.toPx()
    if (offsetX > 0f || offsetY > 0f) {
        drawRoundRect(
            color = color,
            topLeft = Offset(offsetX, offsetY),
            size = Size(
                width = (size.width - offsetX).coerceAtLeast(0f),
                height = (size.height - offsetY).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
        )
    }
}.padding(end = horizontalOffset, bottom = verticalOffset)

@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: PaperIconKey? = null,
    color: Color = PaperTheme.tokens.primary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PaperTheme.tokens.cornerRadius / 2),
        color = color.copy(alpha = 0.18f),
        contentColor = PaperTheme.tokens.ink,
        border = BorderStroke(
            PaperTheme.tokens.borderWidth.coerceAtLeast(1.dp),
            color.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                PaperIcon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PaperProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaperLabel(label)
                PaperLabel("${(progress * 100).toInt()}%", color = PaperTheme.tokens.primary)
            }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(PaperTheme.tokens.cornerRadius / 2)),
            color = PaperTheme.tokens.primary,
            trackColor = PaperTheme.tokens.surfaceMuted,
        )
    }
}

@Composable
fun StyleDecoration(
    modifier: Modifier = Modifier,
    color: Color = PaperTheme.tokens.primary,
    height: Dp = 9.dp,
) {
    when (PaperTheme.tokens.decoration) {
        PaperDecoration.NONE -> Spacer(modifier.height(0.dp))
        PaperDecoration.DOODLE -> Canvas(modifier.fillMaxWidth().height(height)) {
            val y = size.height * 0.56f
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(4f, y),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.32f, y - 6f),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.32f, y - 6f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.62f, y + 5f),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width * 0.62f, y + 5f),
                end = androidx.compose.ui.geometry.Offset(size.width - 4f, y - 1f),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color.copy(alpha = 0.35f),
                radius = 5f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.86f, (y - 7f).coerceAtLeast(5f)),
            )
        }
        PaperDecoration.RETRO_GRID -> Canvas(modifier.fillMaxWidth().height(height)) {
            val spacing = 18f
            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = color.copy(alpha = 0.20f),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = 1f,
                )
                x += spacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = color.copy(alpha = 0.20f),
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += spacing
            }
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width - 38f, 4f),
                end = androidx.compose.ui.geometry.Offset(size.width - 38f, size.height - 4f),
                strokeWidth = 3f,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(size.width - 25f, 4f),
                end = androidx.compose.ui.geometry.Offset(size.width - 25f, size.height - 4f),
                strokeWidth = 3f,
                cap = StrokeCap.Square,
            )
        }
    }
}
