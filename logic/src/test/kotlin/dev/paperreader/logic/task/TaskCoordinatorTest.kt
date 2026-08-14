package dev.paperreader.logic.task

import dev.paperreader.logic.domain.WorkId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCoordinatorTest {
    private val repository = FakeTaskRepository()
    private val coordinator = TaskCoordinator(
        repository,
        Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `enqueue is deterministic and does not duplicate work after process recreation`() = runTest {
        val first = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "manifestation-1")
        val second = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "manifestation-1")

        assertEquals(first.id, second.id)
        assertEquals(1, repository.values.size)
        assertEquals(1, coordinator.tasks.first().size)
    }

    @Test
    fun `coordinator owns lifecycle attempts and monotonic progress`() = runTest {
        val queued = coordinator.enqueue(TaskKind.EXTRACTION, WorkId("w-1"), "pdf-sha")
        val running = coordinator.transition(queued.id, TaskState.RUNNING)
        val halfway = coordinator.updateProgress(queued.id, 0.5)
        val complete = coordinator.transition(queued.id, TaskState.SUCCEEDED)

        assertEquals(1, running.attempt)
        assertEquals(0.5, halfway.progress, 0.0)
        assertEquals(1.0, complete.progress, 0.0)
    }

    @Test
    fun `progress cannot move backwards`() = runTest {
        val task = coordinator.enqueue(TaskKind.EXTRACTION, null, "pdf-sha")
        coordinator.transition(task.id, TaskState.RUNNING)
        coordinator.updateProgress(task.id, 0.6)

        var rejected = false
        try {
            coordinator.updateProgress(task.id, 0.4)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertEquals(true, rejected)
    }

    @Test
    fun `only terminal tasks can be removed for a clean redownload`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "pdf")
        coordinator.transition(task.id, TaskState.RUNNING)
        coordinator.transition(task.id, TaskState.SUCCEEDED)

        assertEquals(true, coordinator.removeTerminal(task.id))
        assertEquals(null, coordinator.get(task.id))
    }

    @Test
    fun `cancel wins a compare and set race against completion`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "pdf")
        coordinator.transition(task.id, TaskState.RUNNING)
        val completionEntered = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        repository.beforeUpdate = { update ->
            if (update.state == TaskState.SUCCEEDED) {
                completionEntered.complete(Unit)
                allowCompletion.await()
            }
        }

        val completion = async { runCatching { coordinator.transition(task.id, TaskState.SUCCEEDED) } }
        completionEntered.await()
        val cancellation = coordinator.cancel(task.id)
        allowCompletion.complete(Unit)

        assertEquals(TaskState.CANCELLED, (cancellation as CancelTaskResult.Cancelled).task.state)
        assertEquals(true, completion.await().exceptionOrNull() is ConcurrentTaskUpdateException)
        assertEquals(TaskState.CANCELLED, coordinator.get(task.id)?.state)
    }

    @Test
    fun `manual retry clears failure progress and exhausted attempt budget`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "pdf")
        coordinator.transition(task.id, TaskState.RUNNING)
        coordinator.updateProgress(task.id, 0.7)
        coordinator.transition(task.id, TaskState.FAILED, "HTTP_503_RETRY_EXHAUSTED")

        val retried = coordinator.retry(task.id) as RetryTaskResult.Retried

        assertEquals(TaskState.QUEUED, retried.task.state)
        assertEquals(0, retried.task.attempt)
        assertEquals(0.0, retried.task.progress, 0.0)
        assertEquals(null, retried.task.failureCode)
    }

    @Test
    fun `active task cannot be cleared from the queue`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "pdf")

        assertEquals(RemoveTaskResult.Active, coordinator.remove(task.id))
        assertEquals(TaskState.QUEUED, coordinator.get(task.id)?.state)
    }

    @Test
    fun `concurrent running updates cannot regress progress`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, WorkId("w-1"), "pdf")
        coordinator.transition(task.id, TaskState.RUNNING)
        val lowerEntered = CompletableDeferred<Unit>()
        val allowLower = CompletableDeferred<Unit>()
        repository.beforeUpdate = { update ->
            if (update.state == TaskState.RUNNING && update.progress == 0.4) {
                lowerEntered.complete(Unit)
                allowLower.await()
            }
        }

        val lower = async { runCatching { coordinator.updateProgress(task.id, 0.4) } }
        lowerEntered.await()
        coordinator.updateProgress(task.id, 0.8)
        allowLower.complete(Unit)

        assertEquals(true, lower.await().exceptionOrNull() is ConcurrentTaskUpdateException)
        assertEquals(0.8, coordinator.get(task.id)?.progress ?: -1.0, 0.0)
    }

    @Test
    fun `terminal and missing task commands are explicit`() = runTest {
        val missing = TaskId("missing")
        assertEquals(CancelTaskResult.NotFound, coordinator.cancel(missing))
        assertEquals(RetryTaskResult.NotFound, coordinator.retry(missing))
        assertEquals(RemoveTaskResult.NotFound, coordinator.remove(missing))
        assertEquals(null, coordinator.find(TaskKind.DOWNLOAD, null, "missing"))

        val succeeded = coordinator.enqueue(TaskKind.DOWNLOAD, null, "done")
        coordinator.transition(succeeded.id, TaskState.RUNNING)
        coordinator.transition(succeeded.id, TaskState.SUCCEEDED)
        assertTrue(coordinator.cancel(succeeded.id) is CancelTaskResult.AlreadyTerminal)
        assertTrue(coordinator.retry(succeeded.id) is RetryTaskResult.NotRetryable)
        assertTrue(coordinator.removeTerminal(succeeded.id))
        val active = coordinator.enqueue(TaskKind.DOWNLOAD, null, "active")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.removeTerminal(active.id) }
        }
    }

    @Test
    fun `transition and progress validate state and failure code ownership`() = runTest {
        val task = coordinator.enqueue(TaskKind.DOWNLOAD, null, "validation")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.transition(TaskId("unknown"), TaskState.RUNNING) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.transition(task.id, TaskState.RUNNING, "not-failed") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.updateProgress(task.id, 0.1) }
        }
        coordinator.transition(task.id, TaskState.RUNNING)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.updateProgress(task.id, 1.1) }
        }
        assertEquals(task.id, coordinator.find(TaskKind.DOWNLOAD, null, "validation")?.id)
    }

    private class FakeTaskRepository : PaperTaskRepository {
        val values = linkedMapOf<TaskId, PaperTask>()
        var beforeUpdate: suspend (PaperTask) -> Unit = {}
        private val flow = MutableStateFlow<List<PaperTask>>(emptyList())
        override val tasks: Flow<List<PaperTask>> = flow

        override suspend fun get(id: TaskId): PaperTask? = values[id]

        override suspend fun insertIfAbsent(task: PaperTask): Boolean {
            if (values.putIfAbsent(task.id, task) != null) return false
            publish()
            return true
        }

        override suspend fun updateIfCurrent(task: PaperTask, expected: PaperTask): Boolean {
            beforeUpdate(task)
            val current = values[task.id] ?: return false
            if (current != expected) return false
            values[task.id] = task
            publish()
            return true
        }

        override suspend fun deleteIfState(id: TaskId, expectedState: TaskState): Boolean {
            val current = values[id] ?: return false
            if (current.state != expectedState) return false
            values.remove(id)
            publish()
            return true
        }

        private fun publish() {
            flow.value = values.values.toList()
        }
    }
}
