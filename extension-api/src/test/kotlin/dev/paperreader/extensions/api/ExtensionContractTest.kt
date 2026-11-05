package dev.paperreader.extensions.api

import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionContractTest {
    @Test
    fun `source request enforces the IPC query budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceSearchRequest(
                requestId = "request-1",
                query = "x".repeat(PaperExtensionContract.MAX_QUERY_CHARACTERS + 1),
                limit = 20,
            )
        }
    }

    @Test
    fun `source record rejects unsafe document URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceManifestation(
                type = "preprint",
                pdfUrl = "file:///private/paper.pdf",
            )
        }
    }

    @Test
    fun `source record rejects non canonical missing identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourcePaperRecord(providerRecordId = "record", title = "Paper", doi = "null")
        }
    }

    @Test
    fun `community theme requires a complete semantic icon set`() {
        assertThrows(IllegalArgumentException::class.java) {
            theme(iconKeys = ThemeSemanticIcon.entries.dropLast(1).toSet())
        }
    }

    private fun theme(iconKeys: Set<ThemeSemanticIcon>) = CommunityTheme(
        requestId = "request-1",
        themeId = "sample.theme",
        displayName = "Sample",
        lightPalette = palette(),
        darkPalette = palette(),
        cornerRadiusDp = 4f,
        borderWidthDp = 1f,
        shadowOffsetDp = 0f,
        titleFont = ThemeFontFamily.SYSTEM_SERIF,
        bodyFont = ThemeFontFamily.SYSTEM_SANS,
        labelFont = ThemeFontFamily.SYSTEM_MONOSPACE,
        decoration = ThemeDecoration.NONE,
        iconKeys = iconKeys,
    )

    private fun palette() = ThemePalette(
        canvas = OPAQUE,
        surface = OPAQUE,
        surfaceMuted = OPAQUE,
        ink = OPAQUE,
        inkMuted = OPAQUE,
        border = OPAQUE,
        primary = OPAQUE,
        onPrimary = OPAQUE,
        primaryContainer = OPAQUE,
        onPrimaryContainer = OPAQUE,
        secondary = OPAQUE,
        onSecondary = OPAQUE,
        secondaryContainer = OPAQUE,
        onSecondaryContainer = OPAQUE,
        success = OPAQUE,
        warning = OPAQUE,
        danger = OPAQUE,
        emptyStateAccent = OPAQUE,
        selection = OPAQUE,
        hardShadow = OPAQUE,
    )

    private companion object {
        const val OPAQUE: Int = -0x1
    }
}
