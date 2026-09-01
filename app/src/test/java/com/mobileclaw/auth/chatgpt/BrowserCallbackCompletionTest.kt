package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BrowserCallbackCompletionTest {
    @Test fun `success is withheld until exchange and persistence finish`() = runBlocking {
        val callback = FakeCallback()
        val exchangeFinished = CompletableDeferred<Unit>()
        val completion = async { BrowserCallbackCompletion.complete(callback) { exchangeFinished.await(); "saved" } }
        assertEquals(0, callback.successes)
        exchangeFinished.complete(Unit)
        assertEquals("saved", completion.await())
        assertEquals(1, callback.successes); assertEquals(0, callback.failures)
    }

    @Test fun `exchange or persistence failure returns only browser failure`() = runBlocking {
        for (message in listOf("exchange rejected", "secure persistence failed")) {
            val callback = FakeCallback()
            assertTrue(runCatching { BrowserCallbackCompletion.complete(callback) { error(message) } }.isFailure)
            assertEquals(0, callback.successes); assertEquals(1, callback.failures)
        }
    }

    @Test fun `browser failure page contains no secret sentinels`() {
        for (secret in listOf("TEST_AUTHORIZATION_CODE_DO_NOT_LEAK", "TEST_ACCESS_TOKEN_DO_NOT_LEAK", "TEST_REFRESH_TOKEN_DO_NOT_LEAK", "TEST_PKCE_VERIFIER_DO_NOT_LEAK")) {
            assertFalse(BrowserCallbackPages.FAILURE.contains(secret))
        }
    }

    @Test fun `success page delivery failure does not invalidate committed authentication`() = runBlocking {
        val callback = FakeCallback(failSuccess = true)
        val result = BrowserCallbackCompletion.complete(callback) { "persisted-and-signed-in" }
        assertEquals("persisted-and-signed-in", result)
        assertEquals(1, callback.successes)
        assertEquals(0, callback.failures)
    }

    private class FakeCallback(private val failSuccess: Boolean = false) : BrowserCallbackResponder {
        override val authorizationCode = "TEST_AUTHORIZATION_CODE_DO_NOT_LEAK"
        var successes = 0; var failures = 0
        override fun success() { successes++; if (failSuccess) throw IOException("browser disconnected") }
        override fun failure() { failures++ }
    }
}
