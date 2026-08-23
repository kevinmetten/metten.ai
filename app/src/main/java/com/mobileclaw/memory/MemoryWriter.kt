package com.mobileclaw.memory

import com.mobileclaw.config.UserConfig

/**
 * Central write path for long-term memory.
 *
 * All user-facing entry points should write durable facts, preferences, rules,
 * corrections, and explicit user configuration through this class instead of
 * directly scattering key conventions around UI, bridge, or server code.
 */
class MemoryWriter(
    private val semanticMemory: SemanticMemory,
    private val userConfig: UserConfig? = null,
) {
    suspend fun syncUserConfig(key: String, value: String, description: String = "") {
        userConfig?.set(key, value, description)
        val memoryKey = profileMemoryKeyForUserConfig(key) ?: return
        if (value.isBlank()) {
            semanticMemory.delete(memoryKey)
        } else {
            semanticMemory.set(
                key = memoryKey,
                value = value,
                confidence = 1.0f,
                type = SemanticMemory.inferType(memoryKey),
                source = "user_config",
                sourceRef = key,
                pinned = true,
            )
        }
    }

    suspend fun deleteUserConfig(key: String) {
        userConfig?.delete(key)
        profileMemoryKeyForUserConfig(key)?.let { semanticMemory.delete(it) }
    }

    suspend fun recordExplicitUserText(text: String) {
        val facts = ExplicitUserFactExtractor.extract(text)
        if (facts.isEmpty()) return
        facts.forEach { (key, value) ->
            semanticMemory.set(
                key = key,
                value = value,
                confidence = confidenceForKey(key),
                type = SemanticMemory.inferType(key),
                source = "user_chat",
            )
            if (key.startsWith("profile.")) {
                userConfig?.set("user.${key.removePrefix("profile.")}", value, "User information recognized from chat")
            }
        }
    }

    suspend fun recordScopedUserText(scopeId: String, text: String) {
        if (scopeId.isBlank()) return
        val facts = ExplicitUserFactExtractor.extract(text)
        if (facts.isEmpty()) return
        facts.forEach { (key, value) ->
            semanticMemory.set(
                key = scopedMemoryKey(scopeId, key),
                value = value,
                confidence = confidenceForKey(key),
                type = SemanticMemory.inferType(key),
                scope = "session:$scopeId",
                source = "user_chat_scoped",
                sourceRef = key,
            )
            promoteScopedFactIfStable(scopeId, key, value)
        }
    }

    suspend fun recordTaskSnapshot(
        scopeId: String,
        goal: String,
        summary: String,
        taskType: String,
        success: Boolean? = null,
    ) {
        if (scopeId.isBlank()) return
        val entries = linkedMapOf(
            "session.$scopeId.task.goal" to goal.take(240),
            "session.$scopeId.task.summary" to summary.take(240),
            "session.$scopeId.task.type" to taskType.take(60),
        ).apply {
            success?.let { put("session.$scopeId.task.status", if (it) "success" else "failed") }
        }
        entries.forEach { (key, value) ->
            if (value.isNotBlank()) {
                semanticMemory.set(
                    key = key,
                    value = value,
                    confidence = 0.95f,
                    type = SemanticMemory.inferType(key),
                    scope = "session:$scopeId",
                    source = "task_snapshot",
                )
            }
        }
    }

    suspend fun updateTaskState(scopeId: String, state: String, detail: String = "") {
        if (scopeId.isBlank() || state.isBlank()) return
        semanticMemory.set(
            key = "session.$scopeId.task.state",
            value = state.take(80),
            confidence = 0.98f,
            type = SemanticMemory.inferType("session.$scopeId.task.state"),
            scope = "session:$scopeId",
            source = "task_state",
        )
        if (detail.isNotBlank()) {
            semanticMemory.set(
                key = "session.$scopeId.task.state_detail",
                value = detail.take(240),
                confidence = 0.9f,
                type = SemanticMemory.inferType("session.$scopeId.task.state_detail"),
                scope = "session:$scopeId",
                source = "task_state",
            )
        }
    }

    suspend fun promoteScopedMemory(memoryKey: String): Boolean {
        val fact = semanticMemory.fact(memoryKey) ?: return false
        if (!fact.key.startsWith("session.") || fact.sourceRef.isBlank()) return false
        val targetKey = fact.sourceRef
        semanticMemory.set(
            key = targetKey,
            value = fact.value,
            confidence = fact.confidence.coerceAtLeast(0.95f),
            type = SemanticMemory.inferType(targetKey),
            source = "memory_promotion",
            sourceRef = memoryKey,
            pinned = targetKey.startsWith("rule.") || targetKey.startsWith("tool.policy."),
        )
        return true
    }

    private fun confidenceForKey(key: String): Float = when {
        key.startsWith("rule.") -> 0.98f
        key.startsWith("correction.") -> 0.96f
        key.startsWith("preference.") -> 0.94f
        key.startsWith("profile.") -> 0.92f
        else -> 0.85f
    }

    private fun profileMemoryKeyForUserConfig(key: String): String? = when {
        key.startsWith("user.") -> "profile.${key.removePrefix("user.").replace('.', '_')}"
        key == "task.default_lang" -> "profile.preferred_language"
        else -> null
    }

    private fun scopedMemoryKey(scopeId: String, key: String): String = "session.$scopeId.$key"

    private suspend fun promoteScopedFactIfStable(scopeId: String, baseKey: String, value: String) {
        if (!isPromotableScopedKey(baseKey)) return
        val allFacts = semanticMemory.allFactsIncludingDisabled()
        val matchingScopes = allFacts
            .filter { fact ->
                fact.enabled &&
                    fact.key.startsWith("session.") &&
                    fact.sourceRef == baseKey &&
                    fact.value == value &&
                    fact.scope.startsWith("session:")
            }
            .map { it.scope.removePrefix("session:") }
            .distinct()
        if (matchingScopes.size < 2 && scopeId !in matchingScopes) return
        if ((matchingScopes + scopeId).distinct().size < 2) return
        semanticMemory.set(
            key = baseKey,
            value = value,
            confidence = confidenceForKey(baseKey).coerceAtLeast(0.95f),
            type = SemanticMemory.inferType(baseKey),
            source = "memory_promotion",
            sourceRef = "session:$scopeId",
            pinned = baseKey.startsWith("rule.") || baseKey.startsWith("tool.policy."),
        )
    }

    private fun isPromotableScopedKey(key: String): Boolean =
        key.startsWith("rule.") ||
            key.startsWith("preference.") ||
            key.startsWith("tool.policy.") ||
            key.startsWith("agent.behavior.") ||
            key.startsWith("profile.preferred")

}

