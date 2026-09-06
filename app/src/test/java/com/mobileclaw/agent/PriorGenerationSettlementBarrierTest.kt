package com.mobileclaw.agent

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PriorGenerationSettlementBarrierTest {
    @Test fun `old snapshot cannot clear newer barrier`() {
        val barrier = PriorGenerationSettlementBarrier<Unit>()
        val a = CompletableDeferred<Unit>(); val b = CompletableDeferred<Unit>()
        barrier.install(a); assertSame(a, barrier.snapshot())
        barrier.install(b); barrier.clearIfSame(a); assertSame(b, barrier.snapshot())
        barrier.clearIfSame(b); assertNull(barrier.snapshot())
    }
}
