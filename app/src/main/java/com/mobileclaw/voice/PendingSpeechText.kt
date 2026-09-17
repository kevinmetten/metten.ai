package com.mobileclaw.voice

/** Bounded queue with a single scheduled consumer; cancellation clears all unsynthesized text. */
internal class PendingSpeechText(private val maxChars: Int = 64_000) {
    sealed interface Item {
        data class Text(val value: String) : Item
        data object Wait : Item
        data object End : Item
    }
    private val queue = ArrayDeque<String>()
    private var chars = 0
    private var scheduled = false
    private var finished = false
    private var cancelled = false
    @Synchronized fun append(text: String): Boolean {
        if (cancelled || finished || text.isBlank()) return false
        if (chars + text.length > maxChars) throw VoiceTurnProcessingException.InvalidDecision("Voice speech queue limit reached.")
        queue.addLast(text); chars += text.length
        return schedule()
    }
    @Synchronized fun finish(): Boolean {
        if (cancelled || finished) return false
        finished = true
        return schedule()
    }
    @Synchronized fun next(): Item {
        if (cancelled) return Item.Wait
        queue.removeFirstOrNull()?.let { chars -= it.length; return Item.Text(it) }
        scheduled = false
        return if (finished) { cancelled = true; Item.End } else Item.Wait
    }
    @Synchronized fun cancel() { cancelled = true; queue.clear(); chars = 0 }
    private fun schedule(): Boolean = if (scheduled) false else { scheduled = true; true }
}
