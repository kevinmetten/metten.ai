package com.mobileclaw.agent

import kotlinx.coroutines.Deferred

/** Owns only the referential identity of the task the next generation must await. */
internal class PriorGenerationSettlementBarrier<T> {
    private var completion: Deferred<T>? = null

    fun install(completion: Deferred<T>?) {
        this.completion = completion
    }

    fun snapshot(): Deferred<T>? = completion

    fun clearIfSame(snapshot: Deferred<T>) {
        if (completion === snapshot) completion = null
    }
}
