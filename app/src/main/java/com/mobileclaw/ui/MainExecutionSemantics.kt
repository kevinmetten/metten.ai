package com.mobileclaw.ui

/** Small English convenience matchers; unmatched text remains owned by the routing stack. */
internal object MainExecutionSemantics {
    private val capabilityQuestions = setOf(
        "what can you do",
        "what are your capabilities",
        "what capabilities do you have",
        "what tools do you have",
        "available tools",
        "capability list",
        "tool list",
        "what do you support",
    )

    private val continuationCommands = setOf(
        "continue",
        "go on",
        "keep going",
        "resume",
        "next",
        "next step",
    )

    private val explicitPhonePhrases = setOf(
        "control the phone",
        "operate the phone",
        "on my phone",
        "open app",
        "open the app",
    )

    private val memoryPhrases = setOf(
        "remember",
        "save this",
        "from now on",
        "next time",
        "my preference",
        "my preferences",
        "my habit",
        "store in memory",
    )

    private val executionWords = setOf(
        "create", "generate", "update", "modify", "fix", "run", "execute", "open", "search",
        "install", "connect", "continue",
    )

    fun isCapabilityInfoQuestion(input: String): Boolean {
        val normalized = normalizedPhrase(input)
        return normalized.length <= 80 && normalized in capabilityQuestions
    }

    fun isRecentContinuationCommand(input: String): Boolean =
        normalizedPhrase(input) in continuationCommands

    fun hasExplicitPhoneControlIntent(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return explicitPhonePhrases.any { it in normalized }
    }

    fun hasMemoryIntent(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return memoryPhrases.any { it in normalized }
    }

    fun hasExecutionIntent(input: String): Boolean {
        val words = input.lowercase().split(Regex("[^a-z]+"))
        return words.any { it in executionWords }
    }

    private fun normalizedPhrase(input: String): String =
        input.trim().lowercase().trimEnd('?', '!', '.')
}
