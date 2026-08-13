package dev.paperreader.app.reader

import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

class PaperPdfViewerFragment : PdfViewerFragment() {
    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        isToolboxVisible = false
        (activity as? PdfReaderActivity)?.onPdfDocumentLoaded(document.pageCount)
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        (activity as? PdfReaderActivity)?.onPdfDocumentError()
    }

    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        (activity as? PdfReaderActivity)?.onPdfViewCreated(pdfView)
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        // Editing is intentionally unavailable until annotations can be anchored to this exact SHA-256.
        isToolboxVisible = false
    }
}
