package com.mobileclaw.agent

internal data class DeterministicToolCall(
    val skillId: String,
    val params: Map<String, Any>,
    val finalAfterSuccess: Boolean = false,
    val thought: String = "",
)

internal object DeterministicPhoneLaunchRouting {
    fun next(taskType: TaskType, goal: String, steps: List<AgentStep>): DeterministicToolCall? {
        if (taskType != TaskType.PHONE_CONTROL) return null
        val requestedApp = requestedAppName(goal) ?: return null
        val alreadyLaunched = steps.any {
            it.skillId == "navigate" &&
                it.skillParams?.get("action") == "launch" &&
                it.skillParams["app_name"] == requestedApp &&
                !it.isError
        }
        if (alreadyLaunched) return null
        return DeterministicToolCall(
            skillId = "navigate",
            params = mapOf("action" to "launch", "app_name" to requestedApp, "foreground" to true),
            finalAfterSuccess = isLaunchOnlyPhoneGoal(goal),
            thought = "Deterministic phone app launch",
        )
    }

    fun requestedAppName(goal: String): String? {
        val text = goal.lineSequence().firstOrNull().orEmpty().trim()
        val match = Regex("""(?i)^(?:please\s+)?(?:open|launch|start)\s+([^,.!?\n]+)""").find(text) ?: return null
        val normalizedName = match.groupValues[1].replace(Regex("""(?i)\s+app$"""), "").trim()
        val appName = normalizedName.substringBeforeAny(
            " and ", " then ", " search ", " find ", " tap ", " click ", " select ", " type ",
        ).trim()
        if (appName.isBlank()) return null
        if (listOf("website", "web page", "link", "file").any { appName.equals(it, ignoreCase = true) }) return null
        return appName.take(40)
    }

    private fun isLaunchOnlyPhoneGoal(goal: String): Boolean {
        val text = goal.lineSequence().firstOrNull().orEmpty().trim()
        val afterLaunchVerb = Regex("""(?i)^(?:please\s+)?(?:open|launch|start)\s+([^,.!?\n]+)""")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        if (afterLaunchVerb.isBlank()) return false
        return listOf(
            " and ", " then ", " search ", " find ", " tap ", " click ", " select ", " type ", " scroll ", " filter ",
        ).none { afterLaunchVerb.contains(it) }
    }

    private fun String.substringBeforeAny(vararg delimiters: String): String {
        val firstIndex = delimiters.mapNotNull { indexOf(it).takeIf { index -> index >= 0 } }.minOrNull() ?: return this
        return substring(0, firstIndex)
    }
}
