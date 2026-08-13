package dev.paperreader.app.ui.screen

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import dev.paperreader.app.ui.theme.PaperTheme

@Composable
internal fun topBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = PaperTheme.tokens.canvas,
    titleContentColor = PaperTheme.tokens.ink,
    actionIconContentColor = PaperTheme.tokens.ink,
)
