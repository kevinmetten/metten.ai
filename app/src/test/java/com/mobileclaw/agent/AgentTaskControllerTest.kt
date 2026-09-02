package com.mobileclaw.agent

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class AgentTaskControllerTest {
    private var id = 0
    private fun controller() = AgentTaskController(clock = { id.toLong() }, idFactory = { "id-${++id}" })

    @Test fun `registration running exact completion and state updates`() {
        val controller = controller()
        val registration = controller.register("s", TaskType.PHONE_CONTROL, true)
        assertEquals(1, controller.activeTasks.value.size)
        assertEquals(AgentTaskPhase.STARTING, controller.task(registration.taskId)?.phase)
        assertTrue(controller.markRunning(registration))
        assertEquals(AgentTaskPhase.RUNNING, controller.activeTasks.value.single().phase)
        assertEquals(registration.taskId, controller.notificationTarget()?.taskId)
        assertTrue(controller.markForegroundProtected(registration.taskId, true))
        assertTrue(controller.activeTasks.value.single().foregroundProtected)
        assertTrue(controller.complete(registration))
        assertTrue(controller.activeTasks.value.isEmpty())
        assertFalse(controller.complete(registration))
    }

    @Test fun `exact cancellation is idempotent and missing cancellation is harmless`() {
        val controller = controller()
        val registration = controller.register("s", TaskType.GENERAL, false)
        val job = Job()
        assertTrue(controller.attachJob(registration, job))
        assertTrue(controller.cancelTask(registration.taskId, AgentCancellationReason.USER_REQUEST))
        assertTrue(job.isCancelled)
        assertTrue(controller.cancelTask(registration.taskId, AgentCancellationReason.USER_REQUEST))
        assertFalse(controller.cancelTask("missing", AgentCancellationReason.USER_REQUEST))
    }

    @Test fun `sessions are independent and session cancellation targets its own task`() {
        val controller = controller()
        val a = controller.register("a", TaskType.PHONE_CONTROL, true)
        val b = controller.register("b", TaskType.PHONE_CONTROL, true)
        val aJob = Job()
        val bJob = Job()
        controller.attachJob(a, aJob)
        controller.attachJob(b, bJob)
        assertEquals(b.taskId, controller.notificationTarget()?.taskId)
        assertTrue(controller.cancelSession("a", AgentCancellationReason.SESSION_REPLACED))
        assertTrue(aJob.isCancelled)
        assertFalse(bJob.isCancelled)
        assertEquals(2, controller.activeTasks.value.count { it.foregroundRequested })
        controller.complete(a)
        assertEquals(b.taskId, controller.notificationTarget()?.taskId)
        assertFalse(controller.cancelSession("missing", AgentCancellationReason.USER_REQUEST))
    }

    @Test fun `superseded late completion cannot remove replacement`() {
        val controller = controller()
        val a = controller.register("s", TaskType.PHONE_CONTROL, true)
        val aJob = Job()
        controller.attachJob(a, aJob)
        val b = controller.register("s", TaskType.PHONE_CONTROL, true)
        assertTrue(aJob.isCancelled)
        assertEquals(AgentCancellationReason.SUPERSEDED_BY_NEW_TURN, controller.task(a.taskId)?.cancellationReason)
        assertTrue(controller.complete(a)) // A's own exact entry may finish without touching B.
        assertEquals(b.taskId, controller.sessionTask("s")?.taskId)
        assertEquals(b.taskId, controller.notificationTarget()?.taskId)
    }
}
