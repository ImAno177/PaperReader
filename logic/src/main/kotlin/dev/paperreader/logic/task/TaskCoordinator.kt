package dev.paperreader.logic.task

import dev.paperreader.logic.domain.WorkId
import java.time.Clock
import kotlinx.coroutines.flow.Flow

sealed interface CancelTaskResult {
    data class Cancelled(val task: PaperTask) : CancelTaskResult
    data class AlreadyTerminal(val task: PaperTask) : CancelTaskResult
    data object NotFound : CancelTaskResult
}

sealed interface RetryTaskResult {
    data class Retried(val task: PaperTask) : RetryTaskResult
    data class NotRetryable(val task: PaperTask) : RetryTaskResult
    data object NotFound : RetryTaskResult
}

sealed interface RemoveTaskResult {
    data object Removed : RemoveTaskResult
    data object Active : RemoveTaskResult
    data object NotFound : RemoveTaskResult
}

class TaskCoordinator(
    private val repository: PaperTaskRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    val tasks: Flow<List<PaperTask>> = repository.tasks

    suspend fun get(id: TaskId): PaperTask? = repository.get(id)

    suspend fun find(kind: TaskKind, workId: WorkId?, targetKey: String): PaperTask? =
        repository.get(TaskIdentity.id(kind, workId, targetKey))

    suspend fun enqueue(
        kind: TaskKind,
        workId: WorkId?,
        targetKey: String,
    ): PaperTask {
        val id = TaskIdentity.id(kind, workId, targetKey)
        repository.get(id)?.let { return it }
        val now = clock.instant()
        val task = PaperTask(
            id = id,
            kind = kind,
            workId = workId,
            targetKey = targetKey,
            state = TaskState.QUEUED,
            progress = 0.0,
            attempt = 0,
            failureCode = null,
            createdAt = now,
            updatedAt = now,
        )
        if (repository.insertIfAbsent(task)) return task
        return checkNotNull(repository.get(id)) { "Task was inserted concurrently but cannot be read: $id" }
    }

    suspend fun transition(
        id: TaskId,
        to: TaskState,
        failureCode: String? = null,
    ): PaperTask {
        val current = checkNotNull(repository.get(id)) { "Unknown task: $id" }
        TaskStateMachine.transition(current.state, to)
        require(to == TaskState.FAILED || failureCode == null) {
            "Only failed tasks can carry a failure code"
        }
        val updated = current.copy(
            state = to,
            progress = when (to) {
                TaskState.SUCCEEDED -> 1.0
                TaskState.QUEUED -> 0.0
                else -> current.progress
            },
            attempt = current.attempt + if (to == TaskState.RUNNING) 1 else 0,
            failureCode = failureCode,
            updatedAt = clock.instant(),
        )
        if (!repository.updateIfCurrent(updated, current)) {
            throw ConcurrentTaskUpdateException(id)
        }
        return updated
    }

    suspend fun updateProgress(id: TaskId, progress: Double): PaperTask {
        val current = checkNotNull(repository.get(id)) { "Unknown task: $id" }
        require(current.state == TaskState.RUNNING) { "Only running tasks report progress" }
        require(progress in current.progress..1.0) { "Task progress cannot move backwards" }
        val updated = current.copy(progress = progress, updatedAt = clock.instant())
        if (!repository.updateIfCurrent(updated, current)) {
            throw ConcurrentTaskUpdateException(id)
        }
        return updated
    }

    suspend fun cancel(id: TaskId): CancelTaskResult {
        while (true) {
            val current = repository.get(id) ?: return CancelTaskResult.NotFound
            if (current.state !in ACTIVE_STATES) {
                return CancelTaskResult.AlreadyTerminal(current)
            }
            val cancelled = current.copy(
                state = TaskState.CANCELLED,
                failureCode = null,
                updatedAt = clock.instant(),
            )
            if (repository.updateIfCurrent(cancelled, current)) {
                return CancelTaskResult.Cancelled(cancelled)
            }
        }
    }

    /** A user-requested retry starts a fresh attempt budget instead of inheriting exhausted retries. */
    suspend fun retry(id: TaskId): RetryTaskResult {
        while (true) {
            val current = repository.get(id) ?: return RetryTaskResult.NotFound
            if (current.state !in RETRYABLE_STATES) {
                return RetryTaskResult.NotRetryable(current)
            }
            val queued = current.copy(
                state = TaskState.QUEUED,
                progress = 0.0,
                attempt = 0,
                failureCode = null,
                updatedAt = clock.instant(),
            )
            if (repository.updateIfCurrent(queued, current)) {
                return RetryTaskResult.Retried(queued)
            }
        }
    }

    suspend fun remove(id: TaskId): RemoveTaskResult {
        while (true) {
            val current = repository.get(id) ?: return RemoveTaskResult.NotFound
            if (current.state in ACTIVE_STATES) return RemoveTaskResult.Active
            if (repository.deleteIfState(id, current.state)) return RemoveTaskResult.Removed
        }
    }

    suspend fun removeTerminal(id: TaskId): Boolean {
        return when (remove(id)) {
            RemoveTaskResult.Removed -> true
            RemoveTaskResult.NotFound -> false
            RemoveTaskResult.Active -> error("Cannot remove an active task: $id")
        }
    }

    private companion object {
        val ACTIVE_STATES = setOf(TaskState.QUEUED, TaskState.RUNNING)
        val RETRYABLE_STATES = setOf(TaskState.FAILED, TaskState.CANCELLED)
    }
}

class ConcurrentTaskUpdateException(id: TaskId) :
    IllegalStateException("Task changed concurrently: ${id.value}")
