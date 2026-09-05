package com.mobileclaw.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
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
        assertEquals(AgentTaskPhase.RUNNING, controller.task(handle.taskId)?.phase)
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

    @Test fun `ordinary worker failure is reported after exact registration completes`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        val handle = service.submit(AgentTaskSubmissionRequest("ui", TaskType.GENERAL) { error("worker broke") })
        scope.advanceUntilIdle()
        assertEquals(AgentTaskCompletion.Failed("worker broke"), handle.completion.getCompleted())
        assertNull(controller.task(handle.taskId))
    }

    @Test fun `competing exclusive phone submissions cannot both launch`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        var launches = 0
        val gate = CompletableDeferred<Unit>()
        val first = service.trySubmitExclusive(AgentTaskSubmissionRequest("voice-a", TaskType.PHONE_CONTROL) { launches++; gate.await() })
        val second = service.trySubmitExclusive(AgentTaskSubmissionRequest("voice-b", TaskType.PHONE_CONTROL) { launches++; Unit })
        assertTrue(first is ExclusiveSubmissionResult.Reserved)
        assertTrue(second is ExclusiveSubmissionResult.Busy)
        scope.runCurrent()
        assertEquals(1, launches)
        assertEquals(1, controller.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        val firstTask = (first as ExclusiveSubmissionResult.Reserved<Unit>).task
        controller.cancelTask(firstTask.taskId, AgentCancellationReason.USER_REQUEST)
        scope.advanceUntilIdle()
    }

    @Test fun `cancellation before prepared lazy worker starts settles and releases registration`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        var workerRan = false
        val reserved = service.reserveExclusive(AgentTaskSubmissionRequest("voice", TaskType.PHONE_CONTROL) { workerRan = true })
            as ExclusiveSubmissionResult.Reserved<Unit>
        assertTrue(reserved.task.cancel(AgentCancellationReason.USER_REQUEST))
        assertFalse(reserved.task.start())
        scope.advanceUntilIdle()
        assertFalse(workerRan)
        assertEquals(AgentTaskCompletion.Cancelled, reserved.task.completion.getCompleted())
        assertNull(controller.task(reserved.task.taskId))
    }

    @Test fun `cancelling registration cannot be revived to running`() {
        val controller = AgentTaskController()
        val registration = controller.register("session", TaskType.PHONE_CONTROL, true)
        assertTrue(controller.cancelTask(registration.taskId, AgentCancellationReason.USER_REQUEST))
        assertEquals(AgentTaskPhase.CANCELLING, controller.task(registration.taskId)?.phase)
        assertFalse(controller.markRunning(registration))
        assertEquals(AgentTaskPhase.CANCELLING, controller.task(registration.taskId)?.phase)
    }

    @Test fun `same session replacement waits for cancelling predecessor then reserves without overlap`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        val old = controller.tryRegisterExclusiveTaskType("same", TaskType.PHONE_CONTROL, true)
            as AgentTaskController.RegistrationAttempt.Registered
        controller.cancelTask(old.registration.taskId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        var result: ExclusiveSubmissionResult<Unit>? = null
        scope.launch {
            result = service.reserveExclusiveAfterSameSessionRelease(
                AgentTaskSubmissionRequest("same", TaskType.PHONE_CONTROL) {},
            ) { true }
        }
        scope.runCurrent()
        assertNull(result)
        controller.complete(old.registration)
        scope.runCurrent()
        assertTrue(result is ExclusiveSubmissionResult.Reserved)
        assertEquals(1, controller.activeTasks.value.size)
        (result as ExclusiveSubmissionResult.Reserved<Unit>).task.cancel(AgentCancellationReason.USER_REQUEST)
        scope.advanceUntilIdle()
    }

    @Test fun `cancelled pending replacement cannot acquire after predecessor releases`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        val old = controller.tryRegisterExclusiveTaskType("same", TaskType.PHONE_CONTROL, true)
            as AgentTaskController.RegistrationAttempt.Registered
        controller.cancelTask(old.registration.taskId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        var reserved = false
        val waiting = scope.launch {
            reserved = service.reserveExclusiveAfterSameSessionRelease(
                AgentTaskSubmissionRequest("same", TaskType.PHONE_CONTROL) {},
            ) { true } is ExclusiveSubmissionResult.Reserved
        }
        scope.runCurrent()
        waiting.cancel()
        controller.complete(old.registration)
        scope.advanceUntilIdle()
        assertFalse(reserved)
        assertTrue(controller.activeTasks.value.isEmpty())
    }

    @Test fun `newer pending replacement supersedes cancelled waiter and alone acquires`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val service = AgentTaskSubmissionService(controller, scope) {}
        val old = controller.tryRegisterExclusiveTaskType("same", TaskType.PHONE_CONTROL, true)
            as AgentTaskController.RegistrationAttempt.Registered
        controller.cancelTask(old.registration.taskId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        var b: ExclusiveSubmissionResult<Unit>? = null
        var c: ExclusiveSubmissionResult<Unit>? = null
        val waiterB = scope.launch { b = service.reserveExclusiveAfterSameSessionRelease(AgentTaskSubmissionRequest("same", TaskType.PHONE_CONTROL) {}) { true } }
        scope.runCurrent()
        waiterB.cancel()
        scope.launch { c = service.reserveExclusiveAfterSameSessionRelease(AgentTaskSubmissionRequest("same", TaskType.PHONE_CONTROL) {}) { true } }
        scope.runCurrent()
        controller.complete(old.registration)
        scope.runCurrent()
        assertNull(b)
        assertTrue(c is ExclusiveSubmissionResult.Reserved)
        (c as ExclusiveSubmissionResult.Reserved<Unit>).task.cancel(AgentCancellationReason.USER_REQUEST)
        scope.advanceUntilIdle()
    }
}
