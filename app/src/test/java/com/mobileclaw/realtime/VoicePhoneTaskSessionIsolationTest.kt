package com.mobileclaw.realtime

import com.mobileclaw.agent.AgentContext
import com.mobileclaw.agent.AgentResult
import com.mobileclaw.agent.AgentTaskController
import com.mobileclaw.agent.AgentTaskSubmissionService
import com.mobileclaw.agent.VoiceAgentCoordinator
import com.mobileclaw.agent.VoicePhoneTaskState
import com.mobileclaw.agent.TaskType
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePhoneTaskSessionIsolationTest {
    @Test fun `long phone progress and success never stop connected Voice`() = verifyCompletion(success = true)

    @Test fun `phone task failure never stops connected Voice`() = verifyCompletion(success = false)

    @Test fun `auxiliary close preserves running phone work then peer failure ends exact generation once`() {
        val harness = RunningPhoneHarness()
        harness.startPhoneTask()

        harness.transport.nativeEvent(RealtimeNativeTransportEvent.EVENTS_DATACHANNEL_CLOSED)
        harness.scope.runCurrent()
        assertEquals(0, harness.transport.disconnectCount)
        assertEquals(RealtimeVoicePhase.CONNECTED, harness.controller.state.value.phase)
        assertEquals(VoicePhoneTaskState.RUNNING, harness.coordinator.status.value.state)
        assertEquals(1, harness.tasks.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })

        harness.transport.nativeEvent(RealtimeNativeTransportEvent.WEBRTC_PEER_FAILED)
        harness.transport.nativeEvent(RealtimeNativeTransportEvent.WEBRTC_PEER_CLOSED)
        harness.scope.advanceUntilIdle()
        assertEquals(1, harness.transport.disconnectCount)
        assertEquals(RealtimeVoicePhase.FAILED, harness.controller.state.value.phase)
        assertTrue(harness.tasks.activeTasks.value.none { it.taskType == TaskType.PHONE_CONTROL })
    }

    @Test fun `local End stays idle when delayed native closed callbacks arrive`() {
        val harness = RunningPhoneHarness()
        harness.startPhoneTask()

        harness.controller.stop()
        harness.transport.nativeEvent(RealtimeNativeTransportEvent.EVENTS_DATACHANNEL_CLOSED)
        harness.transport.nativeEvent(RealtimeNativeTransportEvent.WEBRTC_PEER_CLOSED)
        harness.scope.advanceUntilIdle()

        assertEquals(0, harness.transport.disconnectCount)
        assertEquals(RealtimeVoicePhase.IDLE, harness.controller.state.value.phase)
        assertEquals(RealtimeVoiceRuntimeReason.USER_STOPPED, RealtimeVoiceRuntimeDiagnostics.state.value.lastReason)
        assertEquals(true, RealtimeVoiceRuntimeDiagnostics.state.value.terminalWasLocal)
    }

    private fun verifyCompletion(success: Boolean) {
        val scope = TestScope(StandardTestDispatcher())
        val taskController = AgentTaskController()
        val completion = CompletableDeferred<AgentResult>()
        val progress = mutableListOf<String>()
        val coordinator = VoiceAgentCoordinator(
            scope,
            taskController,
            AgentTaskSubmissionService(taskController, scope) {},
            { ReadinessLevel.READY },
        ) {
            progress += listOf("OBSERVING", "SCROLLING", "OBSERVING", "TAPPING", "OBSERVING", "SCROLLING", "SCROLLING", "VERIFYING")
            completion.await()
        }
        lateinit var transport: ControlTransport
        val controller = ChatGptRealtimeSessionController(scope, { ControlTransport().also { transport = it } }, coordinator)
        controller.start()
        scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.CONNECTED, controller.state.value.phase)

        transport.delegation(RealtimeDelegationRequest(1, "d1", "Scroll down to the bottom and identify the last app"))
        scope.runCurrent()
        assertEquals(VoicePhoneTaskState.RUNNING, coordinator.status.value.state)
        assertEquals(RealtimeVoicePhase.CONNECTED, controller.state.value.phase)
        assertEquals(8, progress.size)

        completion.complete(AgentResult(success, if (success) "Completed." else "Could not complete.", AgentContext("runtime", "goal")))
        scope.advanceUntilIdle()
        assertEquals(if (success) VoicePhoneTaskState.SUCCEEDED else VoicePhoneTaskState.FAILED, coordinator.status.value.state)
        assertEquals(RealtimeVoicePhase.CONNECTED, controller.state.value.phase)
        assertTrue(transport.updates.any { "phone_task_result" in it.text })
        assertEquals(0, transport.closeCount)
    }

    private class ControlTransport : RealtimeControlTransport {
        lateinit var delegation: (RealtimeDelegationRequest) -> Unit
        val updates = mutableListOf<RealtimeDelegationUpdate>()
        var closeCount = 0
        var disconnectCount = 0
        private val nativeEvents = RealtimeVoiceNativeEventGate()
        private var disconnected: ((RealtimeVoiceException?) -> Unit)? = null
        override fun bindControl(generation: Long, listener: (RealtimeDelegationRequest) -> Unit) { delegation = listener }
        override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) { disconnected = onDisconnected }
        override fun send(update: RealtimeDelegationUpdate): Boolean = updates.add(update)
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { closeCount++; nativeEvents.beginLocalClose() }
        fun nativeEvent(event: RealtimeNativeTransportEvent) {
            val reason = nativeEvents.accept(event) ?: return
            disconnectCount++
            disconnected?.invoke(RealtimeVoiceException(
                if (reason == RealtimeVoiceRuntimeReason.WEBRTC_PEER_CLOSED) RealtimeVoiceDiagnostic.REMOTE_CLOSED else RealtimeVoiceDiagnostic.NETWORK_FAILED,
                "Native transport ended.",
            ))
        }
    }

    private class RunningPhoneHarness {
        val scope = TestScope(StandardTestDispatcher())
        val tasks = AgentTaskController()
        val completion = CompletableDeferred<AgentResult>()
        val coordinator = VoiceAgentCoordinator(
            scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY },
        ) { completion.await() }
        lateinit var transport: ControlTransport
        val controller = ChatGptRealtimeSessionController(scope, { ControlTransport().also { transport = it } }, coordinator)

        fun startPhoneTask() {
            controller.start()
            scope.advanceUntilIdle()
            transport.delegation(RealtimeDelegationRequest(1, "d1", "Open Android Settings"))
            scope.runCurrent()
            assertEquals(RealtimeVoicePhase.CONNECTED, controller.state.value.phase)
            assertEquals(VoicePhoneTaskState.RUNNING, coordinator.status.value.state)
        }
    }
}
