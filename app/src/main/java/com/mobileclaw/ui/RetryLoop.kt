package com.mobileclaw.ui

/** Small shared retry loop that returns as soon as the current result is terminal. */
internal suspend fun <T> runRetryLoop(
    maxAttempts: Int,
    attempt: suspend (attemptIndex: Int) -> T,
    shouldRetry: (result: Result<T>) -> Boolean,
    beforeRetry: suspend (attemptIndex: Int) -> Unit = {},
): Result<T> {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    for (attemptIndex in 0 until maxAttempts) {
        val result = runCatching { attempt(attemptIndex) }
        val hasAnotherAttempt = attemptIndex < maxAttempts - 1
        if (!hasAnotherAttempt || !shouldRetry(result)) return result
        beforeRetry(attemptIndex)
    }
    error("Retry loop completed without an attempt")
}
