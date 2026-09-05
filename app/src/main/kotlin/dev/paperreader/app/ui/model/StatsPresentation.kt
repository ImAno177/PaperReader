package dev.paperreader.app.ui.model

import dev.paperreader.logic.domain.ReadingStatus
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskState
import java.time.Duration

data class PaperStatsSnapshot(
    val libraryCount: Int,
    val readingCount: Int,
    val finishedCount: Int,
    val unreadCount: Int,
    val localDocumentCount: Int,
    val annotationCount: Int,
    val collectionCount: Int,
    val historyEntryCount: Int,
    val totalReadDuration: Duration,
    val pendingTaskCount: Int,
    val savedSearchCount: Int,
)

fun buildStatsSnapshot(
    library: List<PaperUi>,
    history: List<ReadingHistoryUi>,
    collectionCount: Int,
    tasks: List<PaperTask>,
    savedSearchCount: Int,
): PaperStatsSnapshot = PaperStatsSnapshot(
    libraryCount = library.size,
    readingCount = library.count { it.status == ReadingStatus.READING },
    finishedCount = library.count { it.status == ReadingStatus.FINISHED },
    unreadCount = library.count { it.status == ReadingStatus.UNREAD },
    localDocumentCount = library.sumOf { paper -> paper.manifestations.count { it.localCopy != null } },
    annotationCount = library.sumOf(PaperUi::annotationCount),
    collectionCount = collectionCount,
    historyEntryCount = history.size,
    totalReadDuration = history.fold(Duration.ZERO) { total, entry -> total.plus(entry.totalReadDuration) },
    pendingTaskCount = tasks.count { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING },
    savedSearchCount = savedSearchCount,
)
