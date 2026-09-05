package com.mobileclaw.realtime

import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSidebandLifecycleTest {
    @Test fun `transport close gate prevents late sideband attachment`() {
        val sideband = FakeSideband()
        val attachment = TransportSidebandAttachment(sideband)
        attachment.close()
        assertFalse(attachment.attach("rtc_late", 1) {})
        assertEquals(0, sideband.connects)
        assertEquals(1, sideband.closes)
    }

    @Test fun `transport attachment that wins is closed exactly before later sends`() {
        val sideband = FakeSideband()
        val attachment = TransportSidebandAttachment(sideband)
        assertTrue(attachment.attach("rtc_current", 3) {})
        assertEquals(1, sideband.connects)
        assertTrue(attachment.send(update(3)))
        attachment.close()
        assertFalse(attachment.send(update(3)))
        assertEquals(1, sideband.closes)
    }

    @Test fun `credential open in flight cannot publish after close`() {
        val scope = TestScope(StandardTestDispatcher())
        val credential = CompletableDeferred<ChatGptBackendCredentials>()
        val factory = FakeSocketFactory()
        val client = client(scope, RealtimeCredentialProvider { credential.await() }, factory)
        client.connect("rtc_one", 1) {}
        scope.runCurrent()
        client.close()
        credential.complete(credentials())
        scope.runCurrent()
        assertTrue(factory.sockets.isEmpty())
        assertFalse(client.send(update(1)))
    }

    @Test fun `open that passed active check cannot publish socket after close`() {
        val scope = TestScope(StandardTestDispatcher())
        val factory = FakeSocketFactory()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = ChatGptRealtimeSidebandClient(
            scope, RealtimeCredentialProvider { credentials() }, socketFactory = factory,
            beforeSocketOpen = { reached.complete(Unit); release.await() },
        )
        client.connect("rtc_race", 1) {}
        scope.runCurrent()
        assertTrue(reached.isCompleted)
        client.close()
        release.complete(Unit)
        scope.runCurrent()
        assertTrue(factory.sockets.isEmpty())
        assertFalse(client.send(update(1)))
    }

    @Test fun `stale callbacks cannot replace or clear newer socket`() {
        val scope = TestScope(StandardTestDispatcher())
        val factory = FakeSocketFactory()
        val client = client(scope, RealtimeCredentialProvider { credentials() }, factory)
        client.connect("rtc_old", 1) {}
        scope.runCurrent()
        val old = factory.sockets.single()
        client.connect("rtc_new", 2) {}
        scope.runCurrent()
        val newer = factory.sockets.last()
        old.listener.onOpen(old, response(old.request()))
        old.listener.onFailure(old, IllegalStateException("stale"), null)
        old.listener.onClosed(old, 1000, "stale")
        assertTrue(client.send(update(2)))
        assertEquals(1, newer.sent.size)
        assertTrue(old.closed)
    }

    @Test fun `old scheduled reconnect cannot open after replacement or close`() {
        val scope = TestScope(StandardTestDispatcher())
        val factory = FakeSocketFactory()
        val client = client(scope, RealtimeCredentialProvider { credentials() }, factory)
        client.connect("rtc_old", 1) {}
        scope.runCurrent()
        val old = factory.sockets.single()
        old.listener.onFailure(old, IllegalStateException("transient"), null)
        client.connect("rtc_new", 2) {}
        scope.runCurrent()
        assertEquals(2, factory.sockets.size)
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, factory.sockets.size)
        client.close()
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, factory.sockets.size)
    }

    @Test fun `current lifecycle connects sends and closes normally`() {
        val scope = TestScope(StandardTestDispatcher())
        val factory = FakeSocketFactory()
        val client = client(scope, RealtimeCredentialProvider { credentials() }, factory)
        client.connect("rtc_current", 7) {}
        scope.runCurrent()
        val socket = factory.sockets.single()
        socket.listener.onOpen(socket, response(socket.request()))
        assertTrue(client.send(update(7)))
        assertFalse(client.send(update(6)))
        client.close()
        assertTrue(socket.closed)
        assertFalse(client.send(update(7)))
    }

    private fun client(scope: TestScope, provider: RealtimeCredentialProvider, factory: FakeSocketFactory) =
        ChatGptRealtimeSidebandClient(scope, provider, socketFactory = factory)
    private fun credentials() = ChatGptBackendCredentials("token", "account", null)
    private fun update(generation: Long) = RealtimeDelegationUpdate(generation, "d", "result", RealtimeDelegationChannel.SPEAKABLE)
    private fun response(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()

    private class FakeSocketFactory : SidebandSocketFactory {
        val sockets = mutableListOf<FakeWebSocket>()
        override fun open(request: Request, listener: WebSocketListener): WebSocket = FakeWebSocket(request, listener).also(sockets::add)
    }

    private class FakeWebSocket(private val handshakeRequest: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun request() = handshakeRequest
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { if (closed) return false; sent += text; return true }
        override fun send(bytes: ByteString) = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }

    private class FakeSideband : RealtimeSideband {
        var connects = 0
        var closes = 0
        override fun connect(callId: String, generation: Long, listener: (RealtimeDelegationRequest) -> Unit) { connects++ }
        override fun close() { closes++ }
        override fun send(update: RealtimeDelegationUpdate) = true
    }
}
