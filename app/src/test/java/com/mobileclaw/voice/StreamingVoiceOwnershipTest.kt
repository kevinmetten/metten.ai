package com.mobileclaw.voice

import com.mobileclaw.agent.*
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingVoiceOwnershipTest {
    @Test fun `three interruptions cancel exact generation and never resume old tails`() {
        val h = Harness(); h.voice.start()
        repeat(3) { n ->
            h.input.listener(SpeechInputEvent.Final("question $n")); h.scope.runCurrent()
            val output = h.output.streams.last()
            assertEquals(listOf("First sentence. "), output.chunks)
            assertFalse(output.finished)
            assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
            h.input.listener(SpeechInputEvent.OutputInterrupted)
            h.input.listener(SpeechInputEvent.OutputInterrupted)
            h.scope.runCurrent()
            assertTrue(output.cancelled)
            assertEquals(n + 1, h.brain.cancelled)
            h.brain.gates.last().complete(Unit); h.scope.runCurrent()
            assertEquals(listOf("First sentence. "), output.chunks)
            output.listener(SpeechOutputEvent.Completed)
            assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        }
        h.input.listener(SpeechInputEvent.Final("earthquakes")); h.scope.runCurrent()
        h.brain.gates.last().complete(Unit); h.scope.runCurrent()
        val replacement = h.output.streams.last()
        assertEquals(listOf("First sentence. ", "Final sentence."), replacement.chunks)
        assertTrue(replacement.finished)
        assertEquals(4, h.brain.calls)
        h.voice.stop()
    }
    @Test fun `teardown cancels session network and queued speech but not unrelated scope work`() {
        val h = Harness(); h.voice.start()
        var unrelatedCancelled = false
        val unrelated = h.scope.launch { try { awaitCancellation() } finally { unrelatedCancelled = true } }
        h.input.listener(SpeechInputEvent.Final("question")); h.scope.runCurrent()
        h.voice.stop(); h.scope.runCurrent()
        assertEquals(1, h.brain.cancelled)
        assertTrue(h.output.streams.single().cancelled)
        assertFalse(unrelatedCancelled)
        unrelated.cancel(); h.scope.runCurrent()
    }
    @Test fun `streamed conversation interruption leaves PHONE_CONTROL worker running`() {
        val h = Harness(); h.voice.start()
        h.brain.phone = true
        h.input.listener(SpeechInputEvent.Final("Open Settings")); h.scope.runCurrent()
        val task = h.tasks.activeTasks.value.single().taskId
        h.output.ordinary!!(SpeechOutputEvent.Completed)
        h.brain.phone = false
        h.input.listener(SpeechInputEvent.Final("explain volcanoes")); h.scope.runCurrent()
        h.input.listener(SpeechInputEvent.OutputInterrupted); h.scope.runCurrent()
        assertEquals(AgentTaskPhase.RUNNING, h.tasks.task(task)?.phase)
        assertEquals(VoicePhoneTaskState.RUNNING, h.coordinator.status.value.state)
        h.voice.stop(); h.scope.runCurrent()
    }
    private class Harness {
        val scope = TestScope(StandardTestDispatcher())
        val input = Input(); val output = Output(); val brain = Brain(); val tasks = AgentTaskController()
        val coordinator = VoiceAgentCoordinator(scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY }) { awaitCancellation() }
        val voice = MettenVoiceSessionController(scope, { input }, { output }, brain, coordinator, { true }, { true })
    }
    private class Input : ContinuousSpeechInputEngine {
        lateinit var listener: (SpeechInputEvent) -> Unit
        override fun capability() = SpeechCapability(true)
        override fun startListening(listener: (SpeechInputEvent) -> Unit) { this.listener = listener }
        override fun stopListening() = Unit
        override fun release() = Unit
    }
    private class Output : StreamingSpeechOutputEngine {
        val streams = mutableListOf<Stream>()
        var ordinary: ((SpeechOutputEvent) -> Unit)? = null
        override fun capability() = SpeechCapability(true)
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { ordinary = listener }
        override fun beginStream(listener: (SpeechOutputEvent) -> Unit) = Stream(listener).also(streams::add)
        override fun stop() { streams.lastOrNull()?.cancel() }
        override fun release() = stop()
    }
    private class Stream(val listener: (SpeechOutputEvent) -> Unit) : SpeechTextStream {
        val chunks = mutableListOf<String>(); var cancelled = false; var finished = false
        override fun append(text: String) { if (!cancelled) chunks += text }
        override fun finish() { if (!cancelled) finished = true }
        override fun cancel() { cancelled = true }
    }
    private class Brain : VoiceTurnBrain {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var calls = 0; var cancelled = 0; var phone = false
        override suspend fun decide(userText: String, context: VoiceTurnContext) = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Settings"))
        override suspend fun decideStreaming(userText: String, context: VoiceTurnContext, onSpeech: (String) -> Unit): VoiceTurnDecision {
            if (phone) return decide(userText, context)
            calls++
            try {
                onSpeech("First sentence. ")
                CompletableDeferred<Unit>().also(gates::add).await()
                onSpeech("Final sentence.")
                return VoiceTurnDecision("First sentence. Final sentence.")
            } catch (failure: CancellationException) { cancelled++; throw failure }
        }
    }
}
