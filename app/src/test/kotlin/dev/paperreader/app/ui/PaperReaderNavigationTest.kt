package dev.paperreader.app.ui

import dev.paperreader.app.ui.model.LocalPdfImportUiState
import dev.paperreader.app.ui.model.MetadataBackupFailure
import dev.paperreader.app.ui.model.MetadataBackupOperation
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.logic.domain.LocalPdfImportFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperReaderNavigationTest {
    @Test
    fun `More route owns every secondary branch but not lookalike routes`() {
        assertFalse(isMoreRoute(null))
        assertTrue(isMoreRoute("more"))
        assertTrue(isMoreRoute("more/appearance"))
        assertTrue(isMoreRoute("more/collections"))
        assertTrue(isMoreRoute("more/reading-imports"))
        assertTrue(isMoreRoute("more/updates"))
        assertTrue(isMoreRoute("more/data-backup"))
        assertTrue(isMoreRoute("more/sources"))
        assertFalse(isMoreRoute("moreful"))
        assertFalse(isMoreRoute("history"))
    }

    @Test
    fun `primary navigation is reserved for root destinations`() {
        assertTrue(shouldShowPrimaryNavigation(null))
        assertTrue(shouldShowPrimaryNavigation("library"))
        assertTrue(shouldShowPrimaryNavigation("more"))
        assertFalse(shouldShowPrimaryNavigation("detail/{workId}"))
        assertFalse(shouldShowPrimaryNavigation("more/appearance"))
        assertFalse(shouldShowPrimaryNavigation("more/sources"))
    }

    @Test
    fun `background local PDF recovery does not hijack the startup destination`() {
        assertFalse(shouldOpenMoreForLocalPdfImport(LocalPdfImportUiState.Idle))
        assertFalse(shouldOpenMoreForLocalPdfImport(LocalPdfImportUiState.Preparing))
    }

    @Test
    fun `local PDF states needing user attention open More`() {
        assertTrue(
            shouldOpenMoreForLocalPdfImport(
                LocalPdfImportUiState.Failed(LocalPdfImportFailure.IO_FAILURE),
            ),
        )
    }

    @Test
    fun `restore review deterministically wins when two More flows need attention`() {
        val destination = moreAttentionDestination(
            metadataBackup = MetadataBackupUiState.Failed(
                operation = MetadataBackupOperation.PREVIEW,
                reason = MetadataBackupFailure.INVALID_OR_UNSUPPORTED,
            ),
            localPdfImport = LocalPdfImportUiState.Failed(LocalPdfImportFailure.IO_FAILURE),
        )

        assertTrue(destination == MoreAttentionDestination.DATA_BACKUP)
    }

    @Test
    fun `local PDF attention opens its branch when restore is idle`() {
        val destination = moreAttentionDestination(
            metadataBackup = MetadataBackupUiState.Idle,
            localPdfImport = LocalPdfImportUiState.Failed(LocalPdfImportFailure.IO_FAILURE),
        )

        assertTrue(destination == MoreAttentionDestination.READING_IMPORTS)
    }
}
