package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class OkHttpCallAwaiterTest {
    @Test fun `coroutine cancellation cancels underlying okhttp call`() = runBlocking {
        val call = FakeCall()
        val request = async { OkHttpCallAwaiter.await(call) }
        call.awaitEnqueued()
        request.cancel()
        runCatching { request.await() }
        assertTrue(call.isCanceled())
    }

    private class FakeCall : Call {
        private val enqueued = kotlinx.coroutines.CompletableDeferred<Unit>()
        private var canceled = false
        override fun request(): Request = Request.Builder().url("https://auth.openai.com/oauth/token").build()
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) { enqueued.complete(Unit) }
        override fun cancel() { canceled = true }
        override fun isExecuted() = true
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
        suspend fun awaitEnqueued() = enqueued.await()
    }
}
