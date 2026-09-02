package com.mobileclaw.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryLoopTest {
    @Test fun `success exits after first attempt`() = runBlocking {
        var attempts = 0
        val result = runRetryLoop(3, { ++attempts; "ok" }, { it.isFailure })
        assertEquals("ok", result.getOrThrow())
        assertEquals(1, attempts)
    }

    @Test fun `cancellation is terminal`() = runBlocking {
        var attempts = 0
        val result = runRetryLoop<String>(3, {
            attempts++
            throw CancellationException("stop")
        }, { it.exceptionOrNull() !is CancellationException })
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun `non retryable failure is terminal`() = runBlocking {
        var attempts = 0
        val result = runRetryLoop<String>(3, {
            attempts++
            error("invalid configuration")
        }, { false })
        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }

    @Test fun `retryable failure retries and then succeeds`() = runBlocking {
        var attempts = 0
        val result = runRetryLoop(3, {
            attempts++
            if (attempts == 1) error("transient") else "ok"
        }, { it.isFailure })
        assertEquals("ok", result.getOrThrow())
        assertEquals(2, attempts)
    }

    @Test fun `retryable failure never exceeds maximum attempts`() = runBlocking {
        var attempts = 0
        val result = runRetryLoop<String>(3, {
            attempts++
            error("transient")
        }, { true })
        assertTrue(result.isFailure)
        assertEquals(3, attempts)
    }
}
