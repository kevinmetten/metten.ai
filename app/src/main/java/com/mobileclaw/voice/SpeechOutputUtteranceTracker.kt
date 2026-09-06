package com.mobileclaw.voice

/** Provider-neutral, exactly-once ownership of one replaceable speech utterance. */
internal class SpeechOutputUtteranceTracker {
    data class Delivery(
        val listener: (SpeechOutputEvent) -> Unit,
        val event: SpeechOutputEvent,
    )

    private enum class State { Pending, Started }
    private data class Current(
        val id: String,
        val listener: (SpeechOutputEvent) -> Unit,
        var state: State = State.Pending,
    )

    private val monitor = Any()
    private var current: Current? = null
    private var released = false

    /** Replaces and silently invalidates any prior utterance. */
    fun register(id: String, listener: (SpeechOutputEvent) -> Unit): Boolean = synchronized(monitor) {
        if (released) false else {
            current = Current(id, listener)
            true
        }
    }

    fun started(id: String): Delivery? = synchronized(monitor) {
        val value = current
        if (value?.id != id || value.state == State.Started) null
        else {
            value.state = State.Started
            Delivery(value.listener, SpeechOutputEvent.Started)
        }
    }

    fun completed(id: String): Delivery? = terminal(id, SpeechOutputEvent.Completed)

    fun failed(id: String, reason: String): Delivery? = terminal(id, SpeechOutputEvent.Failed(reason))

    private fun terminal(id: String, event: SpeechOutputEvent): Delivery? = synchronized(monitor) {
        val value = current
        if (value?.id != id) null else {
            current = null
            Delivery(value.listener, event)
        }
    }

    fun invalidateCurrent(): String? = synchronized(monitor) {
        current?.id.also { current = null }
    }

    fun release() = synchronized(monitor) {
        released = true
        current = null
    }

    fun isCurrent(id: String): Boolean = synchronized(monitor) { current?.id == id }
}
