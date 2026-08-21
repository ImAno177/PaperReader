package dev.paperreader.app.ui

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.ui.components.PaperSurface
import dev.paperreader.app.ui.theme.PaperReaderTheme
import dev.paperreader.app.ui.theme.PaperThemePreset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickableSurfaceKeepsAMinimumTouchTarget() {
        composeRule.setContent {
            PaperReaderTheme(PaperThemePreset.NEOBRUTALISM) {
                PaperSurface(
                    modifier = Modifier.testTag("clickable-surface"),
                    contentPadding = PaddingValues(0.dp),
                    onClick = {},
                ) {
                    Text("Tap")
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("clickable-surface").getUnclippedBoundsInRoot()
        assertTrue(bounds.height >= 48.dp)
    }
}
