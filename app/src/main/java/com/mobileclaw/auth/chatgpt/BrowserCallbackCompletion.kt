package com.mobileclaw.auth.chatgpt

internal interface BrowserCallbackResponder {
    val authorizationCode: String
    fun success()
    fun failure()
}

internal object BrowserCallbackCompletion {
    suspend fun <T> complete(callback: BrowserCallbackResponder, operation: suspend () -> T): T {
        val result = try {
            operation()
        } catch (failure: Throwable) {
            runCatching { callback.failure() }
            throw failure
        }
        // Authentication is already committed. Browser delivery is informational only.
        runCatching { callback.success() }
        return result
    }
}

internal object BrowserCallbackPages {
    const val SUCCESS = "ChatGPT sign-in complete. You can close this page and return to MobileClaw."
    const val FAILURE = "ChatGPT sign-in could not be completed. Return to MobileClaw and try again."
    const val NOT_FOUND = "This local page is not part of the ChatGPT sign-in callback."
    const val INVALID_REQUEST = "The local browser request could not be understood."
}
