package dev.paperreader.logic.usecase

import dev.paperreader.logic.backup.LibraryRestoreCandidate
import dev.paperreader.logic.backup.LibraryRestoreReport
import dev.paperreader.logic.backup.PreparedLibraryArchive
import java.io.InputStream
import java.io.OutputStream

class LibraryArchiveUseCases internal constructor(
    val prepareExport: suspend () -> PreparedLibraryArchive,
    val writeExport: suspend (String, OutputStream) -> Unit,
    val prepareRestore: suspend (InputStream) -> LibraryRestoreCandidate,
    val recoverPendingRestore: suspend () -> LibraryRestoreCandidate?,
    val restore: suspend (String) -> LibraryRestoreReport,
    val discard: suspend (String) -> Unit,
    internal val reconcileStaging: suspend () -> Unit,
)
