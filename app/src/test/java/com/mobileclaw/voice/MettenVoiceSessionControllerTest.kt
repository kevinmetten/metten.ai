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
class MettenVoiceSessionControllerTest {
    @Test fun `start end and mute own speech resources without cancelling phone work on mute`() {
        val h = Harness()
        assertTrue(h.voice.start()); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase); assertEquals(listOf(true), h.fgs)
        h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.advanceUntilIdle()
        assertEquals(1, h.goals.size)
        h.output.complete(); h.voice.setMuted(true)
        assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase); assertEquals(1, h.tasks.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        h.voice.setMuted(false); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        h.voice.stop(); h.scope.advanceUntilIdle()
        assertTrue(h.input.released); assertTrue(h.output.released); assertEquals(MettenVoicePhase.IDLE, h.voice.state.value.phase); assertEquals(false, h.fgs.last())
    }

    @Test fun `ordinary conversation speaks and leaves running phone task untouched`() {
        val h = Harness()
        h.voice.start(); h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.advanceUntilIdle(); h.output.complete()
        val taskId = h.tasks.activeTasks.value.single().taskId
        h.brain.next = VoiceTurnDecision("Ten.")
        h.input.emit(SpeechInputEvent.Final("What is five plus five?")); h.scope.advanceUntilIdle()
        assertEquals("Ten.", h.output.spoken.last()); assertNotNull(h.tasks.task(taskId)); assertEquals(1, h.goals.size)
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
        h.output.complete(); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `real phone success and failure are spoken while Voice stays alive`() {
        val h = Harness(); h.voice.start(); h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.advanceUntilIdle()
        assertFalse(h.output.spoken.any { it.contains("opened", true) })
        h.output.complete(); h.gates.removeFirst().complete(result(true, "Settings opened.")); h.scope.advanceUntilIdle()
        assertEquals("Settings opened.", h.output.spoken.last()); h.output.complete()
        h.brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Clock")); h.input.emit(SpeechInputEvent.Final("Open Clock")); h.scope.advanceUntilIdle(); h.output.complete()
        h.gates.removeFirst().complete(result(false, "Clock could not be opened.")); h.scope.advanceUntilIdle()
        assertTrue(h.output.spoken.last().startsWith("The phone task did not complete:")); h.output.complete(); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `correction replaces running task without overlap`() {
        val h = Harness(); h.voice.start(); h.input.emit(SpeechInputEvent.Final("Open Clock")); h.scope.advanceUntilIdle(); h.output.complete()
        h.brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Replace("Use the toggle on the alarm row"))
        h.input.emit(SpeechInputEvent.Final("No use the toggle")); h.scope.advanceUntilIdle()
        assertEquals(listOf("Open Settings", "Use the toggle on the alarm row"), h.goals)
        assertEquals(1, h.tasks.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
    }

    @Test fun `stale speech and stale model completion cannot affect new generation`() {
        val h = Harness(); val oldBrain = CompletableDeferred<VoiceTurnDecision>(); h.brain.deferred = oldBrain
        h.voice.start(); val staleListener = h.input.listener!!; h.input.emit(SpeechInputEvent.Final("old")); h.scope.runCurrent(); h.voice.stop(); h.voice.start()
        staleListener(SpeechInputEvent.Final("late speech")); oldBrain.complete(VoiceTurnDecision("stale answer")); h.scope.advanceUntilIdle()
        assertFalse(h.output.spoken.contains("stale answer")); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `TTS pauses recognizer and its audio cannot become a turn`() {
        val h = Harness(); h.brain.next = VoiceTurnDecision("Ten."); h.voice.start(); h.input.emit(SpeechInputEvent.Final("five plus five")); h.scope.advanceUntilIdle()
        val calls = h.brain.calls; h.input.emit(SpeechInputEvent.Final("Ten.")); h.scope.advanceUntilIdle()
        assertEquals(calls, h.brain.calls); assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
    }

    @Test fun `unavailable local speech or offline TTS refuses start truthfully`() {
        val stt = Harness(inputAvailable = false); assertFalse(stt.voice.start()); assertTrue(stt.voice.state.value.message!!.contains("on-device", true)); assertEquals(0, stt.input.starts)
        val tts = Harness(outputAvailable = false); assertFalse(tts.voice.start()); assertTrue(tts.voice.state.value.message!!.contains("offline", true)); assertTrue(tts.output.spoken.isEmpty())
    }

    private class Harness(inputAvailable: Boolean = true, outputAvailable: Boolean = true) {
        val scope = TestScope(StandardTestDispatcher()); val input = FakeInput(inputAvailable); val output = FakeOutput(outputAvailable); val brain = FakeBrain()
        val tasks = AgentTaskController(); val goals = mutableListOf<String>(); val gates = ArrayDeque<CompletableDeferred<AgentResult>>(); val fgs = mutableListOf<Boolean>()
        val coordinator = VoiceAgentCoordinator(scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY }) { goal -> goals += goal; CompletableDeferred<AgentResult>().also(gates::add).await() }
        val voice = MettenVoiceSessionController(scope, { input }, { output }, brain, coordinator, { true }, { true }, fgs::add)
        init { brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Settings")) }
    }
    private class FakeInput(private val available: Boolean) : SpeechInputEngine {
        var listener: ((SpeechInputEvent) -> Unit)? = null; var starts = 0; var released = false
        override fun capability() = SpeechCapability(available, if (available) null else "No on-device recognizer.")
        override fun startListening(listener: (SpeechInputEvent) -> Unit) { starts++; this.listener = listener }
        override fun stopListening() = Unit
        override fun release() { released = true }
        fun emit(event: SpeechInputEvent) = listener?.invoke(event) ?: Unit
    }
    private class FakeOutput(private val available: Boolean) : SpeechOutputEngine {
        val spoken = mutableListOf<String>(); var listener: ((SpeechOutputEvent) -> Unit)? = null; var released = false
        override fun capability() = SpeechCapability(available, if (available) null else "No offline TTS voice.")
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken += text; this.listener = listener; listener(SpeechOutputEvent.Started) }
        override fun stop() = Unit
        override fun release() { released = true }
        fun complete() { val current = listener; listener = null; current?.invoke(SpeechOutputEvent.Completed) }
    }
    private class FakeBrain : VoiceTurnBrain {
        var next = VoiceTurnDecision("Ten."); var deferred: CompletableDeferred<VoiceTurnDecision>? = null; var calls = 0
        override suspend fun decide(userText: String, context: List<VoiceConversationTurn>): VoiceTurnDecision { calls++; return deferred?.await() ?: next }
    }
    private companion object { fun result(ok: Boolean, text: String) = AgentResult(ok, text, AgentContext("voice", "goal")) }
}
