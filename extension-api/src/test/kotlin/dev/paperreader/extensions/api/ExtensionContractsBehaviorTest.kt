package dev.paperreader.extensions.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionContractsBehaviorTest {
    @Test
    fun `source contracts accept valid public values`() {
        val descriptor = SourceExtensionDescriptor(
            packageName = "dev.example.source",
            providerId = "example.source",
            displayName = "Example source",
            minimumRequestIntervalMillis = 2_000,
            capabilities = SourceCapability.entries.toSet(),
            roles = SourceRole.entries.toSet(),
            identifierLookupTypes = SourceIdentifierType.entries.toSet(),
            supportedSorts = SourceSearchSort.entries.toSet(),
        )
        assertEquals("dev.example.source", descriptor.packageName)
        assertEquals("example.source", descriptor.providerId)
        assertEquals(3, descriptor.capabilities.size)
        assertEquals(3, descriptor.supportedSorts.size)

        val request = SourceSearchRequest("req-1", "quantum", 10, "next", SourceSearchSort.NEWEST)
        assertEquals(SourceSearchSort.NEWEST, request.sort)
        assertEquals(SourceGetPaperRequest("req-2", "record-1").providerRecordId, "record-1")
        val manifestation = SourceManifestation(
            type = "accepted_manuscript",
            version = "v2",
            landingPageUrl = "https://example.org/paper",
            pdfUrl = "http://example.org/paper.pdf",
            license = "CC-BY",
            publishedDate = "2026-01-02",
        )
        val record = SourcePaperRecord(
            providerRecordId = "record-1",
            title = "A paper",
            abstractText = "Abstract",
            authors = listOf("Alice", "Bob"),
            subjects = setOf("physics", "math"),
            doi = "10.1234/example",
            arxivId = "2501.12345v2",
            pmid = "123456",
            pmcid = "PMC123456",
            citationCount = 7,
            publishedDate = "2026-01-02",
            updatedAt = "2026-01-03T00:00:00Z",
            manifestations = listOf(manifestation),
        )
        assertEquals(1, record.manifestations.size)
        assertEquals(1, SourceSearchPage("req-page", listOf(record), "cursor").records.size)
        assertEquals(record, SourcePaperResponse("req-response", record).record)
        assertEquals(null, SourcePaperResponse("req-empty", null).record)
    }

    @Test
    fun `source constructors reject invalid values`() {
        assertIllegal { SourceExtensionDescriptor("invalid", "ok.provider", "Name") }
        assertIllegal { SourceExtensionDescriptor("dev." + "x".repeat(252), "ok.provider", "Name") }
        assertIllegal { SourceExtensionDescriptor("dev.example", "Bad", "Name") }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", " ") }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "x".repeat(81)) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", apiVersion = 2) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", minimumRequestIntervalMillis = -1) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", minimumRequestIntervalMillis = 86_400_001) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", capabilities = emptySet()) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", roles = emptySet()) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", supportedSorts = emptySet()) }
        assertIllegal { SourceSearchRequest("", "q", 1) }
        assertIllegal { SourceSearchRequest("req!", "q", 1) }
        assertIllegal { SourceSearchRequest("x".repeat(PaperExtensionContract.MAX_REQUEST_ID_CHARACTERS + 1), "q", 1) }
        assertIllegal { SourceSearchRequest("req", " ", 1) }
        assertIllegal { SourceSearchRequest("req", "x".repeat(PaperExtensionContract.MAX_QUERY_CHARACTERS + 1), 1) }
        assertIllegal { SourceSearchRequest("req", "q", 0) }
        assertIllegal { SourceSearchRequest("req", "q", PaperExtensionContract.MAX_RESULTS_PER_PAGE + 1) }
        assertIllegal { SourceSearchRequest("req", "q", 1, "x".repeat(513)) }
        assertIllegal { SourceGetPaperRequest("req", "") }
        assertIllegal { SourceGetPaperRequest("req", "x".repeat(257)) }
        assertIllegal { SourceManifestation("bad") }
        assertIllegal { SourceManifestation("preprint", version = "x".repeat(65)) }
        assertIllegal { SourceManifestation("preprint", license = "x".repeat(513)) }
        assertIllegal { SourceManifestation("preprint", publishedDate = "2026-1-1") }
        assertIllegal { SourcePaperRecord("", "Paper") }
        assertIllegal { SourcePaperRecord("x".repeat(257), "Paper") }
        assertIllegal { SourcePaperRecord("record", "") }
        assertIllegal { SourcePaperRecord("record", "x".repeat(PaperExtensionContract.MAX_TITLE_CHARACTERS + 1)) }
        assertIllegal { SourcePaperRecord("record", "Paper", abstractText = "x".repeat(PaperExtensionContract.MAX_ABSTRACT_CHARACTERS + 1)) }
        assertIllegal { SourcePaperRecord("record", "Paper", authors = List(PaperExtensionContract.MAX_AUTHORS + 1) { "Author $it" }) }
        assertIllegal { SourcePaperRecord("record", "Paper", authors = listOf(" ")) }
        assertIllegal { SourcePaperRecord("record", "Paper", authors = listOf("x".repeat(257))) }
        assertIllegal { SourcePaperRecord("record", "Paper", subjects = (0..PaperExtensionContract.MAX_SUBJECTS).mapTo(linkedSetOf()) { "subject-$it" }) }
        assertIllegal { SourcePaperRecord("record", "Paper", subjects = setOf(" ")) }
        assertIllegal { SourcePaperRecord("record", "Paper", subjects = setOf("x".repeat(257))) }
        assertIllegal { SourcePaperRecord("record", "Paper", doi = "null") }
        assertIllegal { SourcePaperRecord("record", "Paper", arxivId = "bad") }
        assertIllegal { SourcePaperRecord("record", "Paper", pmid = "0") }
        assertIllegal { SourcePaperRecord("record", "Paper", pmcid = "PMC0") }
        assertIllegal { SourcePaperRecord("record", "Paper", citationCount = -1) }
        assertIllegal { SourcePaperRecord("record", "Paper", publishedDate = "2026-1-1") }
        assertIllegal { SourcePaperRecord("record", "Paper", updatedAt = "x".repeat(65)) }
        assertIllegal { SourcePaperRecord("record", "Paper", manifestations = List(21) { SourceManifestation("preprint") }) }
        assertIllegal { SourceSearchPage("", emptyList()) }
        assertIllegal { SourceSearchPage("req", List(PaperExtensionContract.MAX_RESULTS_PER_PAGE + 1) { SourcePaperRecord("record-$it", "Paper") }) }
        assertIllegal { SourceSearchPage("req", emptyList(), "x".repeat(PaperExtensionContract.MAX_CURSOR_CHARACTERS + 1)) }
        assertIllegal { SourcePaperResponse("", null) }
    }

    @Test
    fun `theme contracts accept valid values`() {
        val descriptor = ThemeExtensionDescriptor("dev.example.theme", "Example", setOf("example.theme"))
        assertEquals("Example", descriptor.displayName)
        assertEquals(setOf("example.theme"), descriptor.themeIds)
        val theme = validTheme()
        assertEquals(ThemeDecoration.NONE, theme.decoration)
        assertEquals(ThemeFontFamily.SYSTEM_SANS, theme.bodyFont)
        assertEquals(ThemeSemanticIcon.entries.toSet(), theme.iconKeys)
    }

    @Test
    fun `theme constructors reject invalid values`() {
        assertIllegal { ThemeExtensionDescriptor("invalid", "Name", setOf("theme")) }
        assertIllegal { ThemeExtensionDescriptor("dev." + "x".repeat(252), "Name", setOf("theme")) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", " ", setOf("theme")) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "x".repeat(81), setOf("theme")) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", emptySet()) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", (0..20).mapTo(linkedSetOf()) { "theme-$it" }) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", setOf("Bad")) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", setOf("theme"), apiVersion = 2) }
        assertIllegal { palette(0) }
        assertIllegal { CommunityTheme("", "theme", "Name", palette(), palette(), 0f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "Bad", "Name", palette(), palette(), 0f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", " ", palette(), palette(), 0f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "x".repeat(81), palette(), palette(), 0f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), -1f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 33f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 0f, -1f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 0f, 5f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 0f, 0f, -1f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 0f, 0f, 9f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.toSet()) }
        assertIllegal { CommunityTheme("req", "theme", "Name", palette(), palette(), 0f, 0f, 0f, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeFontFamily.SYSTEM_SANS, ThemeDecoration.NONE, ThemeSemanticIcon.entries.drop(1).toSet()) }
    }

    @Test
    fun `extension failures and pure validators enforce public bounds`() {
        val failure = ExtensionFailure("req-1", ExtensionFailureCode.RATE_LIMITED, "Try later", 5_000)
        assertEquals(ExtensionFailureCode.RATE_LIMITED, failure.code)
        assertEquals(ExtensionFailureCode.INVALID_RESPONSE, ExtensionFailureCode.fromWireValue(999))
        assertIllegal { ExtensionFailure("", ExtensionFailureCode.INTERNAL_ERROR, "message") }
        assertIllegal { ExtensionFailure("req", ExtensionFailureCode.INTERNAL_ERROR, " ") }
        assertIllegal { ExtensionFailure("req", ExtensionFailureCode.INTERNAL_ERROR, "x".repeat(513)) }
        assertIllegal { ExtensionFailure("req", ExtensionFailureCode.INTERNAL_ERROR, "message", -1) }

        ExtensionPayloadValidator.requireSafeWebUrl(null)
        ExtensionPayloadValidator.requireSafeWebUrl("https://example.org/path")
        ExtensionPayloadValidator.requireSafeWebUrl("HTTP://example.org")
        for (url in listOf("file:///tmp/a", "https:///missing-host", "//example.org/path", "not a url", "ftp://example.org")) {
            assertIllegal { ExtensionPayloadValidator.requireSafeWebUrl(url) }
        }
        assertIllegal { ExtensionPayloadValidator.requireSafeWebUrl("https://example.org/%") }
        assertIllegal { ExtensionPayloadValidator.requireSafeWebUrl("https://example.org/" + "x".repeat(2_049)) }

        assertEquals("M0 0L24 24Z", requireValidIconPathData("M0 0L24 24Z".encodeToByteArray()))
        assertIllegal { requireValidIconPathData(byteArrayOf()) }
        assertIllegal { requireValidIconPathData("   \n\t".encodeToByteArray()) }
        assertIllegal { requireValidIconPathData("<svg>".encodeToByteArray()) }
        assertIllegal { requireValidIconPathData(ByteArray(PaperExtensionContract.MAX_ICON_BYTES + 1) { 'M'.code.toByte() }) }
    }

    private fun validTheme() = CommunityTheme(
        requestId = "req",
        themeId = "theme",
        displayName = "Name",
        lightPalette = palette(),
        darkPalette = palette(),
        cornerRadiusDp = 0f,
        borderWidthDp = 0f,
        shadowOffsetDp = 0f,
        titleFont = ThemeFontFamily.SYSTEM_SANS,
        bodyFont = ThemeFontFamily.SYSTEM_SANS,
        labelFont = ThemeFontFamily.SYSTEM_SANS,
        decoration = ThemeDecoration.NONE,
        iconKeys = ThemeSemanticIcon.entries.toSet(),
    )

    private fun palette(color: Int = OPAQUE) = ThemePalette(
        canvas = color,
        surface = color,
        surfaceMuted = color,
        ink = color,
        inkMuted = color,
        border = color,
        primary = color,
        onPrimary = color,
        primaryContainer = color,
        onPrimaryContainer = color,
        secondary = color,
        onSecondary = color,
        secondaryContainer = color,
        onSecondaryContainer = color,
        success = color,
        warning = color,
        danger = color,
        emptyStateAccent = color,
        selection = color,
        hardShadow = color,
    )

    private fun assertIllegal(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private companion object {
        const val OPAQUE: Int = -0x1
    }
}
