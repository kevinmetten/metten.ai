package com.mobileclaw.agent

import com.mobileclaw.permission.DeviceCapability
import com.mobileclaw.permission.ReadinessLevel
import com.mobileclaw.realtime.RealtimeDelegationChannel
import com.mobileclaw.realtime.RealtimeDelegationRequest
import com.mobileclaw.realtime.RealtimeDelegationSink
import com.mobileclaw.realtime.RealtimeDelegationUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCoordinatorTest {
    @Test fun `different and duplicate concurrent starts admit exactly one worker and retain exact id`() {
        val h = Harness()
        h.start("a", "Open Spotify")
        h.start("a", "Open Spotify")
        h.start("b", "Open YouTube")
        h.scope.runCurrent()

        assertEquals(1, h.startedGoals.size)
        val task = h.controller.activeTasks.value.single()
        assertEquals(TaskType.PHONE_CONTROL, task.taskType)
        assertEquals(task.taskId, h.coordinator.status.value.taskId)
        assertEquals(VoicePhoneTaskState.RUNNING, h.coordinator.status.value.state)
        assertTrue(h.updates.any { "PHONE_BUSY" in it.text })

        h.gates.single().complete(result(true, "Spotify is open."))
        h.scope.advanceUntilIdle()
        assertEquals(VoicePhoneTaskState.SUCCEEDED, h.coordinator.status.value.state)
        assertTrue(h.updates.any { it.channel == RealtimeDelegationChannel.SPEAKABLE && "SUCCEEDED" in it.text })
    }

    @Test fun `unrelated UI phone owner is busy and is never cancelled`() {
        val h = Harness()
        val ui = h.controller.tryRegisterExclusiveTaskType("ui", TaskType.PHONE_CONTROL, true)
            as AgentTaskController.RegistrationAttempt.Registered
        h.start("voice", "Open Spotify")
        h.scope.advanceUntilIdle()
        assertTrue(h.startedGoals.isEmpty())
        assertNotNull(h.controller.task(ui.registration.taskId))
        assertNull(h.controller.task(ui.registration.taskId)?.cancellationReason)
        assertTrue(h.updates.single().text.contains("PHONE_BUSY"))
    }

    @Test fun `spoken cancel and End target only exact voice task while keeping sink usable`() {
        val h = Harness()
        h.start("start", "Open Spotify")
        h.scope.runCurrent()
        val exact = h.coordinator.status.value.taskId!!
        h.send("cancel", """{"op":"cancel_phone_task"}""")
        h.scope.advanceUntilIdle()
        assertNull(h.controller.task(exact))
        assertTrue(h.updates.any { "phone_task_cancelled" in it.text })

        h.start("next", "Open YouTube")
        h.scope.runCurrent()
        val next = h.coordinator.status.value.taskId!!
        h.coordinator.endSession(1)
        h.scope.advanceUntilIdle()
        assertNull(h.controller.task(next))
    }

    @Test fun `stale generation cannot cancel current task`() {
        val h = Harness()
        h.coordinator.beginSession(2, h.sink)
        h.send("start", """{"op":"start_phone_task","goal":"Open Maps"}""", generation = 2)
        h.scope.runCurrent()
        val exact = h.coordinator.status.value.taskId!!
        h.send("old", """{"op":"cancel_phone_task"}""", generation = 1)
        h.scope.advanceUntilIdle()
        assertNotNull(h.controller.task(exact))
        h.gates.single().complete(result(true, "Done"))
        h.scope.advanceUntilIdle()
    }

    @Test fun `replace cancels old before replacement starts and does not overlap`() {
        val h = Harness()
        h.start("old", "Open Spotify")
        h.scope.runCurrent()
        val oldId = h.coordinator.status.value.taskId!!
        h.send("replace", """{"op":"replace_phone_task","goal":"Open YouTube"}""")
        h.scope.advanceUntilIdle()
        assertNull(h.controller.task(oldId))
        assertEquals(listOf("Open Spotify", "Open YouTube"), h.startedGoals)
        assertEquals(1, h.controller.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        h.gates.last().complete(result(false, "Could not open YouTube\nsecret detail"))
        h.scope.advanceUntilIdle()
        assertEquals(VoicePhoneTaskState.FAILED, h.coordinator.status.value.state)
        assertTrue(h.coordinator.status.value.summary.length <= 240)
    }

    @Test fun `blocked readiness and malformed controls perform no Android action and leave control alive`() {
        for (blocked in listOf(DeviceCapability.PHONE_CONTROL, DeviceCapability.LONG_RUNNING_PHONE_CONTROL)) {
            val h = Harness(blocked)
            h.start("blocked", "Open Spotify")
            h.send("malformed", "{")
            h.send("unknown", """{"op":"not_supported"}""")
            h.scope.advanceUntilIdle()
            assertTrue(h.startedGoals.isEmpty())
            assertTrue(h.controller.activeTasks.value.isEmpty())
            assertTrue(h.updates.any { "PHONE_CONTROL_NOT_READY" in it.text })
            h.send("status", """{"op":"get_phone_task_status"}""")
            h.scope.advanceUntilIdle()
            assertTrue(h.updates.any { "phone_task_status" in it.text })
        }
    }

    @Test fun `teardown after reservation but before ownership publication prevents worker start`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val submissions = AgentTaskSubmissionService(controller, scope) {}
        val admissionReached = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        var workerRan = false
        val sink = RealtimeDelegationSink { true }
        val coordinator = VoiceAgentCoordinator(
            scope, controller, submissions, { ReadinessLevel.READY },
            beforeOwnershipPublication = { admissionReached.complete(Unit); releaseAdmission.await() },
        ) { workerRan = true; result(true, "unexpected") }
        coordinator.beginSession(1, sink)
        coordinator.accept(RealtimeDelegationRequest(1, "start", """{"op":"start_phone_task","goal":"Open Spotify"}"""))
        scope.runCurrent()
        assertTrue(admissionReached.isCompleted)
        coordinator.endSession(1)
        releaseAdmission.complete(Unit)
        scope.advanceUntilIdle()
        assertFalse(workerRan)
        assertTrue(controller.activeTasks.value.isEmpty())
        assertEquals(VoicePhoneTaskState.IDLE, coordinator.status.value.state)
    }

    @Test fun `new generation cancels exact prior voice owner and stale completion cannot publish`() {
        val h = Harness()
        h.start("old", "Open Spotify")
        h.scope.runCurrent()
        val oldId = h.coordinator.status.value.taskId!!
        val updateCount = h.updates.size
        h.coordinator.beginSession(2, h.sink)
        h.scope.advanceUntilIdle()
        assertNull(h.controller.task(oldId))
        assertEquals(VoicePhoneTaskState.IDLE, h.coordinator.status.value.state)
        assertEquals(updateCount, h.updates.size)
        h.send("new", """{"op":"start_phone_task","goal":"Open YouTube"}""", generation = 2)
        h.scope.runCurrent()
        assertEquals("Open YouTube", h.startedGoals.last())
        h.gates.last().complete(result(true, "YouTube is open"))
        h.scope.advanceUntilIdle()
    }

    private class Harness(private val blocked: DeviceCapability? = null) {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val submissions = AgentTaskSubmissionService(controller, scope) {}
        val updates = mutableListOf<RealtimeDelegationUpdate>()
        val sink = RealtimeDelegationSink { update -> updates += update; true }
        val gates = mutableListOf<CompletableDeferred<AgentResult>>()
        val startedGoals = mutableListOf<String>()
        val coordinator = VoiceAgentCoordinator(
            scope, controller, submissions,
            { if (it == blocked) ReadinessLevel.BLOCKED else ReadinessLevel.READY },
        ) { goal ->
            startedGoals += goal
            CompletableDeferred<AgentResult>().also(gates::add).await()
        }.also { it.beginSession(1, sink) }

        fun start(id: String, goal: String) = send(id, """{"op":"start_phone_task","goal":"$goal"}""")
        fun send(id: String, text: String, generation: Long = 1) = coordinator.accept(RealtimeDelegationRequest(generation, id, text))
    }

    private companion object {
        fun result(success: Boolean, summary: String) = AgentResult(success, summary, AgentContext("runtime", "goal"))
    }
}
