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
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChatGptLoopbackCallbackServerTest {
    @Test fun `redirect matches OpenCode localhost authorize semantics and listener is already bound`() {
        ChatGptLoopbackCallbackServer.open().use { server ->
            val redirect = URI(server.redirectUri)
            assertEquals("localhost", redirect.host)
            assertEquals(1455, server.port)
            assertEquals("/auth/callback", redirect.path)
            assertTrue(server.isLoopbackOnly)
            assertTrue(server.boundLoopbackAddresses.isNotEmpty())
            assertTrue(server.boundLoopbackAddresses.all { InetAddress.getByName(it).isLoopbackAddress })
            assertTrue(runCatching { Socket("127.0.0.1", server.port).close() }.isSuccess)

            val authorize = ChatGptOAuthProtocol.authorizationUrl(server.redirectUri, ChatGptPkce("verifier", "challenge"), "state").toHttpUrl()
            assertEquals("code", authorize.queryParameter("response_type"))
            assertEquals(ChatGptOAuth.CLIENT_ID, authorize.queryParameter("client_id"))
            assertEquals(server.redirectUri, authorize.queryParameter("redirect_uri"))
            assertEquals(ChatGptOAuth.SCOPES, authorize.queryParameter("scope"))
            assertEquals("S256", authorize.queryParameter("code_challenge_method"))
            assertEquals("true", authorize.queryParameter("id_token_add_organizations"))
            assertEquals("true", authorize.queryParameter("codex_cli_simplified_flow"))
            assertEquals("metten_ai_android", authorize.queryParameter("originator"))
            assertNotEquals("opencode", authorize.queryParameter("originator"))
            assertNotEquals("codex_cli_rs", authorize.queryParameter("originator"))
        }
    }

    @Test fun `token exchange reuses exact localhost redirect`() {
        val redirect = "http://localhost:1455/auth/callback"
        val authorize = ChatGptOAuthProtocol.authorizationUrl(redirect, ChatGptPkce("verifier", "challenge"), "state").toHttpUrl()
        val form = ChatGptAuthRequests.exchange("sentinel-code", redirect, "sentinel-verifier").body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(authorize.queryParameter("redirect_uri"), fields["redirect_uri"])
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals(ChatGptOAuth.CLIENT_ID, fields["client_id"])
        assertEquals("sentinel-verifier", fields["code_verifier"])
    }

    @Test fun `falls back to 1457 only when 1455 is unavailable on loopback families`() {
        val ipv6Supported = bindOrNull("::1", 0)?.let { it.close(); true } ?: false
        val ipv4 = bindOrNull("127.0.0.1", 1455)
        val ipv6 = if (ipv6Supported) bindOrNull("::1", 1455) else null
        assumeTrue("Could not reserve IPv4 port 1455", ipv4 != null)
        assumeTrue("Could not reserve IPv6 port 1455", !ipv6Supported || ipv6 != null)
        val blockers = listOfNotNull(ipv4, ipv6)
        try {
            ChatGptLoopbackCallbackServer.open().use { server -> assertEquals(1457, server.port) }
        } finally { blockers.forEach { it.close() } }
    }

    @Test fun `IPv4 direct callback succeeds while redirect remains localhost`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0)).use { server ->
            assumeTrue(server.boundLoopbackAddresses.any { InetAddress.getByName(it) is java.net.Inet4Address })
            assertEquals("localhost", URI(server.redirectUri).host)
            completeCallback(server, "127.0.0.1", "ipv4-code")
        }
    }

    @Test fun `IPv6 direct callback succeeds on the same logical port when available`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0)).use { server ->
            assumeTrue("IPv6 loopback is unavailable", server.boundLoopbackAddresses.any { InetAddress.getByName(it) is java.net.Inet6Address })
            assertTrue(server.boundLoopbackAddresses.all { InetAddress.getByName(it).isLoopbackAddress })
            completeCallback(server, "::1", "ipv6-code")
        }
    }

    @Test fun `irrelevant and malformed requests do not consume valid callback`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0), connectionReadTimeoutMs = 150).use { server ->
            val waiting = async { server.awaitCallback(CallbackValidator("expected")) }
            assertTrue(request(server, "127.0.0.1", "GET /favicon.ico HTTP/1.1").contains("404"))
            assertTrue(request(server, "127.0.0.1", "not http").contains("400"))
            val validResponse = async { request(server, "127.0.0.1", callbackLine("expected", "code-ok")) }
            val callback = withTimeout(2_000) { waiting.await() }
            assertEquals("code-ok", callback.authorizationCode)
            callback.success()
            assertTrue(validResponse.await().contains(BrowserCallbackPages.SUCCESS))
        }
    }

    @Test fun `stalled connection times out while subsequent valid callback succeeds`() = runBlocking {
        ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0), connectionReadTimeoutMs = 100).use { server ->
            val waiting = async { server.awaitCallback(CallbackValidator("expected")) }
            val stalled = Socket("127.0.0.1", server.port)
            val validResponse = async { request(server, "127.0.0.1", callbackLine("expected", "after-stall")) }
            val callback = withTimeout(2_000) { waiting.await() }
            stalled.close()
            assertEquals("after-stall", callback.authorizationCode)
            callback.success()
            assertTrue(validResponse.await().contains(BrowserCallbackPages.SUCCESS))
        }
    }

    @Test fun `state and callback parameter failures are rejected safely`() = runBlocking {
        val targets = listOf("/auth/callback?state=wrong&code=x", "/auth/callback?code=x", "/auth/callback?state=expected", "/auth/callback?state=expected&error=access_denied")
        targets.forEach { target ->
            ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0)).use { server ->
                val waiting = async { runCatching { server.awaitCallback(CallbackValidator("expected")) } }
                val response = async { request(server, "127.0.0.1", "GET $target HTTP/1.1") }
                assertTrue(withTimeout(2_000) { waiting.await() }.isFailure)
                assertTrue(response.await().contains(BrowserCallbackPages.FAILURE))
            }
        }
    }

    @Test fun `closing logical server closes every listener and accepted client`() = runBlocking {
        val server = ChatGptLoopbackCallbackServer.open(ports = intArrayOf(0))
        val stalled = Socket("127.0.0.1", server.port)
        val waiting = async { runCatching { server.awaitCallback(CallbackValidator("expected")) } }
        server.close()
        assertTrue(withTimeout(2_000) { waiting.await() }.exceptionOrNull() is CancellationException)
        stalled.soTimeout = 2_000
        val clientWasClosed = runCatching { stalled.getInputStream().read() == -1 }
            .getOrElse { true }
        assertTrue(clientWasClosed)
        assertTrue(runCatching { Socket("127.0.0.1", server.port) }.isFailure)
        stalled.close()
    }

    private suspend fun completeCallback(server: ChatGptLoopbackCallbackServer, host: String, code: String) = coroutineScope {
        val waiting = async { server.awaitCallback(CallbackValidator("expected")) }
        val response = async { request(server, host, callbackLine("expected", code)) }
        val callback = withTimeout(2_000) { waiting.await() }
        assertEquals(code, callback.authorizationCode)
        callback.success()
        assertTrue(response.await().contains(BrowserCallbackPages.SUCCESS))
    }

    private fun callbackLine(state: String, code: String) = "GET /auth/callback?state=${encode(state)}&code=${encode(code)} HTTP/1.1"
    private fun request(server: ChatGptLoopbackCallbackServer, host: String, requestLine: String): String = Socket(host, server.port).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().write("$requestLine\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readText()
    }
    private fun bindOrNull(host: String, port: Int) = runCatching { ServerSocket(port, 1, InetAddress.getByName(host)) }.getOrNull()
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
