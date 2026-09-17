package com.mobileclaw.voice

/** Conservative character bounds reduce Pocket token-cache pressure; overflow retry is still authoritative. */
internal object PocketTextSegmentation {
    const val INITIAL_MAX_CODE_POINTS = 600
    const val MIN_RETRY_CODE_POINTS = 32
    const val MAX_SPLIT_DEPTH = 6
    private val paragraph = Regex("(?:\\r?\\n){2,}")
    private val sentence = Regex("[.!?]+(?:[\\p{Z}\\s]+|$)")
    private val clause = Regex("[,;:—–]+(?:[\\p{Z}\\s]+|$)")
    private val whitespace = Regex("[\\p{Z}\\s]+")

    fun initial(text: String): List<String> = splitBounded(text, INITIAL_MAX_CODE_POINTS)

    fun retry(text: String, depth: Int): List<String>? {
        val size = text.codePointCount(0, text.length)
        if (depth >= MAX_SPLIT_DEPTH || size <= MIN_RETRY_CODE_POINTS) return null
        val pieces = splitBounded(text, maxOf(MIN_RETRY_CODE_POINTS, size / 2))
        return pieces.takeIf { it.size > 1 }
    }

    fun isCacheOverflow(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { it.message?.contains("text and voice conditioning exceed the 1000-token LM cache", ignoreCase = true) == true }

    private fun splitBounded(text: String, maxCodePoints: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val endLimit = text.offsetByCodePoints(start, minOf(maxCodePoints, text.codePointCount(start, text.length)))
            if (endLimit == text.length) { result += text.substring(start); break }
            val end = listOf(paragraph, sentence, clause, whitespace)
                .firstNotNullOfOrNull { regex -> regex.findAll(text, start).map { it.range.last + 1 }.lastOrNull { it <= endLimit && it > start } }
                ?: endLimit
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
