package com.mobileclaw.ui

internal object MainExecutionSemantics {
    private val tokenPattern = Regex("[\\p{L}\\p{N}_]+(?:-[\\p{L}\\p{N}_]+)*")

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

    fun isCapabilityInfoQuestion(goal: String): Boolean {
        val normalized = normalizeCommand(goal)
        return normalized.length <= 80 && normalized in capabilityQuestions
    }

    fun isRecentContinuationCommand(goal: String): Boolean =
        normalizeCommand(goal) in continuationCommands

    fun hasExplicitPhoneControlIntent(goal: String): Boolean {
        return listOf(
            "control the phone",
            "operate the phone",
            "on my phone",
            "open app",
            "open the app",
        ).any { goal.containsTokenPhrase(it) }
    }

    fun hasMemoryIntent(goal: String): Boolean {
        return listOf(
            "remember",
            "save this",
            "from now on",
            "next time",
            "my preference",
            "my preferences",
            "my habit",
            "store in memory",
        ).any { goal.containsTokenPhrase(it) }
    }

    fun hasExecutionIntent(goal: String): Boolean =
        listOf(
            "create", "generate", "update", "modify", "fix", "run", "execute",
            "open", "search", "install", "connect", "continue",
        ).any { goal.containsTokenPhrase(it) }

    private fun normalizeCommand(value: String): String =
        value.trim().lowercase().trimEnd('?', '!', '.')

    private fun String.containsTokenPhrase(phrase: String): Boolean {
        val tokens = tokenPattern.findAll(lowercase()).map { it.value }.toList()
        val phraseTokens = tokenPattern.findAll(phrase.lowercase()).map { it.value }.toList()
        if (phraseTokens.isEmpty() || phraseTokens.size > tokens.size) return false
        return tokens.windowed(phraseTokens.size).any { it == phraseTokens }
    }
}
