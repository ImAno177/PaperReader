package dev.paperreader.app.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.paperreader.app.importer.IncomingPaperReferenceRequest
import dev.paperreader.app.importer.IncomingPaperReferencePayload
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.identity.PaperReferenceQuery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingPaperReferenceEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigatesThenStartsExactSearchThenConsumesTheRequest() {
        val actions = mutableListOf<String>()
        val request = IncomingPaperReferenceRequest(
            id = 41,
            payload = IncomingPaperReferencePayload.Valid(
                PaperReferenceQuery(
                    identifier = PaperIdentifier(IdentifierType.ARXIV, "2501.04510"),
                    query = "2501.04510v2",
                ),
            ),
        )

        composeRule.setContent {
            IncomingPaperReferenceEffect(
                request = request,
                onNavigateToDiscover = { actions += "navigate" },
                onSearch = { actions += "search:$it" },
                onInvalid = { actions += "invalid" },
                onConsumed = { actions += "consume:$it" },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { actions.size == 3 }
        composeRule.runOnIdle {
            assertEquals(
                listOf("navigate", "search:2501.04510v2", "consume:41"),
                actions,
            )
        }
    }

    @Test
    fun invalidShareShowsFeedbackWithoutStartingANetworkSearch() {
        val actions = mutableListOf<String>()

        composeRule.setContent {
            IncomingPaperReferenceEffect(
                request = IncomingPaperReferenceRequest(
                    id = 42,
                    payload = IncomingPaperReferencePayload.Invalid,
                ),
                onNavigateToDiscover = { actions += "navigate" },
                onSearch = { actions += "search:$it" },
                onInvalid = { actions += "invalid" },
                onConsumed = { actions += "consume:$it" },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { actions.size == 2 }
        composeRule.runOnIdle {
            assertEquals(listOf("invalid", "consume:42"), actions)
        }
    }
}
