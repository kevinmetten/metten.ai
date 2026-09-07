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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class MettenVoiceSessionControllerTest {
    @Test fun `startup activation completed while muted remains admitted and unmute listens once`() {
        val h = Harness(autoInitialize = false)
        assertTrue(h.voice.start())
        h.voice.setMuted(true)
        h.output.finishInitialization(true)
        assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase)
        assertEquals(0, h.input.starts)

        h.voice.setMuted(false)
        assertEquals(1, h.input.starts)
        h.input.emit(SpeechInputEvent.Final("Open Settings"))
        h.scope.advanceUntilIdle()
        assertEquals(listOf("Open Settings"), h.goals)
        assertEquals("I'm working on that.", h.output.spoken.single())
    }

    @Test fun `unmute before startup activation completes waits and starts one listener`() {
        val h = Harness(autoInitialize = false)
        assertTrue(h.voice.start())
        h.voice.setMuted(true)
        h.voice.setMuted(false)
        assertEquals(MettenVoicePhase.STARTING, h.voice.state.value.phase)
        assertEquals(0, h.input.starts)

        h.output.finishInitialization(true)
        assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        assertEquals(1, h.input.starts)
        assertEquals(listOf("start:1"), h.fgs)
        assertEquals(1, h.output.initializeCalls)
    }

    @Test fun `mute after output ownership installation cannot orphan speech`() {
        val h = Harness(); h.start(); h.brain.next = VoiceTurnDecision("Ten.")
        h.input.onStop = { if (h.input.stops == 2) h.voice.setMuted(true) }
        h.input.emit(SpeechInputEvent.Final("five plus five")); h.scope.advanceUntilIdle()

        assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase)
        assertEquals(listOf("Ten."), h.output.spoken)
        h.output.complete()
        assertEquals(MettenVoicePhase.MUTED, h.voice.state.value.phase)
        val starts = h.input.starts
        h.voice.setMuted(false)
        assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        assertEquals(starts + 1, h.input.starts)
    }

    @Test fun `output completion falls back to thinking while brain turn remains`() {
        val h = Harness(); h.startPhone(); h.brain.next = VoiceTurnDecision("Ten."); h.brain.gate = CompletableDeferred()
        h.input.emit(SpeechInputEvent.Final("what time is it")); h.scope.runCurrent()
        val starts = h.input.starts
        h.gates.removeFirst().complete(result(true, "Settings opened.")); h.scope.advanceUntilIdle()
        assertEquals("Settings opened.", h.output.spoken.last())
        h.output.complete()
        assertEquals(MettenVoicePhase.THINKING, h.voice.state.value.phase)
        assertEquals(starts, h.input.starts)

        h.brain.gate!!.complete(Unit); h.scope.advanceUntilIdle()
        assertEquals("Ten.", h.output.spoken.last())
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
    }

    @Test fun `output completion remains thinking while an exact phone handoff is pending`() {
        val publicationGate = CompletableDeferred<Unit>()
        val h = Harness(publicationGate = publicationGate); h.start()
        h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.runCurrent()
        val requestId = h.pendingRequestIds().single()
        h.addPendingPhoneTurn("still-pending", "check status")

        h.emitPhoneEvent(VoiceControlEvent.Accepted(1, requestId, "task"))
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
        val starts = h.input.starts
        h.output.complete()
        assertEquals(MettenVoicePhase.THINKING, h.voice.state.value.phase)
        assertEquals(starts, h.input.starts)

        h.emitPhoneEvent(VoiceControlEvent.Completed(1, "unrelated", "task", VoiceControlTerminalState.CANCELLED, "ignored"))
        assertEquals(MettenVoicePhase.THINKING, h.voice.state.value.phase)
        h.emitPhoneEvent(VoiceControlEvent.Completed(1, "still-pending", "task", VoiceControlTerminalState.CANCELLED, "cancelled"))
        assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        assertEquals(starts + 1, h.input.starts)
    }

    @Test fun `matching phone event transfers pending ownership directly to output`() {
        val publicationGate = CompletableDeferred<Unit>()
        val h = Harness(publicationGate = publicationGate); h.start()
        h.input.emit(SpeechInputEvent.Final("Open Settings")); h.scope.runCurrent()
        val requestId = h.pendingRequestIds().single()
        val starts = h.input.starts

        h.emitPhoneEvent(VoiceControlEvent.Accepted(1, "unrelated", "task"))
        assertEquals(MettenVoicePhase.THINKING, h.voice.state.value.phase)
        assertEquals(starts, h.input.starts)
        h.emitPhoneEvent(VoiceControlEvent.Accepted(1, requestId, "task"))
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
        assertEquals(starts, h.input.starts)
        assertEquals("I'm working on that.", h.output.spoken.single())
    }

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

    @Test fun `recovered output completion permits a second conversational turn and stale completion is ignored`() {
        val h = Harness(); h.start(); h.brain.next = VoiceTurnDecision("Ten.")
        h.input.emit(SpeechInputEvent.Final("what is five plus five")); h.scope.advanceUntilIdle()
        val firstOutput = h.output.listeners.lastIndex
        h.output.complete(firstOutput)
        assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        h.brain.next = VoiceTurnDecision("Twelve.")
        h.input.emit(SpeechInputEvent.Final("what is six plus six")); h.scope.advanceUntilIdle()
        assertEquals(2, h.brain.calls); assertEquals(listOf("Ten.", "Twelve."), h.output.spoken)
        h.output.complete(firstOutput)
        assertEquals(MettenVoicePhase.SPEAKING, h.voice.state.value.phase)
    }

    @Test fun `terminal phone result permits a second phone command`() {
        val h = Harness(); h.start()
        h.input.emit(SpeechInputEvent.Final("open android settings")); h.scope.advanceUntilIdle()
        h.output.complete()
        h.gates.removeFirst().complete(result(true, "Settings are open.")); h.scope.advanceUntilIdle()
        assertEquals("Settings are open.", h.output.spoken.last())
        h.output.complete(); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        h.brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open apps"))
        h.input.emit(SpeechInputEvent.Final("open apps")); h.scope.advanceUntilIdle()
        assertEquals(2, h.brain.calls); assertEquals(listOf("Open Settings", "Open apps"), h.goals)
    }

    @Test fun `foreground start exception fails cleanly and newer generation recovers`() {
        var starts = 0
        val lease = SerializedVoiceForegroundLease({ if (++starts == 1) throw SecurityException("not allowed") }, {})
        val h = Harness(foregroundLease = lease)
        val chat = h.tasks.register("chat", TaskType.CHAT, false)
        val phone = h.tasks.tryRegisterExclusiveTaskType("ui", TaskType.PHONE_CONTROL, true) as AgentTaskController.RegistrationAttempt.Registered
        assertFalse(h.voice.start()); assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase)
        assertTrue(h.input.released); assertTrue(h.output.released)
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.None, lease.stateSnapshot())
        assertNotNull(h.tasks.task(chat.taskId)); assertNotNull(h.tasks.task(phone.registration.taskId))
        assertTrue(h.voice.start()); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        assertEquals(SerializedVoiceForegroundLease.PhysicalState.Active(3), lease.stateSnapshot())
    }

    @Test fun `already owned foreground is idempotent and duplicate Start is rejected`() {
        var starts = 0
        val lease = SerializedVoiceForegroundLease({ starts++ }, {})
        assertEquals(VoiceForegroundAcquireResult.Acquired, lease.acquire(1))
        val h = Harness(autoInitialize = false, foregroundLease = lease)
        assertTrue(h.voice.start()); assertEquals(1, h.output.initializeCalls); assertEquals(1, starts)
        assertFalse(h.voice.start()); assertEquals(1, h.output.initializeCalls); assertEquals(1, starts)
        h.output.finishInitialization(true); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    @Test fun `stale foreground acquisition never initializes speech`() {
        val lease = object : VoiceForegroundLease {
            override fun acquire(generation: Long) = VoiceForegroundAcquireResult.StaleOrEnded
            override fun release(generation: Long) = VoiceForegroundReleaseResult.NoOp
        }
        val h = Harness(autoInitialize = false, foregroundLease = lease)
        assertFalse(h.voice.start()); assertEquals(0, h.output.initializeCalls)
        assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase)
        assertTrue(h.input.released); assertTrue(h.output.released)
    }

    @Test fun `failed foreground release preserves cleanup and newer generation resolves old stop`() {
        var stopFails = true; val calls = mutableListOf<String>(); var starting = 1L
        val lease = SerializedVoiceForegroundLease({ calls += "start:$starting" }, { calls += "stop"; if (stopFails) throw IllegalStateException("blocked") })
        val h = Harness(foregroundLease = lease); h.start()
        h.voice.stop()
        assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase)
        assertTrue(h.input.released); assertTrue(h.output.released)
        assertTrue(lease.stateSnapshot() is SerializedVoiceForegroundLease.PhysicalState.StopFailed)
        stopFails = false; starting = 3
        assertTrue(h.voice.start()); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
        assertEquals(listOf("start:1", "stop", "stop", "start:3"), calls)
    }

    @Test fun `foreground cleanup failure preserves primary fatal speech reason`() {
        val lease = SerializedVoiceForegroundLease({}, { throw IllegalStateException("stop failed") })
        val h = Harness(foregroundLease = lease); h.start()
        h.input.emit(SpeechInputEvent.FatalError("recognizer died"))
        assertEquals(MettenVoicePhase.FAILED, h.voice.state.value.phase)
        assertTrue(h.voice.state.value.message.orEmpty().startsWith("recognizer died"))
        assertTrue(h.input.released); assertTrue(h.output.released)
        assertTrue(lease.stateSnapshot() is SerializedVoiceForegroundLease.PhysicalState.StopFailed)
    }

    @Test fun `delayed old cleanup failure cannot overwrite newer Start`() {
        val stopEntered = CountDownLatch(1); val allowStop = CountDownLatch(1); var stops = 0
        val lease = SerializedVoiceForegroundLease(
            { },
            { if (++stops == 1) { stopEntered.countDown(); check(allowStop.await(2, TimeUnit.SECONDS)); throw IllegalStateException("old stop") } },
        )
        val h = Harness(autoInitialize = false, foregroundLease = lease); assertTrue(h.voice.start()); h.output.finishInitialization(true)
        val ended = CountDownLatch(1); val endThread = Thread { h.voice.stop(); ended.countDown() }; endThread.start()
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        val started = CountDownLatch(1); val startThread = Thread { h.voice.start(); started.countDown() }; startThread.start()
        allowStop.countDown(); assertTrue(ended.await(2, TimeUnit.SECONDS)); assertTrue(started.await(2, TimeUnit.SECONDS))
        endThread.join(2_000); startThread.join(2_000); assertFalse(endThread.isAlive); assertFalse(startThread.isAlive)
        assertEquals(MettenVoicePhase.STARTING, h.voice.state.value.phase)
        h.output.finishInitialization(true); assertEquals(MettenVoicePhase.LISTENING, h.voice.state.value.phase)
    }

    private class Harness(inputAvailable: Boolean = true, outputAvailable: Boolean = true, private val autoInitialize: Boolean = true, foregroundLease: VoiceForegroundLease? = null, publicationGate: CompletableDeferred<Unit>? = null) {
        val scope = TestScope(StandardTestDispatcher()); val input = FakeInput(inputAvailable); val output = FakeOutput(outputAvailable, autoInitialize); val brain = FakeBrain()
        val tasks = AgentTaskController(); val goals = mutableListOf<String>(); val gates = ArrayDeque<CompletableDeferred<AgentResult>>(); val fgs = mutableListOf<String>()
        val coordinator = VoiceAgentCoordinator(scope, tasks, AgentTaskSubmissionService(tasks, scope) {}, { ReadinessLevel.READY }, { publicationGate?.await() }) { goal -> goals += goal; CompletableDeferred<AgentResult>().also(gates::add).await() }
        private val recordingLease = object : VoiceForegroundLease {
            override fun acquire(generation: Long): VoiceForegroundAcquireResult { fgs += "start:$generation"; return VoiceForegroundAcquireResult.Acquired }
            override fun release(generation: Long): VoiceForegroundReleaseResult { fgs += "stop:$generation"; return VoiceForegroundReleaseResult.Released }
        }
        val voice = MettenVoiceSessionController(scope, { input }, { output }, brain, coordinator, { true }, { true }, foregroundLease ?: recordingLease)
        init { brain.next = VoiceTurnDecision(phoneCommand = VoiceControlCommand.Start("Open Settings")) }
        fun start() { assertTrue(voice.start()); if (!autoInitialize) output.finishInitialization(true) }
        fun startPhone() { start(); input.emit(SpeechInputEvent.Final("Open Settings")); scope.advanceUntilIdle(); output.complete() }
        @Suppress("UNCHECKED_CAST")
        fun pendingRequestIds(): Set<String> {
            val field = MettenVoiceSessionController::class.java.getDeclaredField("pendingPhoneTurns").apply { isAccessible = true }
            return (field.get(voice) as Map<String, String>).keys.toSet()
        }
        @Suppress("UNCHECKED_CAST")
        fun addPendingPhoneTurn(requestId: String, text: String) {
            val field = MettenVoiceSessionController::class.java.getDeclaredField("pendingPhoneTurns").apply { isAccessible = true }
            (field.get(voice) as MutableMap<String, String>)[requestId] = text
        }
        fun emitPhoneEvent(event: VoiceControlEvent) {
            val method = MettenVoiceSessionController::class.java.getDeclaredMethod("onPhoneEvent", java.lang.Long.TYPE, VoiceControlEvent::class.java).apply { isAccessible = true }
            method.invoke(voice, event.generation, event)
        }
    }
    private class FakeInput(private val available: Boolean) : SpeechInputEngine {
        val listeners = mutableListOf<(SpeechInputEvent) -> Unit>(); var starts = 0; var stops = 0; var released = false
        var onStop: (() -> Unit)? = null
        override fun capability() = SpeechCapability(available, if (available) null else "No on-device recognizer.")
        override fun startListening(listener: (SpeechInputEvent) -> Unit) { starts++; listeners += listener }
        override fun stopListening() { stops++; onStop?.also { onStop = null }?.invoke() }
        override fun release() { released = true }
        fun emit(event: SpeechInputEvent) = listeners.lastOrNull()?.invoke(event) ?: Unit
    }
    private class FakeOutput(private val available: Boolean, private val auto: Boolean) : SpeechOutputEngine {
        val spoken = mutableListOf<String>(); val listeners = mutableListOf<(SpeechOutputEvent) -> Unit>(); val speechListener get() = listeners.lastOrNull(); var initialization: ((SpeechCapability) -> Unit)? = null; var released = false; var initializeCalls = 0
        override fun capability() = SpeechCapability(available && auto, if (available) "initializing" else "No offline TTS voice.", initializing = available && !auto)
        override fun initialize(listener: (SpeechCapability) -> Unit) { initializeCalls++; if (auto) listener(SpeechCapability(available, if (available) null else "No offline TTS voice.")) else initialization = listener }
        fun finishInitialization(success: Boolean) { initialization?.also { initialization = null }?.invoke(SpeechCapability(success, if (success) null else "No offline TTS voice.")) }
        override fun speak(text: String, listener: (SpeechOutputEvent) -> Unit) { spoken += text; listeners += listener; listener(SpeechOutputEvent.Started) }
        override fun stop() = Unit
        override fun release() { released = true }
        fun complete(index: Int = listeners.lastIndex) { listeners.getOrNull(index)?.invoke(SpeechOutputEvent.Completed) }
    }
    private class FakeBrain : VoiceTurnBrain {
        var next = VoiceTurnDecision("Ten."); var calls = 0; var lastContext: VoiceTurnContext? = null; var failure: VoiceTurnProcessingException? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun decide(userText: String, context: VoiceTurnContext): VoiceTurnDecision { calls++; lastContext = context; gate?.await(); failure?.let { throw it }; return next }
    }
    private companion object { fun result(ok: Boolean, text: String) = AgentResult(ok, text, AgentContext("voice", "goal")) }
}
