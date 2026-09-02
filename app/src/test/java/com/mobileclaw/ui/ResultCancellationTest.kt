package com.mobileclaw.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ResultCancellationTest {
    @Test fun `success is returned unchanged`() {
        val result = Result.success("ok")
        assertEquals(result, result.rethrowCancellation())
    }

    @Test fun `ordinary failure is returned unchanged`() {
        val result = Result.failure<String>(IllegalStateException("failed"))
        assertEquals(result, result.rethrowCancellation())
    }

    @Test fun `cancellation failure is rethrown`() {
        val cancellation = CancellationException("stop")
        try {
            Result.failure<String>(cancellation).rethrowCancellation()
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun `cancellation prevents completion side effect`() {
        var completionCalls = 0
        val cancellation = CancellationException("stop")
        try {
            val result = Result.failure<String>(cancellation).rethrowCancellation()
            result.fold(
                onSuccess = { completionCalls++ },
                onFailure = { completionCalls++ },
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected control flow before terminal-result consumption.
        }
        assertEquals(0, completionCalls)
    }
}
