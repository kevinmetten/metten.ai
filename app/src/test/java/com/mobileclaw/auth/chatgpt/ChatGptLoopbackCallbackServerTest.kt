package com.mobileclaw.auth.chatgpt

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatGptLoopbackCallbackServerTest {
    @Test fun `listener is bound before exact loopback redirect is exposed and prefers 1455`() {
        ChatGptLoopbackCallbackServer.open().use { server ->
            val uri = URI(server.redirectUri)
            assertEquals("127.0.0.1", server.listenerHost)
            assertEquals(server.listenerHost, uri.host)
            assertEquals(1455, server.port)
            assertTrue(server.isLoopbackOnly)
            assertTrue(InetAddress.getByName(uri.host).isLoopbackAddress)
        }
    }

    @Test fun `falls back to 1457 when preferred port is occupied`() {
        ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1")).use {
            ChatGptLoopbackCallbackServer.open().use { server -> assertEquals(1457, server.port) }
        }
    }

    @Test fun `irrelevant and malformed requests do not consume valid callback`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(connectionReadTimeoutMs = 150).use { server ->
            val waiting = async { server.awaitCallback(CallbackValidator("expected")) }
            assertTrue(request(server, "GET /favicon.ico HTTP/1.1").contains("404"))
            assertTrue(request(server, "not http").contains("400"))
            val validResponse = async { request(server, callbackLine("expected", "code-ok")) }
            val callback = withTimeout(2_000) { waiting.await() }
            assertEquals("code-ok", callback.authorizationCode)
            callback.success()
            assertTrue(validResponse.await().contains(BrowserCallbackPages.SUCCESS))
        }
    }

    @Test fun `stalled connection times out while subsequent valid callback succeeds`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(connectionReadTimeoutMs = 100).use { server ->
            val waiting = async { server.awaitCallback(CallbackValidator("expected")) }
            val stalled = Socket(server.listenerHost, server.port)
            val validResponse = async { request(server, callbackLine("expected", "after-stall")) }
            val callback = withTimeout(2_000) { waiting.await() }
            stalled.close()
            assertEquals("after-stall", callback.authorizationCode)
            callback.success()
            assertTrue(validResponse.await().contains(BrowserCallbackPages.SUCCESS))
        }
    }

    @Test fun `state and callback parameter failures are rejected safely`() = runBlocking {
        val targets = listOf(
            "/auth/callback?state=wrong&code=x",
            "/auth/callback?code=x",
            "/auth/callback?state=expected",
            "/auth/callback?state=expected&error=access_denied",
        )
        targets.forEach { target ->
            ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0)).use { server ->
                val waiting = async { runCatching { server.awaitCallback(CallbackValidator("expected")) } }
                val response = async { request(server, "GET $target HTTP/1.1") }
                assertTrue(withTimeout(2_000) { waiting.await() }.isFailure)
                assertTrue(response.await().contains(BrowserCallbackPages.FAILURE))
            }
        }
    }

    @Test fun `closing listener cancels pending accept and timeout closes in finally`() = runBlocking {
        val server = ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0))
        val waiting = async { runCatching { server.awaitCallback(CallbackValidator("expected")) } }
        server.close()
        assertTrue(withTimeout(2_000) { waiting.await() }.exceptionOrNull() is CancellationException)

        val timed = ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0))
        try {
            assertTrue(runCatching { withTimeout(50) { timed.awaitCallback(CallbackValidator("expected")) } }.exceptionOrNull() is CancellationException)
        } finally { timed.close() }
        assertTrue(runCatching { Socket(timed.listenerHost, timed.port) }.isFailure)
    }

    private fun callbackLine(state: String, code: String): String =
        "GET /auth/callback?state=${encode(state)}&code=${encode(code)} HTTP/1.1"

    private fun request(server: ChatGptLoopbackCallbackServer, requestLine: String): String =
        Socket(server.listenerHost, server.port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write("$requestLine\r\nHost: ${server.listenerHost}\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
