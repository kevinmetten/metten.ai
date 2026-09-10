package com.mobileclaw.voice

import com.mobileclaw.agent.*
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoniqoConversationOwnershipTest {
    @Test fun `partial is diagnostic and one committed transcript creates one turn`() {
        val h = Harness(); h.start()
        h.input.emit(SpeechInputEvent.Partial("hel")); h.input.emit(SpeechInputEvent.Partial("hello"))
        assertEquals(0, h.brain.calls)
        h.input.emit(SpeechInputEvent.Final("hello")); h.scope.advanceUntilIdle()
        assertEquals(1, h.brain.calls); assertEquals(listOf("answer"), h.output.spoken)
    }

    @Test fun `confirmed barge in cancels exact output and final continues as next turn`() {
        val h = Harness(); h.start(); h.input.emit(SpeechInputEvent.Final("count")); h.scope.advanceUntilIdle()
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
        val oldCompletion = h.output.listeners.single()
        h.input.emit(SpeechInputEvent.OutputInterrupted)
        assertEquals(1, h.output.stops); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        h.input.emit(SpeechInputEvent.Final("please stop")); h.scope.advanceUntilIdle()
        assertEquals(2, h.brain.calls); assertEquals(2, h.output.spoken.size)
        oldCompletion(SpeechOutputEvent.Completed)
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
    }

    @Test fun `stale callback and mute cannot interrupt current output`() {
        val h = Harness(); h.start(); val stale = h.input.listener
        h.input.emit(SpeechInputEvent.Final("one")); h.scope.advanceUntilIdle(); h.output.complete()
        h.input.emit(SpeechInputEvent.Final("two")); h.scope.advanceUntilIdle()
        stale(SpeechInputEvent.OutputInterrupted)
        assertEquals(0, h.output.stops)
        h.voice.setMuted(true); h.input.emitDirect(SpeechInputEvent.OutputInterrupted)
        assertEquals(0, h.output.stops)
        h.voice.setMuted(false); h.input.emit(SpeechInputEvent.OutputInterrupted)
        assertEquals(1, h.output.stops)
    }

    @Test fun `barge in leaves phone control running`() {
        val h = Harness(); h.start(); h.brain.decision = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Settings"))
        h.input.emit(SpeechInputEvent.Final("open settings")); h.scope.advanceUntilIdle()
        val request = h.pendingRequestIds().single()
        h.emit(VoiceControlEvent.Accepted(1, request, "task"))
        val task = h.tasks.activeTasks.value.single().taskId
        h.input.emit(SpeechInputEvent.OutputInterrupted)
        assertNotNull(h.tasks.task(task)); assertEquals(VoicePhoneTaskState.RUNNING, h.coordinator.status.value.state)
    }

    @Test fun `end rejects capture and output callbacks and restart installs a clean generation`() {
        val h = Harness(); h.start(); val oldInput = h.input.listener
        h.input.emit(SpeechInputEvent.Final("one")); h.scope.advanceUntilIdle(); val oldOutput = h.output.listeners.single()
        h.voice.stop(); oldInput(SpeechInputEvent.Final("stale")); oldOutput(SpeechOutputEvent.Completed); h.scope.advanceUntilIdle()
        assertEquals(1, h.brain.calls); assertEquals(MettenVoicePhase.IDLE, h.voice.state.value.phase)
        h.start(); h.input.emit(SpeechInputEvent.Final("new")); h.scope.advanceUntilIdle()
        assertEquals(2, h.brain.calls)
    }

    private class Harness {
        val scope = TestScope(StandardTestDispatcher()); val input = ContinuousInput(); val output = Output(); val brain = Brain()
        val tasks = AgentTaskController()
        val coordinator = VoiceAgentCoordinator(scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY }) {
            CompletableDeferred<AgentResult>().await()
        }
        val voice = MettenVoiceSessionController(scope, { input }, { output }, brain, coordinator, { true }, { true })
        fun start() { assertTrue(voice.start()) }
        @Suppress("UNCHECKED_CAST")
        fun pendingRequestIds(): Set<String> {
            val field = MettenVoiceSessionController::class.java.getDeclaredField("pendingPhoneTurns").apply { isAccessible = true }
            return (field.get(voice) as Map<String, String>).keys
        }
        fun emit(event: VoiceControlEvent) {
            val method = MettenVoiceSessionController::class.java.getDeclaredMethod("onPhoneEvent", java.lang.Long.TYPE, VoiceControlEvent::class.java).apply { isAccessible = true }
            method.invoke(voice, event.generation, event)
        }
    }
    private class ContinuousInput : ContinuousSpeechInputEngine {
        lateinit var listener: (SpeechInputEvent) -> Unit
        override fun capability() = SpeechCapability(true)
        override fun startListening(listener: (SpeechInputEvent) -> Unit) { this.listener = listener }
        override fun stopListening() = Unit
        override fun release() = Unit
        fun emit(event: SpeechInputEvent) = listener(event)
        fun emitDirect(event: SpeechInputEvent) = listener(event)
    }
    private class Output : SpeechOutputEngine {
        val spoken = mutableListOf<String>(); val listeners = mutableListOf<(SpeechOutputEvent) -> Unit>(); var stops = 0
        override fun capability() = SpeechCapability(true)
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken += text; listeners += listener; listener(SpeechOutputEvent.Started) }
        override fun stop() { stops++ }
        override fun release() = Unit
        fun complete() = listeners.last()(SpeechOutputEvent.Completed)
    }
    private class Brain : VoiceTurnBrain {
        var calls = 0; var decision = VoiceTurnDecision("answer")
        override suspend fun decide(userText: String, context: VoiceTurnContext): VoiceTurnDecision { calls++; return decision }
    }
}
