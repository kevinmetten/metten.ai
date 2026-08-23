package com.mobileclaw.ui

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

    fun isCapabilityInfoQuestion(goal: String): Boolean {
        val normalized = normalizeCommand(goal)
        return normalized.length <= 80 && normalized in capabilityQuestions
    }

    fun isRecentContinuationCommand(goal: String): Boolean =
        normalizeCommand(goal) in continuationCommands

    fun hasExplicitPhoneControlIntent(goal: String): Boolean {
        val text = goal.trim().lowercase()
        return listOf(
            "control the phone",
            "operate the phone",
            "on my phone",
            "open app",
            "open the app",
        ).any(text::contains)
    }

    fun hasMemoryIntent(goal: String): Boolean {
        val text = goal.lowercase()
        return listOf(
            "remember",
            "save this",
            "from now on",
            "next time",
            "my preference",
            "my preferences",
            "my habit",
            "store in memory",
        ).any(text::contains)
    }

    fun hasExecutionIntent(goal: String): Boolean {
        val text = goal.lowercase()
        return listOf(
            "create", "generate", "update", "modify", "fix", "run", "execute",
            "open", "search", "install", "connect", "continue",
        ).any(text::contains)
    }

    private fun normalizeCommand(value: String): String =
        value.trim().lowercase().trimEnd('?', '!', '.')
}
