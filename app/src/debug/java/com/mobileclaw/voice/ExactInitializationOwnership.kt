package com.mobileclaw.voice

internal enum class InitializationAdmission { START, ALREADY_IN_FLIGHT, ALREADY_READY, RELEASED }

/** Exact ownership for one asynchronously constructed closeable native resource. */
internal class ExactInitializationOwnership<T> {
    private var inFlight = false
    private var released = false
    private var resource: T? = null
    @Synchronized fun begin(): InitializationAdmission = when {
        released -> InitializationAdmission.RELEASED
        resource != null -> InitializationAdmission.ALREADY_READY
        inFlight -> InitializationAdmission.ALREADY_IN_FLIGHT
        else -> { inFlight = true; InitializationAdmission.START }
    }
    @Synchronized fun accept(value: T): Boolean {
        inFlight = false
        if (released || resource != null) return false
        resource = value
        return true
    }
    @Synchronized fun failed() { inFlight = false }
    @Synchronized fun current(): T? = resource
    @Synchronized fun isReleased() = released
    @Synchronized fun release(): T? { if (released) return null; released = true; inFlight = false; return resource.also { resource = null } }
}
