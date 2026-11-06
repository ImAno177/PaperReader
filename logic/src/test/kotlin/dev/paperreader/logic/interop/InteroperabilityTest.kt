package dev.paperreader.logic.interop

import dev.paperreader.logic.domain.Annotation
import dev.paperreader.logic.domain.CollectionId
import dev.paperreader.logic.domain.IdentifierType
import dev.paperreader.logic.domain.LibraryPaper
import dev.paperreader.logic.domain.PaperCollection
import dev.paperreader.logic.domain.PaperIdentifier
import dev.paperreader.logic.domain.PaperWork
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.CreateCollectionResult
import dev.paperreader.logic.domain.repository.DeleteCollectionResult
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.RemovePaperResult
import dev.paperreader.logic.domain.repository.RenameCollectionResult
import dev.paperreader.logic.domain.repository.SetPaperCollectionsResult
import dev.paperreader.logic.usecase.BibliographyImportDisposition
import dev.paperreader.logic.usecase.BibliographyImportOutcome
import dev.paperreader.logic.usecase.ImportBibliography
import dev.paperreader.logic.usecase.PreviewBibliographyImport
import dev.paperreader.logic.domain.identity.IdentifierNormalizer
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteroperabilityTest {
    @Test
    fun `BibTeX supported metadata round trips escaped text identifiers and partial dates`() {
        val entry = BibliographyEntry(
            title = "A {careful} 100% & \\write18 study",
            authors = listOf("Research {and} Development", "Doe, Jane"),
            identifiers = setOf(
                id(IdentifierType.DOI, "10.1234/Example"),
                id(IdentifierType.ARXIV, "arXiv:2401.00001v2"),
                id(IdentifierType.PMID, "123456"),
                id(IdentifierType.PMCID, "PMC123456"),
            ),
            abstractText = "An abstract with <markup> \u0026 symbols.",
            publishedAt = BibliographyDate(2024, 5),
            sourceUrl = "https://example.org/paper?id=one\u0026view=full",
            entryType = "article",
            citationKey = "doe2024",
        )

        val encoded = BibliographyCodec.encode(BibliographyFormat.BIBTEX, listOf(entry))
        val decoded = success(BibliographyCodec.parse(BibliographyFormat.BIBTEX, encoded))

        assertEquals(listOf(entry), decoded)
        assertTrue(encoded.contains("\\textbackslash{}write18"))
    }

    @Test
    fun `RIS supported fields round trip full date and repeated authors`() {
        val entry = BibliographyEntry(
            title = "Graph & citation study",
            authors = listOf("Ada Example", "Lin Paper"),
            identifiers = setOf(
                id(IdentifierType.DOI, "10.5555/ABC"),
                id(IdentifierType.ARXIV, "2402.00002"),
                id(IdentifierType.PMID, "7654321"),
                id(IdentifierType.PMCID, "PMC7654321"),
            ),
            abstractText = "Abstract with angle brackets <data>.",
            publishedAt = BibliographyDate(2025, 2, 28),
            sourceUrl = "https://example.org/item",
            entryType = "inproceedings",
        )

        val encoded = BibliographyCodec.encode(BibliographyFormat.RIS, listOf(entry))
        val decoded = success(BibliographyCodec.parse(BibliographyFormat.RIS, encoded))

        assertEquals(listOf(entry), decoded)
        assertTrue(encoded.startsWith("TY  - CPAPER\n"))
        assertTrue(encoded.endsWith("ER  - \n"))
    }

    @Test
    fun `BibTeX refuses macros concatenation duplicate fields and excessive nesting`() {
        val macro = "@article{x, title = titleMacro}"
        val concatenated = "@article{x, title = {A} # {B}}"
        val duplicate = "@article{x, title = {A}, title = {B}}"
        val deep = "@article{x, title = {${"{".repeat(26)}x${"}".repeat(26)}}}"

        assertEquals(BibliographyErrorCode.UNSUPPORTED_SYNTAX, failure(macro).code)
        assertEquals(BibliographyErrorCode.UNSUPPORTED_SYNTAX, failure(concatenated).code)
        assertEquals(BibliographyErrorCode.DUPLICATE_FIELD, failure(duplicate).code)
        assertEquals(BibliographyErrorCode.LIMIT_EXCEEDED, failure(deep).code)
    }

    @Test
    fun `BibTeX rejects duplicate citation keys without evaluating directives`() {
        val duplicateKey = "@article{x,title={One}}\n@article{X,title={Two}}"
        val stringDirective = "@string{title = {Unsafe macro}}"

        assertEquals(BibliographyErrorCode.DUPLICATE_RECORD, failure(duplicateKey).code)
        assertEquals(BibliographyErrorCode.UNSUPPORTED_SYNTAX, failure(stringDirective).code)
    }

    @Test
    fun `RIS requires complete records rejects duplicates and unsafe URLs`() {
        assertEquals(
            BibliographyErrorCode.MALFORMED,
            failure("TI  - Missing TY\nER  - \n", BibliographyFormat.RIS).code,
        )
        assertEquals(
            BibliographyErrorCode.DUPLICATE_FIELD,
            failure("TY  - JOUR\nTI  - One\nTI  - Two\nER  - \n", BibliographyFormat.RIS).code,
        )
        assertEquals(
            BibliographyErrorCode.MALFORMED,
            failure("TY  - JOUR\nTI  - Paper\nUR  - file:///private/paper.pdf\nER  - \n", BibliographyFormat.RIS).code,
        )
    }

    @Test
    fun `codec rejects malformed UTF8 and oversized byte input`() {
        val malformed = BibliographyCodec.parse(BibliographyFormat.RIS, byteArrayOf(0xc3.toByte(), 0x28))
        val oversized = BibliographyCodec.parse(BibliographyFormat.RIS, ByteArray(8 * 1024 * 1024 + 1))

        assertEquals(BibliographyErrorCode.INVALID_UTF8, (malformed as BibliographyParseResult.Failure).error.code)
        assertEquals(BibliographyErrorCode.TOO_LARGE, (oversized as BibliographyParseResult.Failure).error.code)
    }

    @Test
    fun `import preview merges only on exact canonical identifiers`() = runTest {
        val existing = paper(
            "work-doi",
            "Same title",
            setOf(id(IdentifierType.DOI, "10.1234/exact")),
        )
        val repository = FakeLibraryRepository(listOf(existing))
        val input = """
            @article{one, title={Same title}, doi={https://doi.org/10.1234/EXACT}}
            @article{two, title={Same title}, author={A. Author}}
        """.trimIndent().toByteArray()

        val preview = PreviewBibliographyImport(repository).preview(BibliographyFormat.BIBTEX, input)

        assertEquals(null, preview.error)
        assertEquals(BibliographyImportDisposition.EXACT_MATCH, preview.candidates[0].disposition)
        assertEquals(setOf(WorkId("work-doi")), preview.candidates[0].matchedWorkIds)
        assertEquals(BibliographyImportDisposition.NEW, preview.candidates[1].disposition)
        assertTrue(preview.candidates[1].matchedWorkIds.isEmpty())
        assertEquals(
            preview.candidates[1].stableRecordId,
            stableBibliographyRecordId(preview.candidates[1].entry.copy(citationKey = "different-key")),
        )
    }

    @Test
    fun `import keeps year and full date without inventing dates and reports partial precision loss`() = runTest {
        val repository = FakeLibraryRepository(emptyList())
        val input = """
            @article{partial, title={Partial date}, date={2024-05}}
            @article{full, title={Full date}, date={2024-05-12}}
        """.trimIndent().toByteArray()
        val preview = PreviewBibliographyImport(repository).preview(BibliographyFormat.BIBTEX, input)

        assertEquals(listOf(0), preview.partialDatePrecisionLossIndices)
        val report = ImportBibliography(repository).import(preview)

        assertEquals(2, report.importedCount)
        assertEquals(BibliographyImportOutcome.IMPORTED, report.records[0].outcome)
        assertEquals(null, repository.saved[0].publishedDate)
        assertEquals(2024, repository.saved[0].publicationYear)
        assertEquals(java.time.LocalDate.of(2024, 5, 12), repository.saved[1].publishedDate)
        assertEquals(2024, repository.saved[1].publicationYear)
    }

    @Test
    fun `import skips records whose exact identifiers point to different works`() = runTest {
        val repository = FakeLibraryRepository(
            listOf(
                paper("work-doi", "Same title", setOf(id(IdentifierType.DOI, "10.1234/exact"))),
                paper("work-arxiv", "Same title", setOf(id(IdentifierType.ARXIV, "2401.00001"))),
            ),
        )
        val input = """
            @article{combined, title={Same title}, doi={10.1234/exact}, arxiv={2401.00001}}
        """.trimIndent().toByteArray()
        val preview = PreviewBibliographyImport(repository).preview(BibliographyFormat.BIBTEX, input)

        assertEquals(BibliographyImportDisposition.AMBIGUOUS, preview.candidates.single().disposition)
        val report = ImportBibliography(repository).import(preview)

        assertEquals(BibliographyImportOutcome.SKIPPED_AMBIGUOUS, report.records.single().outcome)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `Markdown renders annotation data as inert code and preserves each exact hash anchor`() {
        val workId = WorkId("work-1")
        val first = annotation(workId, "a".repeat(64), "quote with `fence`\n</script>", "note **text**")
        val second = annotation(workId, "b".repeat(64), "revised quote", null)
        val before = listOf(first, second)
        val markdown = AnnotationMarkdownRenderer.render(
            listOf(
                AnnotatedPaperSnapshot(
                    workId = workId,
                    title = "Title <img src=x onerror=alert(1)>",
                    identifiers = setOf(id(IdentifierType.DOI, "10.1234/example")),
                    annotations = before,
                ),
            ),
        )

        assertTrue(markdown.contains("Document SHA-256"))
        assertTrue(markdown.contains(first.documentSha256))
        assertTrue(markdown.contains(second.documentSha256))
        assertTrue(markdown.contains("block-1"))
        assertTrue(markdown.contains("Page: `5`"))
        assertTrue(markdown.contains("10.1234/example"))
        assertTrue(markdown.contains("</script>"))
        assertTrue(markdown.contains("\n```text\n"))
        assertEquals("quote with `fence`\n</script>", first.quoteExact)
    }

    private fun annotation(workId: WorkId, hash: String, quote: String, note: String?) = Annotation(
        id = "annotation-${hash.first()}",
        workId = workId,
        documentSha256 = hash,
        blockId = "block-1",
        startOffset = 2,
        endOffset = 2 + quote.length,
        quotePrefix = "prefix",
        quoteExact = quote,
        quoteSuffix = "suffix",
        pageIndex = 4,
        note = note,
        color = "yellow",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun paper(id: String, title: String, identifiers: Set<PaperIdentifier>) = LibraryPaper(
        work = PaperWork(
            id = WorkId(id),
            title = title,
            identifiers = identifiers,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
        manifestations = emptyList(),
    )

    private fun id(type: IdentifierType, value: String) =
        IdentifierNormalizer.canonical(PaperIdentifier(type, value))

    private fun success(result: BibliographyParseResult): List<BibliographyEntry> =
        (result as? BibliographyParseResult.Success)?.entries
            ?: error("Expected success, received ${(result as BibliographyParseResult.Failure).error}")

    private fun failure(
        value: String,
        format: BibliographyFormat = BibliographyFormat.BIBTEX,
    ): BibliographyError =
        (BibliographyCodec.parse(format, value) as BibliographyParseResult.Failure).error

    private class FakeLibraryRepository(initialPapers: List<LibraryPaper>) : LibraryRepository {
        override val library: Flow<List<LibraryPaper>> = MutableStateFlow(initialPapers)
        override val collections: Flow<List<PaperCollection>> = MutableStateFlow(emptyList())
        val saved = mutableListOf<dev.paperreader.logic.provider.RemotePaper>()

        override suspend fun save(remote: dev.paperreader.logic.provider.RemotePaper): WorkId {
            saved += remote
            return WorkId("imported-${saved.size}")
        }

        override suspend fun get(workId: WorkId): LibraryPaper? = null
        override suspend fun updateReadingState(state: ReadingState) = Unit
        override suspend fun remove(workId: WorkId): RemovePaperResult = RemovePaperResult.NotFound
        override suspend fun createCollection(name: String): CreateCollectionResult = CreateCollectionResult.InvalidName
        override suspend fun renameCollection(id: CollectionId, name: String): RenameCollectionResult = RenameCollectionResult.NotFound
        override suspend fun deleteCollection(id: CollectionId): DeleteCollectionResult = DeleteCollectionResult.NotFound
        override suspend fun setPaperCollections(
            workId: WorkId,
            collectionIds: Set<CollectionId>,
        ): SetPaperCollectionsResult = SetPaperCollectionsResult.PaperNotFound
    }
}
