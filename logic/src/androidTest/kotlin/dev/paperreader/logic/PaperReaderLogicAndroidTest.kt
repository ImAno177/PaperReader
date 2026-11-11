package dev.paperreader.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperReaderLogicAndroidTest {
    private val databaseNames = mutableListOf<String>()

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun bibliographyImportPreservesExactIdentityAndSkipsConflictingSavedWorks() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "paper-reader-import-${UUID.randomUUID()}.db"
        databaseNames += databaseName
        PaperReaderLogic.open(context, emptyList(), PaperReaderConfiguration(databaseName = databaseName)).use { logic ->
            val doi = dev.paperreader.logic.domain.PaperIdentifier(dev.paperreader.logic.domain.IdentifierType.DOI, "10.1234/one")
            val arxiv = dev.paperreader.logic.domain.PaperIdentifier(dev.paperreader.logic.domain.IdentifierType.ARXIV, "2401.00001")
            val first = logic.useCases.savePaper.await(dev.paperreader.logic.provider.RemotePaper("fixture", "one", "First", identifiers = setOf(doi)))
            val second = logic.useCases.savePaper.await(dev.paperreader.logic.provider.RemotePaper("fixture", "two", "Second", identifiers = setOf(arxiv)))
            try {
                logic.useCases.savePaper.await(dev.paperreader.logic.provider.RemotePaper("fixture", "collision", "Conflict", identifiers = setOf(doi, arxiv)))
                org.junit.Assert.fail("One incoming record cannot join distinct saved identities")
            } catch (_: dev.paperreader.logic.domain.repository.ExactIdentityConflictException) { }
            val bytes = "@misc{x,title={Conflict},doi={10.1234/one},eprint={2401.00001},archiveprefix={arXiv}}".toByteArray()
            val preview = logic.useCases.previewBibliographyImport.preview(dev.paperreader.logic.interop.BibliographyFormat.BIBTEX, bytes)
            val report = logic.useCases.importBibliography.import(preview)
            org.junit.Assert.assertEquals(1, report.skippedCount)
            org.junit.Assert.assertEquals("First", logic.useCases.getPaper.await(first)?.work?.title)
            org.junit.Assert.assertEquals("Second", logic.useCases.getPaper.await(second)?.work?.title)
            val batch = "@misc{a,title={Third},doi={10.1234/three},year={2023}}\n@misc{b,title={Third revised},doi={10.1234/three},year={2023}}".toByteArray()
            val batchPreview = logic.useCases.previewBibliographyImport.preview(dev.paperreader.logic.interop.BibliographyFormat.BIBTEX, batch)
            val applied = logic.useCases.importBibliography.import(batchPreview)
            org.junit.Assert.assertEquals(1, applied.importedCount)
            org.junit.Assert.assertEquals(1, applied.mergedCount)
            val imported = logic.useCases.getPaper.await(applied.records.first().workId!!)!!
            org.junit.Assert.assertEquals(2023, imported.work.publicationYear)
            org.junit.Assert.assertNull(imported.work.publishedDate)
        }
    }

    @Test
    fun openBuildsTheApplicationFacadeAndClosesItsDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "paper-reader-test-${UUID.randomUUID()}.db"
        databaseNames += databaseName

        val logic = PaperReaderLogic.open(
            context = context,
            builtInProviders = emptyList(),
            configuration = PaperReaderConfiguration(databaseName = databaseName),
        )

        assertTrue(logic.providers.state.value.installed.isEmpty())
        logic.setDisabledProviderIds(setOf("missing"))
        assertTrue("missing" in logic.providers.state.value.disabledProviderIds)
        logic.close()
    }
}
