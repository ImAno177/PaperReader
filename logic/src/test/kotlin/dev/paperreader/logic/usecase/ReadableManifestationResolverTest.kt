package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.ManifestationType
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.provider.MutableProviderManager
import dev.paperreader.logic.provider.PaperProvider
import dev.paperreader.logic.provider.PaperSearchQuery
import dev.paperreader.logic.provider.ProviderCapability
import dev.paperreader.logic.provider.ProviderDescriptor
import dev.paperreader.logic.provider.ProviderPage
import dev.paperreader.logic.provider.ProviderRole
import dev.paperreader.logic.provider.RemoteManifestation
import dev.paperreader.logic.provider.RemotePaper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableManifestationResolverTest {
    @Test
    fun `identifier-only search record is enriched by an exact content source`() = runTest {
        val provider = FixtureProvider(
            id = "arxiv",
            identifierTypes = setOf(IdentifierType.ARXIV),
            result = RemotePaper(
                providerId = "arxiv",
                providerRecordId = "1706.03762v2",
                title = "Attention Is All You Need",
                identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
                manifestations = listOf(
                    RemoteManifestation(
                        type = ManifestationType.PREPRINT,
                        version = "v2",
                        landingPageUrl = "https://arxiv.org/abs/1706.03762",
                        pdfUrl = "https://arxiv.org/pdf/1706.03762",
                    ),
                ),
            ),
        )
        val original = RemotePaper(
            providerId = "semanticscholar",
            providerRecordId = "s2-1",
            title = "Attention Is All You Need",
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
        )

        val enriched = ReadableManifestationResolver(MutableProviderManager(listOf(provider))).enrich(original)

        assertEquals(listOf(original, provider.result), enriched)
        assertEquals("arxiv:1706.03762", provider.lastQuery?.text)
        assertEquals(5, provider.lastQuery?.limit)
    }

    @Test
    fun `metadata and discovery providers without content role are never queried`() = runTest {
        val provider = FixtureProvider(
            id = "metadata",
            roles = setOf(ProviderRole.METADATA_ENGINE),
            capabilities = setOf(ProviderCapability.METADATA_RESOLUTION),
            identifierTypes = setOf(IdentifierType.DOI),
            result = null,
        )
        val original = RemotePaper(
            providerId = "semanticscholar",
            providerRecordId = "s2-2",
            title = "Paper",
            identifiers = setOf(PaperIdentifier(IdentifierType.DOI, "10.1000/example")),
        )

        val enriched = ReadableManifestationResolver(MutableProviderManager(listOf(provider))).enrich(original)

        assertEquals(listOf(original), enriched)
        assertTrue(provider.lastQuery == null)
        assertTrue(provider.getCalls == 0)
    }

    @Test
    fun `provider failures leave the original metadata durable`() = runTest {
        val provider = FixtureProvider(
            id = "arxiv",
            identifierTypes = setOf(IdentifierType.ARXIV),
            failure = IllegalStateException("extension unavailable"),
        )
        val original = RemotePaper(
            providerId = "semanticscholar",
            providerRecordId = "s2-3",
            title = "Paper",
            identifiers = setOf(PaperIdentifier(IdentifierType.ARXIV, "1706.03762")),
        )

        assertEquals(
            listOf(original),
            ReadableManifestationResolver(MutableProviderManager(listOf(provider))).enrich(original),
        )
    }

    private class FixtureProvider(
        id: String,
        private val roles: Set<ProviderRole> = setOf(ProviderRole.CONTENT_SOURCE),
        private val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.DISCOVERY),
        identifierTypes: Set<IdentifierType>,
        val result: RemotePaper? = null,
        private val failure: Throwable? = null,
    ) : PaperProvider {
        override val descriptor = ProviderDescriptor(
            id = id,
            displayName = id,
            minimumRequestIntervalMillis = 0,
            roles = roles,
            capabilities = capabilities,
            identifierLookupTypes = identifierTypes,
        )
        var lastQuery: PaperSearchQuery? = null
        var getCalls: Int = 0

        override suspend fun search(query: PaperSearchQuery): ProviderPage {
            lastQuery = query
            failure?.let { throw it }
            return ProviderPage(listOfNotNull(result))
        }

        override suspend fun get(recordId: String): RemotePaper? {
            getCalls++
            failure?.let { throw it }
            return result
        }
    }
}
