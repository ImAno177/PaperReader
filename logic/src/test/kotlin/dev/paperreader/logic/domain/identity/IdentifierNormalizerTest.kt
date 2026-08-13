package dev.paperreader.logic.domain.identity

import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.PaperIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifierNormalizerTest {
    @Test
    fun `normalizes DOI labels URLs case and escaped data`() {
        assertEquals("10.1000/xyz+abc", IdentifierNormalizer.doi(" DOI:10.1000/XYZ+ABC "))
        assertEquals(
            "10.1038/s41586-020-2649-2",
            IdentifierNormalizer.doi("https://doi.org/10.1038%2Fs41586-020-2649-2"),
        )
        assertEquals(
            "10.1000/xyz?literal",
            IdentifierNormalizer.doi("10.1000/XYZ?literal"),
        )
        assertEquals(
            "10.1000/xyz",
            IdentifierNormalizer.doi("https://doi.org/10.1000/XYZ?utm_source=share#details"),
        )
    }

    @Test
    fun `rejects values that are not DOI`() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentifierNormalizer.doi("https://example.org/not-a-doi")
        }
    }

    @Test
    fun `normalizes modern and legacy arxiv IDs while retaining version separately`() {
        assertEquals(
            NormalizedArxivId("2401.12345", 3),
            IdentifierNormalizer.arxiv("https://arxiv.org/pdf/2401.12345v3.pdf"),
        )
        assertEquals(
            NormalizedArxivId("hep-th/9901001", 2),
            IdentifierNormalizer.arxiv("arXiv:hep-th/9901001v2"),
        )
    }

    @Test
    fun `arxiv versions resolve to one work but unrelated titles do not fuzzy merge`() {
        val first = setOf(PaperIdentifier(IdentifierType.ARXIV, "2401.12345v1"))
        val revision = setOf(PaperIdentifier(IdentifierType.ARXIV, "2401.12345v4"))
        val titleOnly = emptySet<PaperIdentifier>()

        assertTrue(IdentityResolver.hasExactMatch(first, revision))
        assertFalse(IdentityResolver.hasExactMatch(first, titleOnly))
    }

    @Test
    fun `provider IDs only match within the same authority`() {
        val openAlex = setOf(PaperIdentifier(IdentifierType.PROVIDER, "W123", "OpenAlex"))
        val anotherOpenAlex = setOf(PaperIdentifier(IdentifierType.PROVIDER, "W123", "openalex"))
        val semanticScholar = setOf(PaperIdentifier(IdentifierType.PROVIDER, "W123", "semantic-scholar"))

        assertTrue(IdentityResolver.hasExactMatch(openAlex, anotherOpenAlex))
        assertFalse(IdentityResolver.hasExactMatch(openAlex, semanticScholar))
    }

    @Test
    fun `shared paper references preserve exact provider queries`() {
        assertEquals(
            PaperReferenceQuery(
                identifier = PaperIdentifier(IdentifierType.ARXIV, "2501.04510"),
                query = "2501.04510v2",
            ),
            PaperReferenceParser.parseSharedText("https://arxiv.org/abs/2501.04510v2"),
        )
        assertEquals(
            PaperReferenceQuery(
                identifier = PaperIdentifier(IdentifierType.DOI, "10.1038/s41586-020-2649-2"),
                query = "10.1038/s41586-020-2649-2",
            ),
            PaperReferenceParser.parseSharedText("DOI: 10.1038/S41586-020-2649-2"),
        )
    }

    @Test
    fun `shared text extracts one supported URL without treating prose as a query`() {
        assertEquals(
            "2501.04510",
            PaperReferenceParser.parseSharedText(
                "A paper worth reading\nhttps://arxiv.org/pdf/2501.04510.pdf",
            )?.query,
        )
        assertEquals(
            "10.1000/xyz+abc",
            PaperReferenceParser.parseSharedText(
                "Read this: https://doi.org/10.1000/XYZ+ABC",
            )?.query,
        )
        assertEquals(null, PaperReferenceParser.parseSharedText("A paper worth reading"))
    }

    @Test
    fun `ambiguous or oversized shared text is rejected`() {
        assertEquals(
            null,
            PaperReferenceParser.parseSharedText(
                "https://arxiv.org/abs/2501.04510 https://arxiv.org/abs/2401.12345",
            ),
        )
        assertEquals(null, PaperReferenceParser.parseSharedText("x".repeat(16_385)))
    }
}
