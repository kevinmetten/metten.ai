package com.mobileclaw.agent

import com.mobileclaw.auth.chatgpt.ChatGptBackendCredentials
import com.mobileclaw.permission.ReadinessLevel
import com.mobileclaw.realtime.ChatGptRealtimeSidebandClient
import com.mobileclaw.realtime.RealtimeCredentialProvider
import com.mobileclaw.realtime.RealtimeRequestContext
import com.mobileclaw.realtime.RealtimeSidebandAttachment
import com.mobileclaw.realtime.SidebandSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRealtimeProductionSeamTest {
    @Test fun `natural Frameless handoff executes one canonical phone task and returns result on same id`() {
        val scope = TestScope(StandardTestDispatcher())
        val controller = AgentTaskController(idFactory = sequenceOf("unrelated", "unrelated-registration", "phone", "phone-registration").iterator()::next)
        val unrelated = controller.register("chat", TaskType.CHAT, false)
        val submissions = AgentTaskSubmissionService(controller, scope) {}
        val workerResult = CompletableDeferred<AgentResult>()
        val goals = mutableListOf<String>()
        val socketFactory = RecordingSocketFactory()
        val sideband = ChatGptRealtimeSidebandClient(
            scope,
            RealtimeCredentialProvider { ChatGptBackendCredentials("token", "account", null) },
            socketFactory = socketFactory,
        )
        val coordinator = VoiceAgentCoordinator(scope, controller, submissions, { ReadinessLevel.READY }) { goal ->
            goals += goal
            workerResult.await()
        }
        coordinator.beginSession(1, sideband)
        sideband.connect(
            RealtimeSidebandAttachment("rtc_exact", RealtimeRequestContext("session", "thread", "realtime")),
            1,
            coordinator::accept,
        )
        scope.runCurrent()
        val socket = socketFactory.socket
        socket.listener.onOpen(socket, switchingProtocols(socket.request()))

        socket.listener.onMessage(socket, NATURAL_START_FRAME)
        scope.runCurrent()

        assertEquals(listOf("Open Android Settings"), goals)
        assertEquals(1, controller.activeTasks.value.count { it.taskType == TaskType.PHONE_CONTROL })
        assertNotNull(controller.task(unrelated.taskId))
        assertTrue(socket.sent.any { "\"delegation_item_id\":\"d1\"" in it && "phone_task_started" in it })

        workerResult.complete(AgentResult(true, "Settings opened.", AgentContext("runtime", "goal")))
        scope.advanceUntilIdle()
        assertTrue(socket.sent.any { "\"delegation_item_id\":\"d1\"" in it && "phone_task_result" in it && "SUCCEEDED" in it })
        assertNotNull(controller.task(unrelated.taskId))
    }

    private class RecordingSocketFactory : SidebandSocketFactory {
        lateinit var socket: RecordingSocket
        override fun open(request: Request, listener: WebSocketListener): WebSocket = RecordingSocket(request, listener).also { socket = it }
    }

    private class RecordingSocket(private val handshake: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        override fun request() = handshake
        override fun queueSize() = 0L
        override fun send(text: String) = sent.add(text)
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun switchingProtocols(request: Request) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()

    private companion object {
        const val NATURAL_START_FRAME = """{"type":"delegation.created","item":{"id":"d1","type":"delegation","target":"client","content":[{"type":"input_text","text":"Open Android Settings"}]}}"""
    }
}
