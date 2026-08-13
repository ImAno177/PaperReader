package dev.paperreader.logic.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test
    fun `normal task lifecycle succeeds`() {
        val running = TaskStateMachine.transition(TaskState.QUEUED, TaskState.RUNNING)
        val done = TaskStateMachine.transition(running, TaskState.SUCCEEDED)

        assertEquals(TaskState.SUCCEEDED, done)
    }

    @Test
    fun `failed and cancelled tasks may be queued again`() {
        assertTrue(TaskStateMachine.canTransition(TaskState.FAILED, TaskState.QUEUED))
        assertTrue(TaskStateMachine.canTransition(TaskState.CANCELLED, TaskState.QUEUED))
    }

    @Test
    fun `terminal success cannot silently restart`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskStateMachine.transition(TaskState.SUCCEEDED, TaskState.QUEUED)
        }
    }
}
