package dev.paperreader.extensions.api

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionBundleContractTest {
    @Test
    fun sourceDescriptorRoundTripsEveryDeclaredCapability() {
        val descriptor = SourceExtensionDescriptor(
            packageName = "dev.example.source",
            providerId = "example.source",
            displayName = "Example source",
            minimumRequestIntervalMillis = 2_500,
            capabilities = SourceCapability.entries.toSet(),
            roles = SourceRole.entries.toSet(),
            identifierLookupTypes = SourceIdentifierType.entries.toSet(),
            supportedSorts = SourceSearchSort.entries.toSet(),
        )

        assertEquals(descriptor, SourceExtensionDescriptor.fromBundle(descriptor.toBundle()))
    }

    @Test
    fun sourceDescriptorUsesCompatibleDefaultsForOlderBundles() {
        val bundle = SourceExtensionDescriptor(
            packageName = "dev.example.source",
            providerId = "example.source",
            displayName = "Example source",
        ).toBundle().apply {
            remove("roles")
            remove("identifier_types")
            remove("supported_sorts")
        }

        val descriptor = SourceExtensionDescriptor.fromBundle(bundle)

        assertEquals(setOf(SourceRole.CONTENT_SOURCE), descriptor.roles)
        assertEquals(SourceIdentifierType.entries.toSet(), descriptor.identifierLookupTypes)
        assertEquals(SourceSearchSort.entries.toSet(), descriptor.supportedSorts)
    }

    @Test
    fun sourceDescriptorRejectsUnknownWireValuesAndMissingFields() {
        val valid = SourceExtensionDescriptor(
            packageName = "dev.example.source",
            providerId = "example.source",
            displayName = "Example source",
        ).toBundle()

        for ((key, value) in listOf(
            "capabilities" to "unknown_capability",
            "roles" to "unknown_role",
            "identifier_types" to "unknown_identifier",
            "supported_sorts" to "unknown_sort",
        )) {
            expectIllegal {
                SourceExtensionDescriptor.fromBundle(Bundle(valid).apply {
                    putStringArrayList(key, arrayListOf(value))
                })
            }
        }
        expectIllegal {
            SourceExtensionDescriptor.fromBundle(Bundle(valid).apply { remove("package_name") })
        }
        expectIllegal {
            SourceExtensionDescriptor.fromBundle(Bundle(valid).apply { remove("api_version") })
        }
        expectIllegal {
            SourceExtensionDescriptor.fromBundle(Bundle(valid).apply { putInt("api_version", 1) })
        }
        expectIllegal {
            SourceExtensionDescriptor.fromBundle(Bundle(valid).apply { remove("capabilities") })
        }
    }

    @Test
    fun sourceRequestsRoundTripAndUnknownSortFallsBackToRelevance() {
        val search = SourceSearchRequest(
            requestId = "search:1",
            query = "mobile reading",
            limit = 25,
            cursor = "next-page",
            sort = SourceSearchSort.OLDEST,
        )
        assertEquals(search, SourceSearchRequest.fromBundle(search.toBundle()))

        val unknownSort = search.toBundle().apply { putString("sort", "unknown") }
        assertEquals(SourceSearchSort.RELEVANCE, SourceSearchRequest.fromBundle(unknownSort).sort)

        val defaults = SourceSearchRequest("search:defaults", "mobile", 1)
        assertNull(defaults.cursor)
        assertEquals(SourceSearchSort.RELEVANCE, defaults.sort)

        val details = SourceGetPaperRequest("details:1", "record-1")
        assertEquals(details, SourceGetPaperRequest.fromBundle(details.toBundle()))
        expectIllegal {
            SourceGetPaperRequest.fromBundle(details.toBundle().apply { remove("record_id") })
        }
    }

    @Test
    fun sourceRecordAndManifestationRoundTripAllOptionalFields() {
        val manifestation = SourceManifestation(
            type = "version_of_record",
            version = "v3",
            landingPageUrl = "https://example.org/paper",
            pdfUrl = "https://example.org/paper.pdf",
            license = "CC-BY-4.0",
            publishedDate = "2026-08-14",
        )
        assertEquals(manifestation, SourceManifestation.fromBundle(manifestation.toBundle()))

        val record = fullRecord(manifestation)
        assertEquals(record, SourcePaperRecord.fromBundle(record.toBundle()))

        val minimal = SourcePaperRecord("minimal", "Minimal paper")
        val decoded = SourcePaperRecord.fromBundle(minimal.toBundle())
        assertEquals(minimal, decoded)
        assertNull(decoded.citationCount)

        val legacyRecord = minimal.toBundle().apply { remove("manifestations") }
        assertEquals(emptyList<SourceManifestation>(), SourcePaperRecord.fromBundle(legacyRecord).manifestations)

        val sparseRecord = minimal.toBundle().apply {
            remove("authors")
            remove("subjects")
        }
        assertEquals(emptyList<String>(), SourcePaperRecord.fromBundle(sparseRecord).authors)
        assertEquals(emptySet<String>(), SourcePaperRecord.fromBundle(sparseRecord).subjects)
    }

    @Test
    fun sourceReadableRequestSectionsAndAssetsRoundTripAndRejectMalformedFields() {
        val request = SourceGetReadableDocumentRequest(
            requestId = "readable:1",
            providerRecordId = "record-1",
            version = "v2",
        )
        assertEquals(request, SourceGetReadableDocumentRequest.fromBundle(request.toBundle()))
        expectIllegal {
            SourceGetReadableDocumentRequest.fromBundle(request.toBundle().apply {
                putString(Keys.READABLE_VERSION, "latest")
            })
        }
        expectIllegal {
            SourceGetReadableDocumentRequest.fromBundle(request.toBundle().apply {
                remove(Keys.READABLE_PROVIDER_RECORD_ID)
            })
        }

        val section = SourceReadableSection("intro:1", "Introduction", 1)
        assertEquals(section, SourceReadableSection.fromBundle(section.toBundle()))
        expectIllegal {
            SourceReadableSection.fromBundle(section.toBundle().apply {
                putInt(Keys.READABLE_SECTION_LEVEL, 4)
            })
        }
        expectIllegal {
            SourceReadableSection.fromBundle(section.toBundle().apply {
                remove(Keys.READABLE_TITLE)
            })
        }

        val asset = SourceReadableAsset(
            id = "a".repeat(64),
            sourceUrl = "https://example.org/figure.png",
            mediaType = "image/png",
        )
        assertEquals(asset, SourceReadableAsset.fromBundle(asset.toBundle()))
        expectIllegal {
            SourceReadableAsset.fromBundle(asset.toBundle().apply {
                putString(Keys.READABLE_ASSET_MEDIA_TYPE, "application/octet-stream")
            })
        }
        expectIllegal {
            SourceReadableAsset.fromBundle(asset.toBundle().apply {
                remove(Keys.READABLE_ASSET_URL)
            })
        }
    }

    @Test
    fun sourceReadableMetadataRoundTripsWarningsSectionsAndAssets() {
        val metadata = readableMetadata(
            sections = listOf(
                SourceReadableSection("intro", "Introduction", 1),
                SourceReadableSection("methods", "Methods", 2),
            ),
            warnings = SourceReadableWarning.entries.toSet(),
            assets = listOf(
                SourceReadableAsset("a".repeat(64), "https://example.org/a.png", "image/png"),
                SourceReadableAsset("b".repeat(64), "https://example.org/b.svg", "image/svg+xml"),
            ),
        ).copy(contractVersion = "html-v1")

        assertEquals(metadata, SourceReadableDocumentMetadata.fromBundle(metadata.toBundle()))
    }

    @Test
    fun sourceReadableMetadataAcceptsSparseLegacyBundlesAndRejectsUnknownWarnings() {
        val sparse = readableMetadata(license = null).toBundle().apply {
            remove(Keys.READABLE_CONTRACT_VERSION)
            remove(Keys.LICENSE)
            remove(Keys.READABLE_SECTIONS)
            remove(Keys.READABLE_WARNINGS)
            remove(Keys.READABLE_ASSETS)
        }

        val decoded = SourceReadableDocumentMetadata.fromBundle(sparse)
        assertEquals(LEGACY_READABLE_CONTRACT_VERSION, decoded.contractVersion)
        assertNull(decoded.license)
        assertEquals(emptyList<SourceReadableSection>(), decoded.sections)
        assertEquals(emptySet<SourceReadableWarning>(), decoded.warnings)
        assertEquals(emptyList<SourceReadableAsset>(), decoded.assets)

        expectIllegal {
            SourceReadableDocumentMetadata.fromBundle(readableMetadata().toBundle().apply {
                putStringArrayList(Keys.READABLE_WARNINGS, arrayListOf("unknown_warning"))
            })
        }
        expectIllegal {
            SourceReadableDocumentMetadata.fromBundle(readableMetadata().toBundle().apply {
                remove(Keys.READABLE_DOCUMENT_SHA256)
            })
        }
    }

    @Test
    fun sourceReadableChunkRoundTripsBytesAndRejectsMissingOrMalformedFields() {
        val bytes = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
        val chunk = SourceReadableDocumentChunk("readable:chunk", 3, bytes)
        val decoded = SourceReadableDocumentChunk.fromBundle(chunk.toBundle())

        assertEquals(chunk.requestId, decoded.requestId)
        assertEquals(chunk.sequence, decoded.sequence)
        assertArrayEquals(bytes, decoded.bytes)

        expectIllegal {
            SourceReadableDocumentChunk.fromBundle(chunk.toBundle().apply {
                remove(Keys.READABLE_CHUNK_SEQUENCE)
            })
        }
        expectIllegal {
            SourceReadableDocumentChunk.fromBundle(chunk.toBundle().apply {
                remove(Keys.READABLE_CHUNK_BYTES)
            })
        }
        expectIllegal {
            SourceReadableDocumentChunk.fromBundle(chunk.toBundle().apply {
                putInt(Keys.READABLE_CHUNK_SEQUENCE, -1)
            })
        }
    }

    @Test
    fun sourcePagesAndResponsesRoundTripWithinBinderLimit() {
        val record = fullRecord(SourceManifestation("preprint"))
        val page = SourceSearchPage("page:1", listOf(record), "next")
        assertEquals(page, SourceSearchPage.fromBundle(page.toBundle()))

        val response = SourcePaperResponse("paper:1", record)
        assertEquals(response, SourcePaperResponse.fromBundle(response.toBundle()))
        val empty = SourcePaperResponse("paper:2", null)
        assertEquals(empty, SourcePaperResponse.fromBundle(empty.toBundle()))

        val legacyPage = page.toBundle().apply { remove("results") }
        assertEquals(emptyList<SourcePaperRecord>(), SourceSearchPage.fromBundle(legacyPage).records)
    }

    @Test
    fun sourcePageRejectsAnAggregatePayloadThatExceedsBinderLimit() {
        val largeRecord = SourcePaperRecord(
            providerRecordId = "large",
            title = "Large paper",
            abstractText = "x".repeat(PaperExtensionContract.MAX_ABSTRACT_CHARACTERS),
        )
        val page = SourceSearchPage(
            requestId = "large-page",
            records = List(PaperExtensionContract.MAX_RESULTS_PER_PAGE) { index ->
                largeRecord.copy(providerRecordId = "large-$index")
            },
        )

        expectIllegal(page::toBundle)
    }

    @Test
    fun extensionFailureRoundTripsPresentAndAbsentRetryDelay() {
        val retryable = ExtensionFailure("failure:1", ExtensionFailureCode.RATE_LIMITED, "Try later", 5_000)
        assertEquals(retryable, ExtensionFailure.fromBundle(retryable.toBundle()))

        val terminal = ExtensionFailure("failure:2", ExtensionFailureCode.CANCELLED, "Cancelled")
        assertEquals(terminal, ExtensionFailure.fromBundle(terminal.toBundle()))
        assertNull(ExtensionFailure.fromBundle(terminal.toBundle()).retryAfterMillis)

        val unknownCode = terminal.toBundle().apply { putInt("failure_code", Int.MAX_VALUE) }
        assertEquals(ExtensionFailureCode.INVALID_RESPONSE, ExtensionFailure.fromBundle(unknownCode).code)
    }

    @Test
    fun binderValidatorRejectsInvalidLimitsAndOversizedPayloads() {
        val payload = Bundle().apply { putByteArray("payload", ByteArray(128)) }

        ExtensionPayloadValidator.requireBinderSafe(Bundle())
        expectIllegal { ExtensionPayloadValidator.requireBinderSafe(payload, 1) }
        expectIllegal { ExtensionPayloadValidator.requireBinderSafe(payload, 0) }
        expectIllegal {
            ExtensionPayloadValidator.requireBinderSafe(
                payload,
                PaperExtensionContract.MAX_BINDER_PAYLOAD_BYTES + 1,
            )
        }
    }

    @Test
    fun themeDescriptorAndThemeRoundTripEverySemanticToken() {
        val descriptor = ThemeExtensionDescriptor(
            packageName = "dev.example.theme",
            displayName = "Example theme",
            themeIds = setOf("example.dark", "example.light"),
        )
        assertEquals(descriptor, ThemeExtensionDescriptor.fromBundle(descriptor.toBundle()))

        val theme = fullTheme()
        assertEquals(theme, CommunityTheme.fromBundle(theme.toBundle()))
    }

    @Test
    fun themeDecoderRejectsMissingPaletteTokensAndUnknownEnums() {
        expectIllegal {
            CommunityTheme.fromBundle(fullTheme().toBundle().apply { remove("light_palette") })
        }
        expectIllegal {
            CommunityTheme.fromBundle(fullTheme().toBundle().apply { remove("dark_palette") })
        }
        expectIllegal {
            CommunityTheme.fromBundle(fullTheme().toBundle().apply {
                getBundle("light_palette")?.remove("canvas")
            })
        }
        for ((key, value) in listOf(
            "title_font" to "unknown_font",
            "body_font" to "unknown_font",
            "label_font" to "unknown_font",
            "decoration" to "unknown_decoration",
        )) {
            expectIllegal {
                CommunityTheme.fromBundle(fullTheme().toBundle().apply { putString(key, value) })
            }
        }
        expectIllegal {
            CommunityTheme.fromBundle(fullTheme().toBundle().apply {
                putStringArrayList("icon_keys", arrayListOf("unknown_icon"))
            })
        }
        expectIllegal {
            CommunityTheme.fromBundle(fullTheme().toBundle().apply { remove("icon_keys") })
        }

        val descriptor = ThemeExtensionDescriptor("dev.example.theme", "Theme", setOf("theme")).toBundle()
        expectIllegal {
            ThemeExtensionDescriptor.fromBundle(descriptor.apply { remove("themes") })
        }
    }

    private fun fullRecord(manifestation: SourceManifestation) = SourcePaperRecord(
        providerRecordId = "record-1",
        title = "Mobile-first papers",
        abstractText = "A readable abstract.",
        authors = listOf("Alice", "Bob"),
        subjects = setOf("reading", "mobile"),
        doi = "10.1234/example",
        arxivId = "2501.04510v2",
        pmid = "123456",
        pmcid = "PMC123456",
        citationCount = 42,
        publishedDate = "2026-08-14",
        updatedAt = "2026-08-14T00:00:00Z",
        manifestations = listOf(manifestation),
    )

    private fun readableMetadata(
        license: String? = "CC-BY-4.0",
        sections: List<SourceReadableSection> = emptyList(),
        warnings: Set<SourceReadableWarning> = emptySet(),
        assets: List<SourceReadableAsset> = emptyList(),
    ) = SourceReadableDocumentMetadata(
        requestId = "readable:metadata",
        title = "Readable paper",
        sourceUrl = "https://example.org/paper",
        sourceVersion = "v2",
        license = license,
        sourceSha256 = "a".repeat(64),
        documentSha256 = "b".repeat(64),
        sections = sections,
        warnings = warnings,
        assets = assets,
    )

    private fun fullTheme() = CommunityTheme(
        requestId = "theme:1",
        themeId = "example.theme",
        displayName = "Example theme",
        lightPalette = palette(-0x10101),
        darkPalette = palette(-0x20202),
        cornerRadiusDp = 12f,
        borderWidthDp = 2f,
        shadowOffsetDp = 4f,
        titleFont = ThemeFontFamily.SYSTEM_SERIF,
        bodyFont = ThemeFontFamily.SYSTEM_SANS,
        labelFont = ThemeFontFamily.SYSTEM_MONOSPACE,
        decoration = ThemeDecoration.DOODLE,
        iconKeys = ThemeSemanticIcon.entries.toSet(),
    )

    private fun palette(color: Int) = ThemePalette(
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

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }
}
