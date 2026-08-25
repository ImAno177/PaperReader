package dev.paperreader.app.reader

import dev.paperreader.app.PaperReaderApplication
import dev.paperreader.logic.domain.ReadingState
import dev.paperreader.logic.domain.WorkId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the two reader Activities on one serialized persistence path.
 * Locator construction remains in the reader-specific state files.
 */
internal class ReaderStatePersistence(
    private val application: PaperReaderApplication,
    private val workId: WorkId,
) {
    suspend fun <T> open(
        restore: (ReadingState?) -> T?,
        stateForOpen: (ReadingState?, Instant) -> ReadingState,
    ): T? = withContext(Dispatchers.IO) {
        application.readerWriteMutex.withLock {
            val paper = application.logic.useCases.getPaper.await(workId) ?: return@withLock null
            val existing = paper.readingState
            val restored = restore(existing)
            application.logic.useCases.updateReadingState.await(stateForOpen(existing, Instant.now()))
            restored
        }
    }

    suspend fun persist(stateForUpdate: (ReadingState?, Instant) -> ReadingState) {
        withContext(Dispatchers.IO) {
            application.readerWriteMutex.withLock {
                updateLocked(stateForUpdate)
            }
        }
    }

    fun flush(
        stateForUpdate: ((ReadingState?, Instant) -> ReadingState)?,
        sessionDuration: Duration?,
    ) {
        if (stateForUpdate == null && sessionDuration == null) return
        application.applicationIoScope.launch {
            try {
                application.readerWriteMutex.withLock {
                    stateForUpdate?.let { updateLocked(it) }
                    sessionDuration?.let { duration ->
                        application.logic.useCases.recordReadingSession.await(
                            workId = workId,
                            readAt = Instant.now(),
                            duration = duration,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Reader state is secondary to keeping the local document responsive and intact.
            }
        }
    }

    private suspend fun updateLocked(stateForUpdate: (ReadingState?, Instant) -> ReadingState) {
        val paper = application.logic.useCases.getPaper.await(workId) ?: return
        val existing = paper.readingState
        application.logic.useCases.updateReadingState.await(stateForUpdate(existing, Instant.now()))
    }
}
