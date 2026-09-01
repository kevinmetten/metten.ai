package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LoginAttemptGateTest {
    @Test fun `cancel during exchange rejects stale publication`() = runBlocking {
        val gate = LoginAttemptGate()
        val attempt = gate.begin()
        val exchangeStarted = CompletableDeferred<Unit>()
        val exchangeResult = CompletableDeferred<Unit>()
        var saves = 0
        var signedInEmissions = 0
        val completion = async {
            exchangeStarted.complete(Unit)
            exchangeResult.await()
            runCatching { gate.runIfCurrent(attempt) { saves++; signedInEmissions++ } }
        }
        exchangeStarted.await()
        gate.invalidate()
        exchangeResult.complete(Unit)
        assertTrue(completion.await().isFailure)
        assertEquals(0, saves)
        assertEquals(0, signedInEmissions)
    }

    @Test fun `new login remains authoritative when old exchange returns`() = runBlocking {
        val gate = LoginAttemptGate()
        val attemptA = gate.begin()
        val exchangeAStarted = CompletableDeferred<Unit>()
        val exchangeAResult = CompletableDeferred<Unit>()
        var currentAccount: String? = null
        var savesA = 0
        val completionA = async {
            exchangeAStarted.complete(Unit)
            exchangeAResult.await()
            runCatching { gate.runIfCurrent(attemptA) { savesA++; currentAccount = "A" } }
        }
        exchangeAStarted.await()
        gate.invalidate()
        val attemptB = gate.begin()
        gate.runIfCurrent(attemptB) { currentAccount = "B" }
        exchangeAResult.complete(Unit)
        assertTrue(completionA.await().isFailure)
        assertEquals(0, savesA)
        assertEquals("B", currentAccount)
    }

    @Test fun `completion before invalidation is safely followed by sign out`() {
        val gate = LoginAttemptGate()
        val attempt = gate.begin()
        var currentAccount: String? = null
        gate.runIfCurrent(attempt) { currentAccount = "A" }
        gate.invalidate()
        currentAccount = null
        assertNull(currentAccount)
    }
}
