package com.mobileclaw.memory

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.Message
import com.mobileclaw.memory.db.ConversationEntity
import com.mobileclaw.memory.db.EpisodeEntity

/** Extracts conservative, structured long-term memories from conversations and episodes. */
class UserProfileExtractor(
    private val llm: LlmGateway,
    private val semanticMemory: SemanticMemory,
    private val conversationMemory: ConversationMemory,
) {
    suspend fun extractAndUpdate(recentGoal: String, recentSummary: String, taskId: String? = null) {
        val taskMessages = taskId?.takeIf { it.isNotBlank() }?.let {
            runCatching { conversationMemory.recentContextForTask(it, limit = 24) }.getOrDefault(emptyList())
        }.orEmpty()
        val messages = if (taskMessages.isNotEmpty()) {
            taskMessages
        } else {
            runCatching { conversationMemory.recentUserMessages(limit = 30) }.getOrDefault(emptyList())
        }
        if (messages.isEmpty() && recentGoal.isBlank()) return
        val context = UserProfileExtractionSupport.buildConversationSnippet(messages, recentGoal, recentSummary)
        val facts = runCatching { callLlm(UserProfileExtractionSupport.conversationPrompt(context)) }
            .getOrDefault(emptyList())
        facts.forEach { persistFact(it) }
    }

    suspend fun extractFromEpisodes(episodes: List<EpisodeEntity>) {
        if (episodes.size < 3) return
        val analysis = UserProfileExtractionSupport.buildEpisodeAnalysis(episodes)
        val facts = runCatching { callLlm(UserProfileExtractionSupport.episodePrompt(analysis)) }
            .getOrDefault(emptyList())
        facts.forEach { persistFact(it) }
    }

    private suspend fun persistFact(fact: ProfileFact) {
        val key = fact.key.trim()
        val value = fact.value.trim()
        if (isAllowedMemoryKey(key) && value.isNotBlank()) {
            semanticMemory.set(
                key = key,
                value = value,
                confidence = fact.confidence.coerceIn(0f, 1f),
                type = SemanticMemory.inferType(key),
                source = "memory_extractor",
            )
        }
    }

    private fun isAllowedMemoryKey(key: String): Boolean =
        key.startsWith("profile.") ||
            key.startsWith("preference.") ||
            key.startsWith("rule.") ||
            key.startsWith("correction.") ||
            key.startsWith("failure.") ||
            key.startsWith("lesson.") ||
            key.startsWith("project.") ||
            key.startsWith("tool.policy.")

    private suspend fun callLlm(prompt: String): List<ProfileFact> {
        val content = runCatching {
            llm.chat(
                ChatRequest(
                    messages = listOf(Message(role = "user", content = prompt)),
                    stream = false,
                ),
            ).content
        }.getOrNull() ?: return emptyList()
        return UserProfileExtractionSupport.parseFactsJson(content)
    }
}

internal data class ProfileFact(val key: String, val value: String, val confidence: Float)

internal object UserProfileExtractionSupport {
    private val gson = Gson()
    private val fencedJson = Regex("""^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*$""", RegexOption.IGNORE_CASE)

    internal fun buildConversationSnippet(
        messages: List<ConversationEntity>,
        goal: String,
        summary: String,
    ): String = buildString {
        if (goal.isNotBlank()) {
            appendLine("Current task: $goal")
            if (summary.isNotBlank()) appendLine("Task result: $summary")
        }
        if (messages.isNotEmpty()) {
            appendLine("Recent conversation:")
            messages.takeLast(20).forEach { message ->
                val label = when (message.role) {
                    "user" -> "User"
                    "agent", "assistant" -> "Assistant"
                    else -> "Observation"
                }
                appendLine("$label: ${message.content.take(200)}")
            }
        }
    }

    internal fun buildEpisodeAnalysis(episodes: List<EpisodeEntity>): String {
        val successCount = episodes.count { it.success }
        val allSkills = episodes.flatMap { episode ->
            runCatching { gson.fromJson(episode.skillsUsed, Array<String>::class.java).toList() }
                .getOrDefault(emptyList())
        }
        val skillFrequency = allSkills.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
        val averageGoalLength = episodes.map { it.goalText.length }.average().toInt()
        val webCount = episodes.count { it.hasAnySkill("web_search", "fetch_url", "web_browse") }
        val technicalCount = episodes.count { it.hasAnySkill("shell") }
        val visualCount = episodes.count { it.hasAnySkill("see_screen", "screenshot") }

        return buildString {
            appendLine("Task statistics: ${episodes.size} total; $successCount successful; ${successCount * 100 / episodes.size}% success rate")
            appendLine("Average task length: $averageGoalLength characters")
            appendLine("Web-related tasks: $webCount")
            appendLine("Technical operations: $technicalCount")
            appendLine("Visual-analysis tasks: $visualCount")
            appendLine("Skill usage:")
            skillFrequency.forEach { (skill, count) ->
                val pattern = observedUsagePattern(skill)
                appendLine("  $skill: $count${if (pattern.isEmpty()) "" else " ($pattern)"}")
            }
            appendLine("Recent task examples:")
            episodes.take(8).forEach { episode ->
                appendLine("  ${if (episode.success) "SUCCESS" else "FAILURE"}: ${episode.goalText.take(50)}")
            }
        }
    }

