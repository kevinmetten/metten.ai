package com.mobileclaw.agent

import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCoordinatorTest {
    @Test fun `start reserves exactly one canonical phone worker and reports real completion`() {
        val h = Harness()
        h.send("one", VoiceControlCommand.Start("Open Settings")); h.send("two", VoiceControlCommand.Start("Open Clock")); h.scope.runCurrent()
        assertEquals(listOf("Open Settings"), h.goals)
        assertEquals(1, h.controller.activeTasks.value.size)
        assertTrue(h.events.any { it is VoiceControlEvent.Accepted })
        h.gates.single().complete(result(true, "Settings opened.")); h.scope.advanceUntilIdle()
        assertTrue(h.events.any { it is VoiceControlEvent.Completed && it.success && it.summary == "Settings opened." })
        assertTrue(h.controller.activeTasks.value.isEmpty())
    }

    @Test fun `replace waits for exact old completion and never overlaps`() {
        val h = Harness()
        h.send("old", VoiceControlCommand.Start("Open Clock")); h.scope.runCurrent()
        h.send("fix", VoiceControlCommand.Replace("Use the alarm row toggle")); h.scope.advanceUntilIdle()
        assertEquals(listOf("Open Clock", "Use the alarm row toggle"), h.goals)
        assertEquals(1, h.controller.activeTasks.value.size)
    }

    @Test fun `status cancel and End use exact Voice owner`() {
        val h = Harness()
        val chat = h.controller.register("chat", TaskType.CHAT, false)
        h.send("start", VoiceControlCommand.Start("Open Clock")); h.scope.runCurrent()
        h.send("status", VoiceControlCommand.Status); h.scope.advanceUntilIdle()
        assertTrue(h.events.any { it is VoiceControlEvent.Status && it.status.state == VoicePhoneTaskState.RUNNING })
        h.send("cancel", VoiceControlCommand.Cancel); h.scope.advanceUntilIdle()
        assertNotNull(h.controller.task(chat.taskId))
        assertTrue(h.controller.activeTasks.value.none { it.taskType == TaskType.PHONE_CONTROL })
    }

    @Test fun `new generation cancels prior owner and ignores stale request`() {
        val h = Harness()
        h.send("old", VoiceControlCommand.Start("Open Clock")); h.scope.runCurrent()
        h.coordinator.beginSession(2, h.sink); h.scope.advanceUntilIdle()
        h.coordinator.accept(VoiceControlRequest(1, "stale", VoiceControlCommand.Start("Open Maps"))); h.scope.advanceUntilIdle()
        assertEquals(listOf("Open Clock"), h.goals)
    }

    private class Harness {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController()
        val submissions = AgentTaskSubmissionService(controller, scope) {}
        val events = mutableListOf<VoiceControlEvent>()
        val sink = VoiceControlEventSink(events::add)
        val goals = mutableListOf<String>()
        val gates = mutableListOf<CompletableDeferred<AgentResult>>()
        val coordinator = VoiceAgentCoordinator(scope, controller, submissions, { ReadinessLevel.READY }) { goal ->
            goals += goal; CompletableDeferred<AgentResult>().also(gates::add).await()
        }.also { it.beginSession(1, sink) }
        fun send(id: String, command: VoiceControlCommand) = coordinator.accept(VoiceControlRequest(1, id, command))
    }
    private companion object { fun result(ok: Boolean, text: String) = AgentResult(ok, text, AgentContext("voice", "goal")) }
}
