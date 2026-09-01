package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceCodeProtocolTest {
    @Test fun `device code accepts code aliases and numeric interval forms`() {
        val standard = ChatGptDeviceProtocol.parseCode("""{"device_auth_id":"id","user_code":"ABCD","interval":5}""")
        assertEquals("ABCD", standard.userCode); assertEquals(5, standard.interval)
        val alias = ChatGptDeviceProtocol.parseCode("""{"device_auth_id":"id","usercode":"EFGH","interval":"7"}""")
        assertEquals("EFGH", alias.userCode); assertEquals(7, alias.interval)
        for (bad in listOf("0", "-1", "nope")) assertTrue(runCatching {
            ChatGptDeviceProtocol.parseCode("""{"device_auth_id":"id","user_code":"code","interval":"$bad"}""")
        }.isFailure)
    }

    @Test fun `polls immediately then waits exact server interval for pending results`() = runBlocking {
        val service = FakeService(ArrayDeque(listOf(null, null, DeviceToken("authorization", "verifier", "challenge"))))
        val waits = mutableListOf<Long>()
        val result = DeviceCodePoller(service) { waits += it }.poll(DeviceCode("id", "code", 4))
        assertEquals(3, service.polls); assertEquals(listOf(4000L, 4000L), waits)
        assertEquals("authorization", result.authorizationCode); assertEquals("challenge", result.codeChallenge)
    }

    @Test fun `device token retains challenge and requires code and verifier`() {
        val token = ChatGptDeviceProtocol.parseToken("""{"authorization_code":"auth","code_verifier":"verify","code_challenge":"challenge"}""")
        assertEquals("auth", token.authorizationCode); assertEquals("verify", token.codeVerifier); assertEquals("challenge", token.codeChallenge)
        assertTrue(runCatching { ChatGptDeviceProtocol.parseToken("{}") }.isFailure)
    }

    @Test fun `initial unavailable differs from pending poll statuses`() {
        val unavailable = runCatching { ChatGptDeviceProtocol.initialResponse(404, "") }.exceptionOrNull()
        assertTrue(unavailable?.message?.contains("not available") == true)
        assertNull(ChatGptDeviceProtocol.pollResponse(403, ""))
        assertNull(ChatGptDeviceProtocol.pollResponse(404, ""))
        assertTrue(runCatching { ChatGptDeviceProtocol.pollResponse(500, "") }.isFailure)
    }

    @Test fun `poll cancellation propagates without another request`() = runBlocking {
        val service = FakeService(ArrayDeque(listOf(null)))
        val result = runCatching { DeviceCodePoller(service) { throw CancellationException() }.poll(DeviceCode("id", "code", 2)) }
        assertTrue(result.exceptionOrNull() is CancellationException); assertEquals(1, service.polls)
    }

    private class FakeService(private val results: ArrayDeque<DeviceToken?>) : ChatGptAuthService {
        var polls = 0
        override suspend fun pollDevice(id: String, code: String): DeviceToken? { polls++; return results.removeFirst() }
        override suspend fun exchange(code: String, redirect: String, verifier: String) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun deviceCode() = error("unused")
        override suspend fun revoke(tokens: ChatGptOAuthTokens) = Unit
    }
}
