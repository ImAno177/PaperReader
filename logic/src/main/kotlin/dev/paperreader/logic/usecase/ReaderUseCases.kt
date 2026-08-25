package dev.paperreader.logic.usecase

import dev.paperreader.logic.domain.ManifestationId
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.domain.repository.LibraryRepository
import dev.paperreader.logic.domain.repository.LocalPdfImportRepository
import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.PrepareLocalPdfResult
import dev.paperreader.logic.reader.ReadablePaperFailure
import dev.paperreader.logic.reader.ReadablePaperDocument
import dev.paperreader.logic.reader.ReadablePaperLoader
import dev.paperreader.logic.reader.ReadablePaperResult
import java.lang.ref.WeakReference

class LoadReadablePaper internal constructor(
    private val repository: LibraryRepository,
    private val loader: ReadablePaperLoader,
) {
    private var lastLoaded: LoadedReadablePaper? = null

    suspend fun await(
        workId: WorkId,
        manifestationId: ManifestationId,
        retainDocumentSha256: String? = null,
    ): ReadablePaperResult {
        val paper = repository.get(workId)
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.PAPER_NOT_FOUND)
        val manifestation = paper.manifestations.firstOrNull { it.id == manifestationId }
            ?: return ReadablePaperResult.Unavailable(ReadablePaperFailure.MANIFESTATION_NOT_FOUND)
        if (retainDocumentSha256 != null) {
            val recent = lastLoaded
            val recentDocument = recent?.document
            if (
                recent?.workId == workId &&
                recent.manifestationId == manifestationId &&
                recentDocument != null &&
                recentDocument.documentSha256 == retainDocumentSha256 &&
                loader.retain(recentDocument)
            ) {
                lastLoaded = null
                return ReadablePaperResult.Ready(recentDocument.copy(keptForOffline = true))
            }
        }
        val result = loader.load(paper.work.title, manifestation, retainDocumentSha256)
        lastLoaded = if (retainDocumentSha256 == null) {
            (result as? ReadablePaperResult.Ready)?.document?.let {
                LoadedReadablePaper(workId, manifestationId, WeakReference(it))
            }
        } else {
            null
        }
        return result
    }

    private data class LoadedReadablePaper(
        val workId: WorkId,
        val manifestationId: ManifestationId,
        private val documentReference: WeakReference<ReadablePaperDocument>,
    ) {
        val document: ReadablePaperDocument?
            get() = documentReference.get()
    }
}

class PrepareLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(sourceUri: String): PrepareLocalPdfResult = repository.prepare(sourceUri)
}

class RecoverPendingLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(): LocalPdfCandidate? = repository.recoverPending()
}

class ImportLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(importToken: String, title: String): LocalPdfImportResult =
        repository.import(importToken, title)
}

class DiscardPendingLocalPdf(private val repository: LocalPdfImportRepository) {
    suspend fun await(importToken: String) = repository.discard(importToken)
}
