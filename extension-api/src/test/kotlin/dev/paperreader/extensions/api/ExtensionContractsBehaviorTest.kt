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
        assertEquals(4, descriptor.capabilities.size)
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
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", apiVersion = 1) }
        assertIllegal { SourceExtensionDescriptor("dev.example", "ok.provider", "Name", apiVersion = 3) }
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
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", setOf("theme"), apiVersion = 1) }
        assertIllegal { ThemeExtensionDescriptor("dev.example", "Name", setOf("theme"), apiVersion = 3) }
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
        for (markup in listOf("<svg>", "<svg onload='bad'>")) {
            assertIllegal { requireValidIconPathData(markup.encodeToByteArray()) }
        }
        assertIllegal { requireValidIconPathData(ByteArray(PaperExtensionContract.MAX_ICON_BYTES + 1) { 'M'.code.toByte() }) }
    }

    @Test
    fun `readable request enforces identifiers and version syntax`() {
        val boundary = SourceGetReadableDocumentRequest(
            requestId = "r".repeat(PaperExtensionContract.MAX_REQUEST_ID_CHARACTERS),
            providerRecordId = "p".repeat(256),
            version = "v9",
        )
        assertEquals(256, boundary.providerRecordId.length)

        assertIllegal { SourceGetReadableDocumentRequest(" ", "record", "v1") }
        assertIllegal { SourceGetReadableDocumentRequest("req", " ", "v1") }
        assertIllegal { SourceGetReadableDocumentRequest("req", "p".repeat(257), "v1") }
        for (version in listOf("v0", "v01", "V1", "1")) {
            assertIllegal { SourceGetReadableDocumentRequest("req", "record", version) }
        }
    }

    @Test
    fun `readable sections enforce anchor title and hierarchy bounds`() {
        val boundary = SourceReadableSection(
            anchor = "a".repeat(160),
            title = "T".repeat(180),
            level = 3,
        )
        assertEquals(160, boundary.anchor.length)
        assertEquals(180, boundary.title.length)
        assertEquals(1, SourceReadableSection("a", "Section", 1).anchor.length)

        assertIllegal { SourceReadableSection("", "Section", 1) }
        assertIllegal { SourceReadableSection("bad/anchor", "Section", 1) }
        assertIllegal { SourceReadableSection("a".repeat(161), "Section", 1) }
        assertIllegal { SourceReadableSection("section", " ", 1) }
        assertIllegal { SourceReadableSection("section", "T".repeat(181), 1) }
        assertIllegal { SourceReadableSection("section", "Section", 0) }
        assertIllegal { SourceReadableSection("section", "Section", 4) }
    }

    @Test
    fun `readable assets enforce canonical ids safe URLs and media types`() {
        val id = "a".repeat(64)
        for (mediaType in listOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml")) {
            assertEquals(
                mediaType,
                SourceReadableAsset(id, "https://example.org/figure", mediaType).mediaType,
            )
        }

        assertIllegal { SourceReadableAsset("a".repeat(63), "https://example.org/figure", "image/png") }
        assertIllegal { SourceReadableAsset("A".repeat(64), "https://example.org/figure", "image/png") }
        assertIllegal { SourceReadableAsset(id, "file:///private/figure.png", "image/png") }
        assertIllegal { SourceReadableAsset(id, "https://example.org/figure", "application/pdf") }
    }

    @Test
    fun `readable metadata accepts its declared field boundaries`() {
        val metadata = readableMetadata(
            requestId = "r".repeat(PaperExtensionContract.MAX_REQUEST_ID_CHARACTERS),
            title = "T".repeat(PaperExtensionContract.MAX_TITLE_CHARACTERS),
            sourceVersion = "v123",
            license = "L".repeat(160),
            sections = listOf(readableSection("a"), readableSection("b")),
            warnings = setOf(SourceReadableWarning.FIGURE_UNAVAILABLE),
            assets = listOf(readableAsset()),
            contractVersion = "a" + "z".repeat(63),
        )

        assertEquals(PaperExtensionContract.MAX_TITLE_CHARACTERS, metadata.title.length)
        assertEquals(160, metadata.license?.length)
        assertEquals(64, metadata.contractVersion.length)
    }

    @Test
    fun `readable metadata rejects unsafe provenance malformed hashes and duplicate identities`() {
        assertIllegal { readableMetadata(title = " ") }
        assertIllegal { readableMetadata(title = "T".repeat(PaperExtensionContract.MAX_TITLE_CHARACTERS + 1)) }
        assertIllegal { readableMetadata(contractVersion = "UPPERCASE") }
        assertIllegal { readableMetadata(contractVersion = "a".repeat(65)) }
        assertIllegal { readableMetadata(sourceUrl = "file:///private/paper.html") }
        assertIllegal { readableMetadata(sourceVersion = "v0") }
        assertIllegal { readableMetadata(license = "L".repeat(161)) }
        assertIllegal { readableMetadata(sourceSha256 = "a".repeat(63)) }
        assertIllegal { readableMetadata(documentSha256 = "A".repeat(64)) }
        assertIllegal {
            readableMetadata(sections = listOf(readableSection("duplicate"), readableSection("duplicate")))
        }
        assertIllegal {
            readableMetadata(assets = listOf(readableAsset("a".repeat(64)), readableAsset("a".repeat(64))))
        }
    }

    @Test
    fun `readable chunks enforce sequence and byte boundaries`() {
        val oneByte = SourceReadableDocumentChunk("request", 0, byteArrayOf(1))
        assertEquals(0, oneByte.sequence)

        val maximum = SourceReadableDocumentChunk(
            "r".repeat(PaperExtensionContract.MAX_REQUEST_ID_CHARACTERS),
            Int.MAX_VALUE,
            ByteArray(PaperExtensionContract.MAX_READABLE_DOCUMENT_CHUNK_BYTES),
        )
        assertEquals(PaperExtensionContract.MAX_READABLE_DOCUMENT_CHUNK_BYTES, maximum.bytes.size)

        assertIllegal { SourceReadableDocumentChunk(" ", 0, byteArrayOf(1)) }
        assertIllegal { SourceReadableDocumentChunk("request", -1, byteArrayOf(1)) }
        assertIllegal { SourceReadableDocumentChunk("request", 0, byteArrayOf()) }
        assertIllegal {
            SourceReadableDocumentChunk(
                "request",
                0,
                ByteArray(PaperExtensionContract.MAX_READABLE_DOCUMENT_CHUNK_BYTES + 1),
            )
        }
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

    private fun readableSection(anchor: String, title: String = "Section") =
        SourceReadableSection(anchor = anchor, title = title, level = 1)

    private fun readableAsset(id: String = "a".repeat(64)) =
        SourceReadableAsset(id = id, sourceUrl = "https://example.org/figure", mediaType = "image/png")

    private fun readableMetadata(
        requestId: String = "readable:1",
        title: String = "Readable paper",
        sourceUrl: String = "https://example.org/paper",
        sourceVersion: String = "v1",
        license: String? = null,
        sourceSha256: String = "a".repeat(64),
        documentSha256: String = "b".repeat(64),
        sections: List<SourceReadableSection> = emptyList(),
        warnings: Set<SourceReadableWarning> = emptySet(),
        assets: List<SourceReadableAsset> = emptyList(),
        contractVersion: String = LEGACY_READABLE_CONTRACT_VERSION,
    ) = SourceReadableDocumentMetadata(
        requestId = requestId,
        title = title,
        sourceUrl = sourceUrl,
        sourceVersion = sourceVersion,
        license = license,
        sourceSha256 = sourceSha256,
        documentSha256 = documentSha256,
        sections = sections,
        warnings = warnings,
        assets = assets,
        contractVersion = contractVersion,
    )

    private companion object {
        const val OPAQUE: Int = -0x1
    }
}
