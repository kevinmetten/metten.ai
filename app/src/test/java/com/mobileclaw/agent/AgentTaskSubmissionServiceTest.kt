package com.mobileclaw.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTaskSubmissionServiceTest {
    @Test fun `shared path registers runs protects and completes exact task`() {
        val scope = TestScope(StandardTestDispatcher())
        var nextId = 0
        val controller = AgentTaskController(idFactory = { (++nextId).toString() })
        val protected = mutableListOf<String>()
        val service = AgentTaskSubmissionService(controller, scope, protected::add)
        val handle = service.submit(AgentTaskSubmissionRequest("session", TaskType.PHONE_CONTROL, true) { "done" })
        assertEquals(AgentTaskPhase.STARTING, controller.task(handle.taskId)?.phase)
        scope.advanceUntilIdle()
        assertEquals(listOf(handle.taskId), protected)
        assertNull(controller.task(handle.taskId))
        assertEquals(AgentTaskCompletion.Succeeded("done"), handle.completion.getCompleted())
    }

    @Test fun `cancel exact task cancels attached worker as cancellation`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val gate = CompletableDeferred<Unit>()
        val service = AgentTaskSubmissionService(controller, scope) {}
        val handle = service.submit(AgentTaskSubmissionRequest("voice", TaskType.PHONE_CONTROL) { gate.await() })
        scope.runCurrent()
        assertTrue(controller.cancelTask(handle.taskId, AgentCancellationReason.USER_REQUEST))
        scope.advanceUntilIdle()
        assertEquals(AgentTaskCompletion.Cancelled, handle.completion.getCompleted())
        assertNull(controller.task(handle.taskId))
    }
}
