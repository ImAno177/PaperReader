package dev.paperreader.logic.task

import dev.paperreader.logic.domain.WorkId
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

enum class TaskKind {
    DOWNLOAD,
    EXTRACTION,
}

data class PaperTask(
    val id: TaskId,
    val kind: TaskKind,
    val workId: WorkId?,
    val targetKey: String,
    val state: TaskState,
    val progress: Double,
    val attempt: Int,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(targetKey.isNotBlank())
        require(progress in 0.0..1.0)
        require(attempt >= 0)
        require(state == TaskState.FAILED || failureCode == null)
    }
}

interface PaperTaskRepository {
    val tasks: Flow<List<PaperTask>>

    suspend fun get(id: TaskId): PaperTask?

    suspend fun insertIfAbsent(task: PaperTask): Boolean

    suspend fun updateIfCurrent(task: PaperTask, expected: PaperTask): Boolean

    suspend fun deleteIfState(id: TaskId, expectedState: TaskState): Boolean
}

internal object TaskIdentity {
    fun id(kind: TaskKind, workId: WorkId?, targetKey: String): TaskId {
        val value = listOf(kind.name, workId?.value.orEmpty(), targetKey).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return TaskId("task-$hash")
    }
}