internal object ExplicitUserFactExtractor {
    private const val MAX_INPUT_LENGTH = 500
    private const val MAX_VALUE_LENGTH = 120

    private val explicitNamePattern = Regex(
        """(?i)^(?:my name is|you can call me)\s+([^.!?;\n]{1,60})""",
    )
    private val conversationalNamePattern = Regex(
        """^(?:I'm|I am)\s+([A-Z][A-Za-z'’-]*(?:\s+[A-Z][A-Za-z'’-]*){0,2})(?:[.!?]|$)""",
    )
    private val locationPattern = Regex(
        """(?i)^(?:I live\s+(?:in|near)|I'm from|I am from)\s+([^.!?;\n]{2,80})""",
    )
    private val professionPattern = Regex(
        """(?i)^(?:I'm|I am)\s+(?:an?\s+)?((?:software\s+)?(?:engineer|developer|designer)|lawyer|attorney|teacher|physician|doctor|student|freelancer|accountant|nurse|writer|researcher|consultant)(?:[.!?]|$)""",
    )
    private val workAsPattern = Regex(
        """(?i)^(?:I work as|my job is|my profession is)\s+(?:an?\s+)?([^.!?;\n]{2,60})""",
    )
    private val preferencePattern = Regex(
        """(?i)^I\s+(?:prefer|like|love)\s+([^.!?;\n]{2,100})""",
    )
    private val dislikePattern = Regex(
        """(?i)^I\s+(?:don't like|do not like|dislike|hate)\s+([^.!?;\n]{2,100})""",
    )
    private val rememberPattern = Regex(
        """(?i)^(?:please\s+)?(?:remember(?:\s+that|\s+this\s*:?)?|keep in mind(?:\s+that)?)\s*[:,-]?\s*([^\n]{2,160})""",
    )
    private val durablePrefixPattern = Regex(
        """(?i)^(?:from now on|going forward|in the future),?\s*""",
    )
    private val canonicalDoNotPattern = Regex(
        """(?i)^(?:please\s+)?(?:don't|do not|never|stop)\s+([^\n]{2,160})""",
    )
    private val mustPattern = Regex(
        """(?i)^(?:always|every time|you must|from now on,?\s*(?:always|you must)?|in the future,?\s*(?:always|you must)?)\s+([^\n]{2,160})""",
    )

