package dev.paperreader.app.ui.screen

import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatesPresentationTest {
    @Test
    fun `download actions follow durable task state`() {
        assertEquals(setOf(DownloadTaskAction.CANCEL), actions(TaskState.QUEUED))
        assertEquals(setOf(DownloadTaskAction.CANCEL), actions(TaskState.RUNNING))
        assertEquals(
            setOf(DownloadTaskAction.RETRY, DownloadTaskAction.REMOVE),
            actions(TaskState.FAILED),
        )
        assertEquals(
            setOf(DownloadTaskAction.RETRY, DownloadTaskAction.REMOVE),
            actions(TaskState.CANCELLED),
        )
        assertEquals(setOf(DownloadTaskAction.REMOVE), actions(TaskState.SUCCEEDED))
    }

    @Test
    fun `download controls are not shown for unsupported extraction tasks`() {
        assertEquals(
            emptySet<DownloadTaskAction>(),
            downloadTaskActions(task(TaskState.QUEUED, TaskKind.EXTRACTION)),
        )
    }

    private fun actions(state: TaskState) = downloadTaskActions(task(state, TaskKind.DOWNLOAD))

    private fun task(state: TaskState, kind: TaskKind) = PaperTask(
        id = TaskId("task-${state.name.lowercase()}-${kind.name.lowercase()}"),
        kind = kind,
        workId = WorkId("work-1"),
        targetKey = "manifestation-1",
        state = state,
        progress = if (state == TaskState.SUCCEEDED) 1.0 else 0.0,
        attempt = 0,
        failureCode = if (state == TaskState.FAILED) "HTTP_503" else null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T00:00:00Z")
    }
}
