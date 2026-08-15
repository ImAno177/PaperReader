package dev.paperreader.logic.plugin

import dev.paperreader.extensions.api.ExtensionFailure
import dev.paperreader.extensions.api.ExtensionFailureCode
import dev.paperreader.extensions.api.SourceExtensionDescriptor
import dev.paperreader.extensions.api.SourceGetPaperRequest
import dev.paperreader.extensions.api.SourceManifestation
import dev.paperreader.extensions.api.SourcePaperRecord
import dev.paperreader.extensions.api.SourceSearchPage
import dev.paperreader.extensions.api.SourceSearchRequest
import dev.paperreader.extensions.api.SourceCapability
import dev.paperreader.extensions.api.SourceRole
import dev.paperreader.extensions.api.SourceSearchSort
import dev.paperreader.extensions.api.SourceIdentifierType
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderException
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.SearchSort
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySourceProviderTest {
    @Test
    fun `rejects a trusted source service declared outside its package`() {
        val failure = runCatching {
            TrustedSourceExtension(
                packageName = "dev.paperreader.sample.source",
                serviceClassName = "dev.attacker.SourceService",
                versionCode = 1,
                signerSha256 = "00".repeat(32),
                providerId = "sample",
                displayName = "Sample source",
                minimumRequestIntervalMillis = 1_000,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `maps bounded extension records to neutral provider records`() = runTest {
        val provider = CommunitySourceProvider(
            FakeTransport(
                SourceSearchPage(
                    requestId = "ignored-by-fake",
                    records = listOf(record()),
                    nextCursor = "next",
                ),
            ),
        )

        val page = provider.search(PaperSearchQuery("mobile reading", limit = 5))

        assertEquals("next", page.nextCursor)
        assertEquals("sample", page.items.single().providerId)
        assertEquals(ManifestationType.PREPRINT, page.items.single().manifestations.single().type)
        assertEquals(
            setOf(
                IdentifierType.PROVIDER,
                IdentifierType.DOI,
                IdentifierType.ARXIV,
                IdentifierType.PMID,
                IdentifierType.PMCID,
            ),
            page.items.single().identifiers.mapTo(linkedSetOf()) { it.type },
        )
        assertEquals(42, page.items.single().citationMetrics?.count)
        assertEquals("sample", page.items.single().citationMetrics?.sourceId)
    }

    @Test
    fun `preserves extension rate-limit failures`() = runTest {
        val provider = CommunitySourceProvider(
            FakeTransport(
                searchFailure = ExtensionFailure(
                    requestId = "request",
                    code = ExtensionFailureCode.RATE_LIMITED,
                    message = "Try later",
                    retryAfterMillis = 4_000,
                ),
            ),
        )

        val failure = runCatching { provider.search(PaperSearchQuery("query")) }.exceptionOrNull()
        assertTrue(failure is ProviderException.RateLimited)
        failure as ProviderException.RateLimited
        assertEquals(4_000L, failure.retryAfterMillis)
    }

    @Test
    fun `assigns a bounded cooldown when an older extension omits retry after`() = runTest {
        val provider = CommunitySourceProvider(
            FakeTransport(
                searchFailure = ExtensionFailure(
                    requestId = "request",
                    code = ExtensionFailureCode.RATE_LIMITED,
                    message = "Try later",
                ),
            ),
        )

        val failure = runCatching { provider.search(PaperSearchQuery("query")) }.exceptionOrNull()
        assertTrue(failure is ProviderException.RateLimited)
        assertEquals(60_000L, (failure as ProviderException.RateLimited).retryAfterMillis)
    }

    @Test
    fun `rejects malformed escaped identifiers without escaping provider failure boundary`() = runTest {
        val provider = CommunitySourceProvider(
            FakeTransport(
                SourceSearchPage(
                    requestId = "ignored-by-fake",
                    records = listOf(record().copy(doi = "10.1000/%")),
                ),
            ),
        )

        val failure = runCatching { provider.search(PaperSearchQuery("query")) }.exceptionOrNull()

        assertTrue(failure is ProviderException.InvalidResponse)
    }

    @Test
    fun `maps every descriptor capability role identifier and sort`() {
        val descriptor = SourceExtensionDescriptor(
            packageName = "dev.paperreader.sample.source",
            providerId = "sample",
            displayName = "Sample source",
            capabilities = SourceCapability.entries.toSet(),
            roles = SourceRole.entries.toSet(),
            identifierLookupTypes = SourceIdentifierType.entries.toSet(),
            supportedSorts = SourceSearchSort.entries.toSet(),
        )

        val provider = CommunitySourceProvider(FakeTransport(descriptor = descriptor))

        assertEquals(setOf(ProviderCapability.DISCOVERY, ProviderCapability.METADATA_RESOLUTION), provider.descriptor.capabilities)
        assertEquals(ProviderRole.entries.toSet(), provider.descriptor.roles)
        assertEquals(
            setOf(IdentifierType.DOI, IdentifierType.ARXIV, IdentifierType.PMID, IdentifierType.PMCID),
            provider.descriptor.identifierLookupTypes,
        )
        assertEquals(SearchSort.entries.toSet(), provider.descriptor.supportedSorts)
    }

    @Test
    fun `get maps a record and preserves a missing record`() = runTest {
        val record = record().copy(
            manifestations = listOf(
                SourceManifestation("accepted_manuscript", version = "accepted"),
                SourceManifestation("version_of_record", version = "published"),
                SourceManifestation("other", version = "other"),
            ),
        )
        val provider = CommunitySourceProvider(
            FakeTransport(SourceSearchPage("request", listOf(record)))
        )

        val paper = provider.get("sample-1")
        assertEquals(3, paper?.manifestations?.size)
        assertEquals(ManifestationType.ACCEPTED_MANUSCRIPT, paper?.manifestations?.first()?.type)
        assertEquals(ManifestationType.VERSION_OF_RECORD, paper?.manifestations?.get(1)?.type)
        assertEquals(ManifestationType.OTHER, paper?.manifestations?.last()?.type)

        assertNull(CommunitySourceProvider(FakeTransport(SourceSearchPage("request", emptyList()))).get("missing"))
    }

    @Test
    fun `translates every extension failure category and preserves cancellation`() = runTest {
        val failures = listOf(
            ExtensionFailureCode.UNAVAILABLE to ProviderException.Unavailable::class.java,
            ExtensionFailureCode.INTERNAL_ERROR to ProviderException.Unavailable::class.java,
            ExtensionFailureCode.INVALID_REQUEST to ProviderException.InvalidResponse::class.java,
            ExtensionFailureCode.INVALID_RESPONSE to ProviderException.InvalidResponse::class.java,
        )
        failures.forEach { (code, expected) ->
            val failure = runCatching {
                CommunitySourceProvider(
                    FakeTransport(
                        searchFailure = ExtensionFailure("request", code, "failure"),
                    ),
                ).search(PaperSearchQuery("query"))
            }.exceptionOrNull()
            assertTrue("$code -> $failure", expected.isInstance(failure))
        }

        val cancelled = runCatching {
            CommunitySourceProvider(
                FakeTransport(searchThrowable = CancellationException("cancelled")),
            ).search(PaperSearchQuery("query"))
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)

        val providerFailure = ProviderException.InvalidResponse(IllegalArgumentException("bad"))
        val preserved = runCatching {
            CommunitySourceProvider(FakeTransport(searchThrowable = providerFailure)).search(PaperSearchQuery("query"))
        }.exceptionOrNull()
        assertTrue(preserved === providerFailure)

        val unavailable = runCatching {
            CommunitySourceProvider(FakeTransport(searchThrowable = IllegalStateException("offline")))
                .search(PaperSearchQuery("query"))
        }.exceptionOrNull()
        assertTrue(unavailable is ProviderException.Unavailable)
    }

    private fun record() = SourcePaperRecord(
        providerRecordId = "sample-1",
        title = "A readable paper",
        abstractText = "Abstract",
        authors = listOf("Ada Lovelace"),
        doi = "10.1000/sample",
        arxivId = "2501.00001v1",
        pmid = "12345678",
        pmcid = "PMC123456",
        citationCount = 42,
        publishedDate = "2026-01-02",
        updatedAt = "2026-01-02T03:04:05Z",
        manifestations = listOf(
            SourceManifestation(
                type = "preprint",
                version = "v1",
                landingPageUrl = "https://example.org/paper/1",
                pdfUrl = "https://example.org/paper/1.pdf",
                publishedDate = "2026-01-02",
            ),
        ),
    )

    private class FakeTransport(
        private val page: SourceSearchPage? = null,
        private val searchFailure: ExtensionFailure? = null,
        override val descriptor: SourceExtensionDescriptor = SourceExtensionDescriptor(
            packageName = "dev.paperreader.sample.source",
            providerId = "sample",
            displayName = "Sample source",
        ),
        private val searchThrowable: Throwable? = null,
    ) : SourceExtensionTransport {
        override suspend fun search(request: SourceSearchRequest): SourceSearchPage {
            searchThrowable?.let { throw it }
            searchFailure?.let { throw SourceExtensionRequestException(it) }
            return requireNotNull(page)
        }

        override suspend fun getPaper(request: SourceGetPaperRequest): SourcePaperRecord? = page?.records?.firstOrNull()
    }
}
