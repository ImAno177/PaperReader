package dev.paperreader.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.paperreader.app.backup.MetadataBackupFileGateway
import dev.paperreader.app.ui.screen.DataBackupViewModel
import dev.paperreader.logic.backup.MAX_METADATA_BACKUP_ARCHIVE_BYTES
import androidx.compose.ui.platform.LocalContext
import dev.paperreader.app.ui.screen.LibraryTransferViewModel
import dev.paperreader.logic.interop.BibliographyFormat
import java.io.IOException

internal data class LibraryTransferFileActions(
    val exportLibrary: () -> Unit,
    val exportMetadata: () -> Unit,
    val importMetadata: () -> Unit,
    val importLibrary: () -> Unit,
    val exportBibliography: (BibliographyFormat) -> Unit,
    val importBibliography: (BibliographyFormat) -> Unit,
    val exportNotes: () -> Unit,
)

@Composable
internal fun rememberLibraryTransferFileActions(viewModel: LibraryTransferViewModel, backupViewModel: DataBackupViewModel): LibraryTransferFileActions {
    val resolver = LocalContext.current.contentResolver
    val metadataFiles = remember(resolver) { MetadataBackupFileGateway(resolver) }
    val exportMetadata = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.paperreader.backup+zip")) { uri ->
        uri?.let { selected -> backupViewModel.createBackup { metadataFiles.write(selected, it.archiveBytes) } }
    }
    val importMetadata = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected -> backupViewModel.previewRestore { metadataFiles.read(selected, MAX_METADATA_BACKUP_ARCHIVE_BYTES) } }
    }
    val exportLibrary = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportLibrary { resolver.openOutputStream(it, "wt") ?: throw IOException("Cannot open output") } }
    }
    val importLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspectLibrary { resolver.openInputStream(it) ?: throw IOException("Cannot open input") } }
    }
    val exportBibTeX = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-bibtex")) { uri ->
        uri?.let { viewModel.exportBibliography(BibliographyFormat.BIBTEX) {
            resolver.openOutputStream(it, "wt") ?: throw IOException("Cannot open output")
        } }
    }
    val exportRis = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-research-info-systems")) { uri ->
        uri?.let { viewModel.exportBibliography(BibliographyFormat.RIS) {
            resolver.openOutputStream(it, "wt") ?: throw IOException("Cannot open output")
        } }
    }
    val importBibTeX = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspectBibliography(BibliographyFormat.BIBTEX) {
            resolver.openInputStream(it) ?: throw IOException("Cannot open input")
        } }
    }
    val importRis = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.inspectBibliography(BibliographyFormat.RIS) {
            resolver.openInputStream(it) ?: throw IOException("Cannot open input")
        } }
    }
    val exportNotes = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { viewModel.exportNotes { resolver.openOutputStream(it, "wt") ?: throw IOException("Cannot open output") } }
    }
    return LibraryTransferFileActions(
        exportMetadata = { exportMetadata.launch("paper-reader-metadata.paperreader.backup") },
        importMetadata = { importMetadata.launch(arrayOf("application/vnd.paperreader.backup+zip", "application/zip", "application/octet-stream")) },
        exportLibrary = { exportLibrary.launch("paper-reader-library.paperreader.zip") },
        importLibrary = { importLibrary.launch(arrayOf("*/*")) },
        exportBibliography = { format ->
            when (format) {
                BibliographyFormat.BIBTEX -> exportBibTeX.launch("paper-reader-library.bib")
                BibliographyFormat.RIS -> exportRis.launch("paper-reader-library.ris")
            }
        },
        importBibliography = { format ->
            when (format) {
                BibliographyFormat.BIBTEX -> importBibTeX.launch(arrayOf("*/*"))
                BibliographyFormat.RIS -> importRis.launch(arrayOf("*/*"))
            }
        },
        exportNotes = { exportNotes.launch("paper-reader-notes.md") },
    )
}
