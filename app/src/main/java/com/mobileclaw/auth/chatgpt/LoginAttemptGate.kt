package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.CancellationException

/** Serializes only login-attempt publication; it is never held during OAuth I/O. */
internal class LoginAttemptGate {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Long = synchronized(lock) { ++generation }
    fun invalidate() = synchronized(lock) { generation++ }
    fun isCurrent(candidate: Long): Boolean = synchronized(lock) { generation == candidate }

    fun <T> runIfCurrent(candidate: Long, action: () -> T): T = synchronized(lock) {
        if (generation != candidate) throw CancellationException("The ChatGPT sign-in attempt is no longer active.")
        action()
    }
}