    fun extract(text: String): Map<String, String> {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_INPUT_LENGTH) return emptyMap()
        val facts = linkedMapOf<String, String>()

        fun putClean(key: String, value: String) {
            val cleaned = cleanValue(value)
            if (cleaned.isBlank()) return
            val storageKey = if (
                key.startsWith("rule.") ||
                key.startsWith("correction.") ||
                key.startsWith("preference.user_requirement")
            ) {
                "$key.${kotlin.math.abs(cleaned.hashCode())}"
            } else {
                key
            }
            facts[storageKey] = cleaned
        }

        explicitNamePattern.find(trimmed)?.groupValues?.get(1)?.let { candidate ->
            if (looksLikeName(candidate)) putClean("profile.name", candidate)
        } ?: conversationalNamePattern.find(trimmed)?.groupValues?.get(1)?.let { candidate ->
            if (looksLikeName(candidate)) putClean("profile.name", candidate)
        }

        locationPattern.find(trimmed)?.groupValues?.get(1)?.let {
            putClean("profile.location", it)
        }

        professionPattern.find(trimmed)?.groupValues?.get(1)?.let {
            putClean("profile.profession", it)
        } ?: workAsPattern.find(trimmed)?.groupValues?.get(1)?.let { candidate ->
            if (looksLikeProfession(candidate)) putClean("profile.profession", candidate)
        }

        val normalizedPreferenceText = durablePrefixPattern.replace(trimmed, "")
        preferencePattern.find(normalizedPreferenceText)?.groupValues?.get(1)?.let { candidate ->
            if (isDurablePreferenceCandidate(candidate, hasExplicitDurability(trimmed))) {
                putClean("profile.preferences", candidate)
            }
        }
        dislikePattern.find(normalizedPreferenceText)?.groupValues?.get(1)?.let { candidate ->
            if (isDurablePreferenceCandidate(candidate, hasExplicitDurability(trimmed))) {
                putClean("profile.dislikes", candidate)
            }
        }
        extractPreferredStyle(trimmed)?.let {
            putClean("profile.preferred_style", it)
        }
        rememberPattern.find(trimmed)?.groupValues?.get(1)?.let {
            putClean("profile.note", it)
        }

        extractDurableDoNotRule(trimmed)?.let {
            putClean("rule.user_do_not", it)
        }
        extractDurableMustRule(trimmed)?.let {
            putClean("rule.user_must", it)
        }
        extractCorrection(trimmed)?.let {
            putClean("correction.user_reported_behavior", it)
        }
        extractDurableRequirement(trimmed)?.let {
            putClean("preference.user_requirement", it)
        }

