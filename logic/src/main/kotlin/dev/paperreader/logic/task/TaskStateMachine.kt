package dev.paperreader.logic.task

enum class TaskState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

object TaskStateMachine {
    private val allowed = mapOf(
        TaskState.QUEUED to setOf(TaskState.RUNNING, TaskState.CANCELLED),
        TaskState.RUNNING to setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED),
        TaskState.FAILED to setOf(TaskState.QUEUED, TaskState.CANCELLED),
        TaskState.SUCCEEDED to emptySet(),
        TaskState.CANCELLED to setOf(TaskState.QUEUED),
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean = to in allowed.getValue(from)

    fun transition(from: TaskState, to: TaskState): TaskState {
        require(canTransition(from, to)) { "Invalid task transition: $from -> $to" }
        return to
    }
}
