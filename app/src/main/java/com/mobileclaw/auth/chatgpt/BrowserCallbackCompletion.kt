package com.mobileclaw.auth.chatgpt

internal interface BrowserCallbackResponder {
    val authorizationCode: String
    fun success()
    fun failure()
}

internal object BrowserCallbackCompletion {
    suspend fun <T> complete(callback: BrowserCallbackResponder, operation: suspend () -> T): T = try {
        operation().also { callback.success() }
    } catch (failure: Throwable) {
        runCatching { callback.failure() }
        throw failure
    }
}

internal object BrowserCallbackPages {
    const val SUCCESS = "ChatGPT sign-in complete. You can close this page and return to MobileClaw."
    const val FAILURE = "ChatGPT sign-in could not be completed. Return to MobileClaw and try again."
}
