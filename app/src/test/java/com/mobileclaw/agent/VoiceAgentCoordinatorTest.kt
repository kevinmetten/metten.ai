package com.mobileclaw.agent

import com.mobileclaw.permission.DeviceCapability
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentCoordinatorTest {
    @Test fun `duplicate and concurrent starts admit exactly one worker`() {
        val h = Harness(); h.start("a", "Open Spotify"); h.start("a", "Open Spotify"); h.start("b", "Open YouTube"); h.scope.runCurrent()
        assertEquals(listOf("Open Spotify"), h.goals)
        assertEquals(1, h.controller.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        assertTrue(h.events.any { it is VoiceControlEvent.Rejected && it.reason == "PHONE_BUSY" })
        h.gates.single().complete(result(true, "Spotify is open.")); h.scope.advanceUntilIdle()
        assertTrue(h.events.any { it is VoiceControlEvent.Completed && it.state == VoiceControlTerminalState.SUCCEEDED })
    }

    @Test fun `unrelated UI phone owner is busy and never cancelled`() {
        val h = Harness(); val ui = h.controller.tryRegisterExclusiveTaskType("ui", TaskType.PHONE_CONTROL, true) as AgentTaskController.RegistrationAttempt.Registered
        h.start("voice", "Open Spotify"); h.scope.advanceUntilIdle()
        assertTrue(h.goals.isEmpty()); assertNotNull(h.controller.task(ui.registration.taskId)); assertNull(h.controller.task(ui.registration.taskId)?.cancellationReason)
    }

    @Test fun `stale generation cannot cancel current phone work`() {
        val h = Harness(); h.coordinator.beginSession(2, h.sink); h.send("start", VoiceControlCommand.Start("Open Maps"), 2); h.scope.runCurrent()
        val exact = h.coordinator.status.value.taskId!!; h.send("old", VoiceControlCommand.Cancel, 1); h.scope.advanceUntilIdle()
        assertNotNull(h.controller.task(exact))
    }

    @Test fun `both phone readiness capabilities block without Android action`() {
        for (blocked in listOf(DeviceCapability.PHONE_CONTROL, DeviceCapability.LONG_RUNNING_PHONE_CONTROL)) {
            val h = Harness(blocked); h.start("blocked", "Open Spotify"); h.scope.advanceUntilIdle()
            assertTrue(h.goals.isEmpty()); assertTrue(h.controller.activeTasks.value.isEmpty())
            assertTrue(h.events.single() is VoiceControlEvent.Rejected)
        }
    }

    @Test fun `teardown after reservation before ownership publication prevents worker start`() {
        val scope = TestScope(StandardTestDispatcher()); val controller = AgentTaskController(); val admission = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var ran = false
        val coordinator = VoiceAgentCoordinator(scope, controller, AgentTaskSubmissionService(controller, scope) {}, { ReadinessLevel.READY }, { admission.complete(Unit); release.await() }) { ran = true; result(true, "unexpected") }
        coordinator.beginSession(1, VoiceControlEventSink { }); coordinator.accept(VoiceControlRequest(1, "start", VoiceControlCommand.Start("Open Spotify"))); scope.runCurrent()
        assertTrue(admission.isCompleted); coordinator.endSession(1); release.complete(Unit); scope.advanceUntilIdle()
        assertFalse(ran); assertTrue(controller.activeTasks.value.isEmpty())
    }

    @Test fun `new generation cancels exact prior and stale completion cannot publish`() {
        val h = Harness(); h.start("old", "Open Spotify"); h.scope.runCurrent(); val oldId = h.coordinator.status.value.taskId!!; val count = h.events.size
        h.coordinator.beginSession(2, h.sink); h.scope.advanceUntilIdle()
        assertNull(h.controller.task(oldId)); assertEquals(count, h.events.size); assertEquals(VoicePhoneTaskState.IDLE, h.coordinator.status.value.state)
    }

    @Test fun `replace waits for old settlement never overlaps and suppresses superseded terminal event`() {
        val h = Harness(); h.start("old", "Open Clock"); h.scope.runCurrent(); val oldId = h.coordinator.status.value.taskId!!
        h.send("fix", VoiceControlCommand.Replace("Use alarm row toggle")); h.scope.advanceUntilIdle()
        assertNull(h.controller.task(oldId)); assertEquals(listOf("Open Clock", "Use alarm row toggle"), h.goals)
        assertEquals(1, h.controller.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        assertFalse(h.events.any { it is VoiceControlEvent.Completed && it.cancellation == VoiceControlCancellationDisposition.SUPERSEDED })
    }

    @Test fun `cancel emits exactly one cancellation and leaves unrelated chat untouched`() {
        val h = Harness(); val chat = h.controller.register("chat", TaskType.CHAT, false); h.start("start", "Open Clock"); h.scope.runCurrent()
        h.send("cancel", VoiceControlCommand.Cancel); h.scope.advanceUntilIdle()
        assertNotNull(h.controller.task(chat.taskId))
        assertEquals(1, h.events.count { it is VoiceControlEvent.Completed && it.state == VoiceControlTerminalState.CANCELLED })
    }

    @Test fun `sink callback occurs outside coordinator lock`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val controller = AgentTaskController(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val ended = CountDownLatch(1)
        val coordinator = VoiceAgentCoordinator(scope, controller, AgentTaskSubmissionService(controller, scope) {}, { ReadinessLevel.READY }) { CompletableDeferred<AgentResult>().await() }
        coordinator.beginSession(1, VoiceControlEventSink { entered.countDown(); release.await(2, TimeUnit.SECONDS) })
        coordinator.accept(VoiceControlRequest(1, "start", VoiceControlCommand.Start("Open Clock")))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread { coordinator.endSession(1); ended.countDown() }.start()
        assertTrue("endSession must not wait for a blocked sink", ended.await(1, TimeUnit.SECONDS)); release.countDown()
    }

    private class Harness(private val blocked: DeviceCapability? = null) {
        val scope = TestScope(StandardTestDispatcher()); val controller = AgentTaskController(); val events = mutableListOf<VoiceControlEvent>(); val sink = VoiceControlEventSink { events += it }
        val goals = mutableListOf<String>(); val gates = mutableListOf<CompletableDeferred<AgentResult>>()
        val coordinator = VoiceAgentCoordinator(scope, controller, AgentTaskSubmissionService(controller, scope) {}, { if (it == blocked) ReadinessLevel.BLOCKED else ReadinessLevel.READY }) { goal -> goals += goal; CompletableDeferred<AgentResult>().also(gates::add).await() }.also { it.beginSession(1, sink) }
        fun start(id: String, goal: String) = send(id, VoiceControlCommand.Start(goal))
        fun send(id: String, command: VoiceControlCommand, generation: Long = 1) = coordinator.accept(VoiceControlRequest(generation, id, command))
    }
    private companion object { fun result(ok: Boolean, text: String) = AgentResult(ok, text, AgentContext("voice", "goal")) }
}
