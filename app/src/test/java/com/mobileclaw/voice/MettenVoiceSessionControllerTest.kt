package com.mobileclaw.voice

import com.mobileclaw.agent.*
import com.mobileclaw.permission.ReadinessLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MettenVoiceSessionControllerTest {
    @Test fun `async TTS start stays STARTING and End rejects late initialization`() {
        val h = Harness(autoInitialize = false)
        assertTrue(h.voice.start()); assertEquals(MettenVoicePhase.STARTING, h.voice.state.value.phase); assertEquals(listOf("start:1"), h.fgs)
        h.voice.stop(); h.output.finishInitialization(true)
        assertEquals(MettenVoicePhase.IDLE, h.voice.state.value.phase); assertEquals(listOf("start:1", "stop:1"), h.fgs); assertEquals(0, h.input.starts)
    }

    @Test fun `fatal failure fully tears down exact Voice generation only`() {
        val h = Harness(); h.start(); h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.advanceUntilIdle(); h.output.complete()
        val chat = h.tasks.register("chat", TaskType.CHAT, false); val stale = h.input.listeners.last()
        h.input.emit(SpeechInputEvent.FatalError("recognizer died")); h.scope.advanceUntilIdle()
        assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase); assertEquals("recognizer died", h.voice.state.value.message)
        assertTrue(h.input.released); assertTrue(h.output.released); assertEquals("stop:1", h.fgs.last()); assertNotNull(h.tasks.task(chat.taskId))
        assertTrue(h.tasks.activeTasks.value.none { it.taskType == TaskType.PHONE_CONTROL })
        val calls = h.brain.calls; stale(SpeechInputEvent.Final("stale")); h.scope.advanceUntilIdle(); assertEquals(calls, h.brain.calls)
    }

    @Test fun `mute preserves running phone task and ordinary conversation does not replace it`() {
        val h = Harness(); h.startPhone(); val taskId = h.tasks.activeTasks.value.single().taskId
        h.voice.setMuted(true); assertNotNull(h.tasks.task(taskId)); assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase)
        h.voice.setMuted(false); h.brain.next = VoiceTurnDecision("Ten."); h.input.emit(SpeechInputEvent.Final("What is five plus five?")); h.scope.advanceUntilIdle()
        assertEquals("Ten.", h.output.spoken.last()); assertNotNull(h.tasks.task(taskId)); assertEquals(1, h.goals.size)
        h.output.complete(); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `accepted phone turn enters bounded context for next brain call`() {
        val h = Harness(); h.startPhone(); h.brain.next = VoiceTurnDecision("Okay.")
        h.input.emit(SpeechInputEvent.Final("Scroll to my alarm")); h.scope.advanceUntilIdle()
        assertTrue(h.brain.lastContext!!.conversation.any { it.userText == "Open Settings" && it.assistantText == "I'm working on that." })
        assertEquals(VoicePhoneTaskState.RUNNING, h.brain.lastContext!!.phoneTask.state)
        assertEquals("Open Settings", h.brain.lastContext!!.phoneTask.summary)
    }

    @Test fun `user cancel speaks exactly once and replacement cancellation is silent`() {
        val cancel = Harness(); cancel.startPhone(); cancel.brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Cancel)
        cancel.input.emit(SpeechInputEvent.Final("stop that")); cancel.scope.advanceUntilIdle()
        assertEquals(1, cancel.output.spoken.count { it == "Phone task cancelled." })

        val replace = Harness(); replace.startPhone(); replace.brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Replace("Use alarm row toggle"))
        replace.input.emit(SpeechInputEvent.Final("No, use the toggle")); replace.scope.advanceUntilIdle()
        assertFalse(replace.output.spoken.any { "did not complete" in it }); assertEquals(listOf("Open Settings", "Use alarm row toggle"), replace.goals)
        assertEquals(1, replace.tasks.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
    }

    @Test fun `late callback from prior listen attempt in same generation is ignored`() {
        val h = Harness(); h.start(); val old = h.input.listeners.last(); h.brain.next = VoiceTurnDecision("Ten.")
        old(SpeechInputEvent.Final("five plus five")); h.scope.advanceUntilIdle(); h.output.complete()
        val calls = h.brain.calls; old(SpeechInputEvent.Final("late old result")); h.scope.advanceUntilIdle()
        assertEquals(calls, h.brain.calls); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `stale recoverable retry cannot rearm during thinking or speaking`() {
        val h = Harness(); h.start(); val listener = h.input.listeners.last(); listener(SpeechInputEvent.RecoverableError("timeout", 2_000)); h.brain.next = VoiceTurnDecision("Ten.")
        listener(SpeechInputEvent.Final("five plus five")); h.scope.runCurrent()
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase); val starts = h.input.starts
        h.scope.advanceUntilIdle(); assertEquals(starts, h.input.starts)
    }

    @Test fun `TTS self loop is rejected and actual task results keep Voice healthy`() {
        val h = Harness(); h.startPhone(); val calls = h.brain.calls; h.input.emit(SpeechInputEvent.Final("I'm working on that.")); assertEquals(calls, h.brain.calls)
        h.gates.removeFirst().complete(result(true, "Settings opened.")); h.scope.advanceUntilIdle()
        assertEquals("Settings opened.", h.output.spoken.last()); h.output.complete(); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `unavailable local speech or offline TTS refuses synchronously`() {
        val stt = Harness(inputAvailable = false); assertFalse(stt.voice.start()); assertEquals(0, stt.input.starts)
        val tts = Harness(outputAvailable = false); assertFalse(tts.voice.start()); assertEquals(MettenVoicePhase.FAILED, tts.voice.state.value.phase)
    }

    @Test fun `mute and unmute during speech waits for exact output completion`() {
        val h = Harness(); h.start(); h.brain.next = VoiceTurnDecision("Ten.")
        h.input.emit(SpeechInputEvent.Final("five plus five")); h.scope.advanceUntilIdle()
        val starts = h.input.starts
        h.voice.setMuted(true); h.voice.setMuted(false)
        assertEquals(starts, h.input.starts)
        h.output.complete()
        assertEquals(starts + 1, h.input.starts)
    }

    @Test fun `output completion while muted waits until unmute and duplicate is stale`() {
        val h = Harness(); h.start(); h.brain.next = VoiceTurnDecision("Ten.")
        h.input.emit(SpeechInputEvent.Final("five plus five")); h.scope.advanceUntilIdle()
        val callback = h.output.speechListener!!
        h.voice.setMuted(true); callback(SpeechOutputEvent.Completed)
        assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase)
        val starts = h.input.starts; callback(SpeechOutputEvent.Completed); assertEquals(starts, h.input.starts)
        h.voice.setMuted(false); assertEquals(starts + 1, h.input.starts)
    }

    @Test fun `recoverable text failure preserves active phone work`() {
        val h = Harness(); h.startPhone(); val task = h.tasks.activeTasks.value.single().taskId
        h.brain.failure = VoiceTurnProcessingException.EmptyResponse()
        h.input.emit(SpeechInputEvent.Final("what time is it")); h.scope.advanceUntilIdle()
        assertNotNull(h.tasks.task(task))
        assertEquals("I couldn't process that request. Please try again.", h.output.spoken.last())
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
    }

    @Test fun `three recognizer start failures exhaust bounded budget while no speech does not`() {
        val h = Harness(); h.start()
        repeat(5) { h.input.emit(SpeechInputEvent.RecoverableError("none", 300, SpeechInputFailureKind.NO_SPEECH)); h.scope.advanceUntilIdle() }
        assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        repeat(3) { h.input.emit(SpeechInputEvent.RecoverableError("start", 300, SpeechInputFailureKind.START_FAILURE)); h.scope.advanceUntilIdle() }
        assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase)
    }

    private class Harness(inputAvailable: Boolean = true, outputAvailable: Boolean = true, autoInitialize: Boolean = true) {
        val scope = TestScope(StandardTestDispatcher()); val input = FakeInput(inputAvailable); val output = FakeOutput(outputAvailable, autoInitialize); val brain = FakeBrain()
        val tasks = AgentTaskController(); val goals = mutableListOf<String>(); val gates = ArrayDeque<CompletableDeferred<AgentResult>>(); val fgs = mutableListOf<String>()
        val coordinator = VoiceAgentCoordinator(scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY }) { goal -> goals += goal; CompletableDeferred<AgentResult>().also(gates::add).await() }
        val voice = MettenVoiceSessionController(scope, { input }, { output }, brain, coordinator, { true }, { true }, object : VoiceForegroundLease {
            override fun acquire(generation: Long): Boolean { fgs += "start:$generation"; return true }
            override fun release(generation: Long) { fgs += "stop:$generation" }
        })
        init { brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Settings")) }
        fun start() { assertTrue(voice.start()); if (!autoInitialize) output.finishInitialization(true) }
        fun startPhone() { start(); input.emit(SpeechInputEvent.Final("Open Settings")); scope.advanceUntilIdle(); output.complete() }
    }
    private class FakeInput(private val available: Boolean) : SpeechInputEngine {
        val listeners = mutableListOf<(SpeechInputEvent) -> Unit>(); var starts = 0; var released = false
        override fun capability() = SpeechCapability(available, if (available) null else "No on-device recognizer.")
        override fun startListening(listener: (SpeechInputEvent) -> Unit) { starts++; listeners += listener }
        override fun stopListening() = Unit
        override fun release() { released = true }
        fun emit(event: SpeechInputEvent) = listeners.lastOrNull()?.invoke(event) ?: Unit
    }
    private class FakeOutput(private val available: Boolean, private val auto: Boolean) : SpeechOutputEngine {
        val spoken = mutableListOf<String>(); var speechListener: ((SpeechOutputEvent) -> Unit)? = null; var initialization: ((SpeechCapability) -> Unit)? = null; var released = false
        override fun capability() = SpeechCapability(available && auto, if (available) "initializing" else "No offline TTS voice.", initializing = available && !auto)
        override fun initialize(listener: (SpeechCapability) -> Unit) { if (auto) listener(SpeechCapability(available, if (available) null else "No offline TTS voice.")) else initialization = listener }
        fun finishInitialization(success: Boolean) { initialization?.also { initialization = null }?.invoke(SpeechCapability(success, if (success) null else "No offline TTS voice.")) }
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken += text; speechListener = listener; listener(SpeechOutputEvent.Started) }
        override fun stop() = Unit
        override fun release() { released = true }
        fun complete() { speechListener?.also { speechListener = null }?.invoke(SpeechOutputEvent.Completed) }
    }
    private class FakeBrain : VoiceTurnBrain {
        var next = VoiceTurnDecision("Ten."); var calls = 0; var lastContext: VoiceTurnContext? = null; var failure: VoiceTurnProcessingException? = null
        override suspend fun decide(userText: String, context: VoiceTurnContext): VoiceTurnDecision { calls++; lastContext = context; failure?.let { throw it }; return next }
    }
    private companion object { fun result(ok: Boolean, text: String) = AgentResult(ok, text, AgentContext("voice", "goal")) }
}
