package dev.paperreader.logic.data.repository

import dev.paperreader.logic.data.LibraryDatabase
import dev.paperreader.logic.data.TaskEntity
import dev.paperreader.logic.domain.WorkId
import dev.paperreader.logic.task.PaperTask
import dev.paperreader.logic.task.PaperTaskRepository
import dev.paperreader.logic.task.TaskId
import dev.paperreader.logic.task.TaskKind
import dev.paperreader.logic.task.TaskState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomTaskRepository(database: LibraryDatabase) : PaperTaskRepository {
    private val dao = database.taskDao()

    override val tasks: Flow<List<PaperTask>> = dao.observeTasks().map { rows -> rows.map(TaskEntity::toDomain) }

    override suspend fun get(id: TaskId): PaperTask? = dao.getTask(id.value)?.toDomain()

    override suspend fun insertIfAbsent(task: PaperTask): Boolean = dao.insertTask(task.toEntity()) != -1L

    override suspend fun updateIfCurrent(task: PaperTask, expected: PaperTask): Boolean = dao.updateIfState(
        id = task.id.value,
        expectedState = expected.state.name,
        expectedProgress = expected.progress,
        expectedAttempt = expected.attempt,
        expectedFailureCode = expected.failureCode,
        expectedUpdatedAtEpochMillis = expected.updatedAt.toEpochMilli(),
        newState = task.state.name,
        progress = task.progress,
        attempt = task.attempt,
        failureCode = task.failureCode,
        updatedAtEpochMillis = task.updatedAt.toEpochMilli(),
    ) == 1

    override suspend fun deleteIfState(id: TaskId, expectedState: TaskState): Boolean =
        dao.deleteIfState(id.value, expectedState.name) == 1
}

private fun TaskEntity.toDomain() = PaperTask(
    id = TaskId(id),
    kind = TaskKind.valueOf(kind),
    workId = workId?.let(::WorkId),
    targetKey = targetKey,
    state = TaskState.valueOf(state),
    progress = progress,
    attempt = attempt,
    failureCode = failureCode,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

private fun PaperTask.toEntity() = TaskEntity(
    id = id.value,
    kind = kind.name,
    workId = workId?.value,
    targetKey = targetKey,
    state = state.name,
    progress = progress,
    attempt = attempt,
    failureCode = failureCode,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)