        val lower = trimmed.lowercase()
        val hasProhibitionOrComplaint = containsAny(
            lower,
            "don't", "do not", "stop", "unless i", "unnecessarily", "shouldn't", "should not",
            "keep searching", "always search", "just inspect",
        )
        if (
            containsAny(lower, "image", "picture", "photo", "screenshot") &&
            containsAny(lower, "web search", "search the web", "browse", "online lookup", "online research") &&
            hasProhibitionOrComplaint
        ) {
            putClean(
                "tool.policy.image_understanding.no_web_search",
                "Inspect user-provided images directly. Do not search the web unless the user explicitly requests online research.",
            )
        }
        if (
            containsAny(lower, "uibuild", "ui_builder", "ui builder", "build a page", "create a page", "building a page", "creating a page") &&
            containsAny(lower, "don't", "do not", "stop", "unless i", "unnecessarily", "just because", "ordinary question", "normal question")
        ) {
            putClean(
                "tool.policy.general.no_unrequested_ui_build",
                "Do not use UI Builder for ordinary chat or follow-ups; create or modify pages only when the user explicitly asks.",
            )
        }
        if (
            containsAny(lower, "switch roles", "switching roles", "change roles", "changing roles", "current role") &&
            containsAny(lower, "don't", "do not", "stop", "never", "randomly", "automatically", "unless i", "keep the current role")
        ) {
            putClean(
                "agent.behavior.keep_current_role",
                "Keep the current role unless the user explicitly requests a role change.",
            )
        }
        return facts
    }

    private fun cleanValue(value: String): String = value
        .trim()
        .trimStart('"', '\'', '“', '”')
        .trimEnd(' ', '.', ',', ';', ':', '!', '?', '"', '\'', '“', '”')
        .trim()
        .take(MAX_VALUE_LENGTH)

    private fun looksLikeName(candidate: String): Boolean {
        val cleaned = cleanValue(candidate)
        if (cleaned.isBlank() || cleaned.split(Regex("\\s+")).size > 3) return false
        val lower = cleaned.lowercase()
        return !containsAny(
            lower,
            "developer", "engineer", "lawyer", "attorney", "student", "freelancer", "designer",
            "tired", "working", "using ", "from ", "at ", "in ", "happy", "sad", "busy",
        ) && cleaned.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' || it == '’' }
    }

    private fun looksLikeProfession(candidate: String): Boolean {
        val lower = cleanValue(candidate).lowercase()
        if (lower.isBlank()) return false
        return occupationTitlePattern.matches(lower)
    }

    private fun isDurablePreferenceCandidate(candidate: String, explicitDurability: Boolean): Boolean {
        if (explicitDurability) return true
        val lower = cleanValue(candidate).lowercase()
        if (lower.isBlank()) return false
        if (lower in setOf("this", "that", "it", "this one", "that one")) return false
        return !lower.startsWith("this ") &&
            !lower.startsWith("that ") &&
            !lower.startsWith("the current ") &&
            !lower.startsWith("what you ") &&
            !containsAny(lower, "for this task", "right now", "today", "what you did here", "what you just")
    }

    private fun hasExplicitDurability(text: String): Boolean = containsAny(
        text.lowercase(),
        "from now on", "going forward", "in the future", "always",
    )

    private fun extractPreferredStyle(text: String): String? {
        val lower = text.lowercase()
        val durable = containsAny(lower, "from now on", "going forward", "in the future", "always")
        val style = containsAny(
            lower,
            "answer", "answers", "response", "responses", "tone", "concisely", "concise", "direct", "short", "brief", "technical", "detailed",
        )
        if (!durable || !style) return null
        return durablePrefixPattern.replace(text, "")
            .replace(Regex("(?i)^please\\s+"), "")
            .replace(Regex("(?i)^always\\s+"), "")
    }

    private fun extractDurableDoNotRule(text: String): String? {
        val normalized = durablePrefixPattern.replace(text, "")
        val match = canonicalDoNotPattern.find(normalized) ?: return null
        val lower = normalized.lowercase()
        val durable = lower.startsWith("never ") ||
            text.lowercase().startsWith("from now on") ||
            containsAny(lower, " again", "unless i ask", "unless i explicitly", "automatically", "randomly", "every time")
        if (!durable || lower.contains("don't like") || lower.contains("do not like")) return null
        val directive = lower.removePrefix("please ")
        val verb = when {
            directive.startsWith("never ") -> "Never"
            directive.startsWith("stop ") -> "Stop"
            else -> "Do not"
        }
        return "$verb ${match.groupValues[1]}"
    }

    private fun extractDurableMustRule(text: String): String? {
        val match = mustPattern.find(text) ?: return null
        return "Always ${match.groupValues[1]}"
    }

    private fun extractCorrection(text: String): String? {
        val lower = text.lowercase()
        if (!Regex("""(?i)^you\s+(?:always|keep|constantly|often)\s+""").containsMatchIn(text)) return null
        val negativeContext = containsAny(
            lower,
            "don't want", "do not want", "unnecessarily", "instead of", "without asking", "wrong",
            "ask me for confirmation", "asks me for confirmation", "too often", "again",
        )
        return text.takeIf { negativeContext }
    }

    private fun extractDurableRequirement(text: String): String? {
        val lower = text.lowercase()
        val requirementLead = lower.startsWith("i want you to") ||
            lower.startsWith("i require you to") ||
            lower.startsWith("my requirement is")
        val durable = containsAny(lower, "always", "from now on", "in the future", "every time", "never")
        if (!requirementLead || !durable) return null
        return text
            .replace(Regex("(?i)^I want you to\\s+"), "")
            .replace(Regex("(?i)^I require you to\\s+"), "")
            .replace(Regex("(?i)^My requirement is(?: that)?\\s+"), "")
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any(text::contains)

    private val occupationTitlePattern = Regex(
        """(?i)^(?:(?:senior|junior|lead|principal|licensed)\s+){0,2}(?:(?:software|civil|mechanical|electrical|data)\s+)?(?:engineer|developer|designer|lawyer|attorney|teacher|physician|doctor|student|freelancer|accountant|nurse|writer|researcher|consultant)$""",
    )
}
