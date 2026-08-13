package dev.paperreader.app.ui

import dev.paperreader.app.backup.BackupDestinationRecoveryCancellationException
import dev.paperreader.app.ui.model.MetadataBackupFailure
import dev.paperreader.app.ui.model.MetadataBackupOperation
import dev.paperreader.app.ui.model.MetadataBackupUiState
import dev.paperreader.logic.backup.MetadataBackupError
import dev.paperreader.logic.backup.MetadataBackupExportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataBackupExportFlowTest {
    @Test
    fun `rejected export never deletes or writes an untouched destination`() = runBlocking {
        var writeCalled = false

        val state = executeMetadataBackupExport(
            export = {
                MetadataBackupExportResult.Rejected(
                    MetadataBackupError.Rejected(
                        code = "invalid_local_data",
                        detail = "Local metadata is invalid",
                    ),
                )
            },
            write = { writeCalled = true },
        )

        assertEquals(
            MetadataBackupUiState.Failed(
                operation = dev.paperreader.app.ui.model.MetadataBackupOperation.EXPORT,
                reason = MetadataBackupFailure.EXPORT_FAILED,
            ),
            state,
        )
        assertFalse(writeCalled)
    }

    @Test
    fun `failed recovery during cancellation maps to an incomplete destination warning`() {
        val cause = BackupDestinationRecoveryCancellationException(
            java.util.concurrent.CancellationException("cancelled"),
        )

        assertEquals(
            MetadataBackupUiState.Failed(
                operation = MetadataBackupOperation.EXPORT,
                reason = MetadataBackupFailure.INCOMPLETE_FILE_MAY_REMAIN,
            ),
            metadataBackupStateAfterCancellation(cause),
        )
    }
}
