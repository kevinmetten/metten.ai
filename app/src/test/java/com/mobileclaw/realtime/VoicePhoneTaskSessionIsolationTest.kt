package com.mobileclaw.realtime

import com.mobileclaw.agent.AgentContext
import com.mobileclaw.agent.AgentResult
import com.mobileclaw.agent.AgentTaskController
import com.mobileclaw.agent.AgentTaskSubmissionService
import com.mobileclaw.agent.VoiceAgentCoordinator
import com.mobileclaw.agent.VoicePhoneTaskState
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePhoneTaskSessionIsolationTest {
    @Test fun `long phone progress and success never stop connected Voice`() = verifyCompletion(success = true)

    @Test fun `phone task failure never stops connected Voice`() = verifyCompletion(success = false)

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
        override fun bindControl(generation: Long, listener: (RealtimeDelegationRequest) -> Unit) { delegation = listener }
        override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) = Unit
        override fun send(update: RealtimeDelegationUpdate): Boolean = updates.add(update)
        override fun setMuted(muted: Boolean) = Unit
        override fun close() { closeCount++ }
    }
}
