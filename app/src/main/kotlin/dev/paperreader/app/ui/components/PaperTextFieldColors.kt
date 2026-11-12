package dev.paperreader.app.ui.components

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import dev.paperreader.app.ui.theme.PaperTheme

/**
 * Text fields sit on the card surface with an ink outline, so an input never blends into the
 * canvas behind it and its edge stays visible before focus.
 */
@Composable
fun paperTextFieldColors(): TextFieldColors {
    val tokens = PaperTheme.tokens
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = tokens.surface,
        unfocusedContainerColor = tokens.surface,
        disabledContainerColor = tokens.surfaceMuted,
        errorContainerColor = tokens.surface,
        focusedBorderColor = tokens.ink,
        unfocusedBorderColor = tokens.border,
        focusedTextColor = tokens.ink,
        unfocusedTextColor = tokens.ink,
        focusedLabelColor = tokens.ink,
        unfocusedLabelColor = tokens.inkMuted,
        focusedPlaceholderColor = tokens.inkMuted,
        unfocusedPlaceholderColor = tokens.inkMuted,
        cursorColor = tokens.ink,
        focusedLeadingIconColor = tokens.ink,
        unfocusedLeadingIconColor = tokens.inkMuted,
        focusedTrailingIconColor = tokens.ink,
        unfocusedTrailingIconColor = tokens.inkMuted,
    )
}