    private fun EpisodeEntity.hasAnySkill(vararg skills: String): Boolean = skills.any { skillsUsed.contains(it) }

    private fun observedUsagePattern(skill: String): String = when {
        skill.startsWith("web_") -> "frequent web research usage"
        skill == "shell" -> "technical command-line usage"
        skill == "memory" -> "knowledge-retention workflow usage"
        skill == "see_screen" -> "visual screen-analysis usage"
        skill == "navigate" -> "multi-app navigation usage"
        skill.startsWith("bg_") -> "background automation usage"
        skill == "screenshot" -> "screenshot-based workflow usage"
        skill == "tap" || skill == "scroll" -> "active UI interaction usage"
        else -> ""
    }

    private val keysHint = """
Available keys (choose only the most relevant; return at most 8 facts):
profile.physio.health / profile.physio.fitness / profile.physio.appearance / profile.physio.medical
profile.personality.temperament / profile.personality.style / profile.personality.emotion_pattern
profile.cognitive.thinking / profile.cognitive.learning / profile.cognitive.perspective
profile.emotional.stability / profile.emotional.empathy / profile.emotional.stress
profile.social.style / profile.social.communication / profile.social.relationships
profile.values.core / profile.values.goals / profile.values.principles
profile.capability.skills / profile.capability.execution / profile.capability.creativity
profile.spiritual.core / profile.spiritual.beliefs / profile.spiritual.resilience
preference.chat.style / preference.chat.language / preference.ui.style / preference.ui.avoid / preference.code.style / preference.document.style
rule.user_must.<short_name> / rule.user_do_not.<short_name>
correction.recent.<short_name>
failure.<domain>.<short_name>
lesson.<domain>.<short_name>
project.mobileclaw.<short_name>
tool.policy.<tool_or_domain>.<short_name>
    """.trimIndent()

    internal fun conversationPrompt(context: String): String = """
You analyze recent conversation for durable information that will materially improve future assistance.

$context

$keysHint

High priority evidence:
- Explicit user corrections and behavioral instructions.
- Durable preferences, prohibitions, requirements, and tool-use policies.
- Stable user facts and important reusable project context.

Lower priority or excluded evidence:
- Speculative personality profiling, one-time states, temporary emotions, and transient task instructions.
- Do not infer health, beliefs, emotional condition, or other sensitive attributes from weak proxies.

Rules:
- Do not guess. Prefer explicit user statements over inference, and return no fact when evidence is insufficient.
- Do not infer a durable fact from a one-off request or turn the current task into a permanent preference.
- Corrections and explicit behavioral instructions matter more than broad personality characterization.
- Durable wording can include always, never, from now on, going forward, in the future, remember that, you keep, do not, and you must, but interpret it in context.
- "I want a PDF" is a current request, while "I prefer PDFs for reports" may be durable.
- "I don't like this version" is transient, while "I don't like verbose answers" may be durable.
- Return a JSON array only, with no Markdown or surrounding prose, and at most 8 facts.
- Each item must use a listed key, a confidence from 0.0 to 1.0, and a concise factual English value of no more than about 40 words.

Example:
[{"key":"preference.chat.style","value":"Prefers concise, direct answers.","confidence":0.9},{"key":"tool.policy.image_understanding.no_web_search","value":"Inspect uploaded images directly unless the user explicitly requests online research.","confidence":0.95}]
    """.trimIndent()

    internal fun episodePrompt(analysis: String): String = """
You analyze repeated task-history patterns to extract durable, reusable operational memory.

$analysis

$keysHint

Rules:
- Require sufficient evidence across repeated episodes; a one-off success or failure normally produces no fact.
- Prioritize repeated failure patterns, reusable lessons, and recurring tool or workflow policies.
- Describe skill frequencies as observed usage patterns, not as personality, intelligence, health, beliefs, or emotional traits.
- Do not infer unsupported sensitive or psychological conclusions from tool use, task length, or success rate.
- A repeated pattern may support failure.<domain>.*, lesson.<domain>.*, or tool.policy.<domain>.* when warranted.
- Do not guess; return no fact when evidence is insufficient.
- Return a JSON array only, with no Markdown or surrounding prose, and at most 8 facts.
- Each item must use a listed key, a confidence from 0.0 to 1.0, and a concise factual English value of no more than about 40 words.
    """.trimIndent()

    internal fun parseFactsJson(raw: String): List<ProfileFact> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val json = fencedJson.matchEntire(trimmed)?.groupValues?.get(1)
            ?: if (trimmed.startsWith("```")) return emptyList() else trimmed
        return runCatching {
            JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
                val objectValue = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val key = runCatching { objectValue.get("key")?.asString?.trim() }.getOrNull()
                    ?: return@mapNotNull null
                val value = runCatching { objectValue.get("value")?.asString?.trim() }.getOrNull()
                    ?: return@mapNotNull null
                val confidence = runCatching { objectValue.get("confidence")?.asFloat }.getOrNull() ?: 0.5f
                if (key.isBlank() || value.isBlank()) null else ProfileFact(key, value, confidence)
            }
        }.getOrDefault(emptyList())
    }
}
