package com.mobileclaw.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGptRealtimeSessionControllerTest {
    @Test fun `successful lifecycle duplicate start mute and repeated stop`() {
        val harness = Harness()
        assertEquals(RealtimeVoicePhase.IDLE, harness.controller.state.value.phase)
        assertTrue(harness.controller.start())
        assertEquals(RealtimeVoicePhase.CONNECTING, harness.controller.state.value.phase)
        assertFalse(harness.controller.start())
        harness.scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.CONNECTED, harness.controller.state.value.phase)
        harness.controller.setMuted(true)
        assertEquals(RealtimeVoicePhase.MUTED, harness.controller.state.value.phase)
        assertTrue(harness.last.muted)
        harness.controller.setMuted(false)
        assertEquals(RealtimeVoicePhase.CONNECTED, harness.controller.state.value.phase)
        harness.controller.stop()
        assertEquals(RealtimeVoicePhase.IDLE, harness.controller.state.value.phase)
        assertTrue(harness.last.closed)
        harness.controller.stop()
        assertEquals(1, harness.transports.size)
    }

    @Test fun `cancelled negotiation returns idle and disposes`() {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness { gate.await() }
        harness.controller.start()
        harness.scope.runCurrent()
        harness.controller.stop()
        harness.scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.IDLE, harness.controller.state.value.phase)
        assertTrue(harness.last.closed)
    }

    @Test fun `failure permits a fresh start and stale callback cannot mutate it`() {
        val harness = Harness { throw RealtimeVoiceException(RealtimeVoiceDiagnostic.ACCESS_DENIED, "Unavailable") }
        harness.controller.start()
        harness.scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.FAILED, harness.controller.state.value.phase)
        val first = harness.last
        harness.behavior = {}
        assertTrue(harness.controller.start())
        harness.scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.CONNECTED, harness.controller.state.value.phase)
        first.callback?.invoke(RealtimeVoiceException(RealtimeVoiceDiagnostic.NETWORK_FAILED, "stale"))
        assertEquals(RealtimeVoicePhase.CONNECTED, harness.controller.state.value.phase)
    }

    @Test fun `remote close is truthful and transport is closed once`() {
        val harness = Harness()
        harness.controller.start()
        harness.scope.advanceUntilIdle()
        harness.last.callback?.invoke(null)
        assertEquals(RealtimeVoicePhase.FAILED, harness.controller.state.value.phase)
        assertEquals(RealtimeVoiceDiagnostic.REMOTE_CLOSED, harness.controller.state.value.diagnostic)
        assertEquals(1, harness.last.closeCount)
    }

    @Test fun `completion after stop cannot publish connected`() {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness { gate.await() }
        harness.controller.start()
        harness.scope.runCurrent()
        harness.controller.stop()
        gate.complete(Unit)
        harness.scope.advanceUntilIdle()
        assertEquals(RealtimeVoicePhase.IDLE, harness.controller.state.value.phase)
        assertEquals(1, harness.last.closeCount)
    }

    private class Harness(initial: suspend () -> Unit = {}) {
        val scope = TestScope(StandardTestDispatcher())
        var behavior = initial
        val transports = mutableListOf<FakeTransport>()
        val controller = ChatGptRealtimeSessionController(scope) {
            FakeTransport { behavior() }.also(transports::add)
        }
        val last get() = transports.last()
    }

    private class FakeTransport(private val behavior: suspend () -> Unit) : RealtimeVoiceTransport {
        var closeCount = 0
        val closed get() = closeCount > 0
        var muted = false
        var callback: ((RealtimeVoiceException?) -> Unit)? = null
        override suspend fun connect(onDisconnected: (RealtimeVoiceException?) -> Unit) { callback = onDisconnected; behavior() }
        override fun setMuted(muted: Boolean) { this.muted = muted }
        override fun close() { closeCount++ }
    }
}
