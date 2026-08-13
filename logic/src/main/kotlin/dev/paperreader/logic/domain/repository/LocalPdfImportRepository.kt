package dev.paperreader.logic.domain.repository

import dev.paperreader.logic.domain.LocalPdfCandidate
import dev.paperreader.logic.domain.LocalPdfImportResult
import dev.paperreader.logic.domain.PrepareLocalPdfResult

interface LocalPdfImportRepository {
    /** Validates and copies the source into bounded app-private staging before returning Ready. */
    suspend fun prepare(sourceUri: String): PrepareLocalPdfResult

    suspend fun recoverPending(): LocalPdfCandidate?

    suspend fun import(importToken: String, title: String): LocalPdfImportResult

    suspend fun discard(importToken: String)
}
