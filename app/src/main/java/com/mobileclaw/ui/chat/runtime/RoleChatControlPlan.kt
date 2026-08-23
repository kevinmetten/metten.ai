package com.mobileclaw.ui.chat.runtime

data class RoleChatControlPlan(
    val roleProfile: RoleRuntimeProfile,
    val executionModeHint: ChatExecutionMode? = null,
    val contextPolicy: RoleContextPolicy = RoleContextPolicy(),
    val toolPolicy: RoleToolPolicy = RoleToolPolicy(),
    val intentPolicy: RoleIntentPolicy = RoleIntentPolicy(),
    val responsePolicy: RoleResponsePolicy = RoleResponsePolicy(),
    val visibilityPolicy: RoleVisibilityPolicy = RoleVisibilityPolicy(),
    val persistencePolicy: RolePersistencePolicy = RolePersistencePolicy(),
    val promptDirectives: String = "",
) {
    fun toPromptBlock(maxChars: Int = 1800): String = buildString {
        appendLine("## Role Chat Control Plan")
        appendLine("- Role id: ${roleProfile.roleId}")
        executionModeHint?.let { appendLine("- Execution mode hint: $it") }
        appendLine("- Context budget: ${contextPolicy.maxRoleContextChars}")
        appendLine("- Include user memory: ${contextPolicy.includeUserMemory}")
        appendLine("- Include recent messages: ${contextPolicy.includeRecentMessages}")
        appendLine("- Include workspace summary: ${contextPolicy.includeWorkspaceSummary}")
        appendLine("- Short follow-up handling: ${intentPolicy.shortFollowUpMode}")
        appendLine("- Current message priority: ${intentPolicy.currentMessagePriority}")
        appendLine("- Response style: ${responsePolicy.style}")
        appendLine("- Avoid capability listing: ${responsePolicy.avoidCapabilityListing}")
        appendLine("- Allow UI blocks: ${responsePolicy.allowUiBlocks}")
        if (contextPolicy.readRoleFiles.isNotEmpty()) {
            appendLine("- Role files to read: ${contextPolicy.readRoleFiles.joinToString(", ")}")
        }
        if (toolPolicy.preferredToolIds.isNotEmpty()) {
            appendLine("- Preferred tools: ${toolPolicy.preferredToolIds.joinToString(", ")}")
        }
        if (toolPolicy.blockedToolIds.isNotEmpty()) {
            appendLine("- Blocked tools: ${toolPolicy.blockedToolIds.joinToString(", ")}")
        }
        appendLine("- Allow MCP: ${toolPolicy.allowMcp}")
        appendLine("- Confirm external tools: ${toolPolicy.requireConfirmationForExternalTools}")
        appendLine("- Role memory write: ${persistencePolicy.allowRoleMemoryWrite}")
        appendLine("- User memory write: ${persistencePolicy.allowUserMemoryWrite}")
        if (promptDirectives.isNotBlank()) {
            appendLine()
            appendLine("### Prompt Directives")
            appendLine(promptDirectives.take(900))
        }
    }.trim().take(maxChars)

    companion object {
        fun safeDefault(profile: RoleRuntimeProfile): RoleChatControlPlan {
            return RoleChatControlPlan(
                roleProfile = profile,
                contextPolicy = RoleContextPolicy(readRoleFiles = listOf("core.md", "chat_protocol.md")),
                toolPolicy = RoleToolPolicy(
                    preferredToolIds = profile.role.forcedSkillIds,
                ),
                promptDirectives = RoleChatControlPlanCompiler.promptDirectives(profile.protocol),
            )
        }
    }
}

enum class RoleExecutionPreference {
    AUTO,
    DIRECT_FIRST,
    AGENT_FIRST,
}

/** Stable editor-managed execution preference stored inside the Skill Policy section. */
object RoleExecutionPreferenceProtocol {
    private const val FIELD = "Execution mode"
    private val fieldPattern = Regex(
        pattern = """^\s*-?\s*$FIELD\s*:\s*(auto|direct_first|agent_first)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parse(skillPolicy: String, responsePolicy: String = ""): RoleExecutionPreference {
        skillPolicy.lineSequence().forEach { line ->
            val value = fieldPattern.matchEntire(line)?.groupValues?.getOrNull(1)?.lowercase()
                ?: return@forEach
            return when (value) {
                "direct_first" -> RoleExecutionPreference.DIRECT_FIRST
                "agent_first" -> RoleExecutionPreference.AGENT_FIRST
                else -> RoleExecutionPreference.AUTO
            }
        }

        val prose = "$skillPolicy\n$responsePolicy".lowercase()
        return when {
            listOf("prefer agent", "agent first", "force agent").any(prose::contains) ->
                RoleExecutionPreference.AGENT_FIRST
            listOf("prefer direct", "direct chat first").any(prose::contains) ->
                RoleExecutionPreference.DIRECT_FIRST
            else -> RoleExecutionPreference.AUTO
        }
    }

    fun update(skillPolicy: String, preference: RoleExecutionPreference): String {
        val kept = skillPolicy.lineSequence()
            .filterNot { fieldPattern.matches(it) }
            .joinToString("\n")
            .trimEnd()
        val managedLine = when (preference) {
            RoleExecutionPreference.AUTO -> ""
            RoleExecutionPreference.DIRECT_FIRST -> "- $FIELD: direct_first"
            RoleExecutionPreference.AGENT_FIRST -> "- $FIELD: agent_first"
        }
        return listOf(kept, managedLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }
}

object RoleChatControlPlanCompiler {
    fun compile(profile: RoleRuntimeProfile): RoleChatControlPlan {
        val protocol = profile.protocol
        val allProtocolText = listOf(
            protocol.inputUnderstanding,
            protocol.contextReading,
            protocol.memoryPolicy,
            protocol.skillPolicy,
            protocol.responsePolicy,
            protocol.persistencePolicy,
        ).joinToString("\n").lowercase()
        val readFiles = buildList {
            add("core.md")
            add("chat_protocol.md")
            if (allProtocolText.contains("memory.md") || protocol.memoryPolicy.isNotBlank()) add("memory.md")
            if (allProtocolText.contains("model.md")) add("model.md")
            if (allProtocolText.contains("skills.md") || protocol.skillPolicy.isNotBlank()) add("skills.md")
            if (allProtocolText.contains("skill_index.md") || allProtocolText.contains("skill index")) add("skill_index.md")
        }.distinct()
        val preferredToolIds = (profile.role.forcedSkillIds + extractToolIds(protocol.skillPolicy, profile))
            .distinct()
        val blockedToolIds = extractListAfterLabels(
            text = protocol.skillPolicy,
            labels = listOf("Blocked tools"),
        )
        val roleMemoryAllowed = protocol.memoryPolicy.isNotBlank() ||
            protocol.persistencePolicy.contains("memory.md", ignoreCase = true)
        val journalAllowed = protocol.persistencePolicy.contains("journal.md", ignoreCase = true)
        val contextPolicy = RoleContextPolicy(
            readRoleFiles = readFiles,
            includeUserMemory = !allProtocolText.contains("no user memory"),
            includeRecentMessages = protocol.inputUnderstanding.isNotBlank() &&
                !allProtocolText.contains("ignore recent"),
            includeWorkspaceSummary = !allProtocolText.contains("no workspace"),
            maxRoleContextChars = when {
                readFiles.size >= 5 -> 6200
                readFiles.size >= 4 -> 5200
                else -> 4200
            },
        )
        val toolPolicy = RoleToolPolicy(
            preferredToolIds = preferredToolIds,
            blockedToolIds = blockedToolIds,
            allowMcp = !allProtocolText.contains("disable mcp"),
            requireConfirmationForExternalTools = false,
        )
        val intentPolicy = RoleIntentPolicy(
            shortFollowUpMode = when {
                allProtocolText.contains("ignore recent") -> "latest_message_only"
                protocol.inputUnderstanding.isNotBlank() -> "resolve_from_recent_context"
                else -> "standard"
            },
            currentMessagePriority = !allProtocolText.contains("history first"),
            artifactReferenceMode = if (
                allProtocolText.contains("active artifact") ||
                allProtocolText.contains("workspace")
            ) "active_artifact_aware" else "standard",
        )
        val responsePolicy = RoleResponsePolicy(
            style = extractResponseStyle(protocol.responsePolicy),
            avoidCapabilityListing = !allProtocolText.contains("list capabilities freely"),
            allowUiBlocks = allProtocolText.contains("ui block") ||
                allProtocolText.contains("interactive block") ||
                allProtocolText.contains("interactive form"),
            completionSummaryMode = when {
                allProtocolText.contains("low interruption") || allProtocolText.contains("compact summary") -> "compact"
                allProtocolText.contains("detailed summary") || allProtocolText.contains("explain steps") -> "detailed"
                else -> "balanced"
            },
        )
        val visibilityPolicy = RoleVisibilityPolicy(
            showTimelineForToolCalls = !allProtocolText.contains("silent tools") &&
                !allProtocolText.contains("hide tool timeline"),
            showTimelineForMemoryWrites = (roleMemoryAllowed || journalAllowed) &&
                !allProtocolText.contains("hide memory timeline"),
            exposeTraceByDefault = allProtocolText.contains("expose trace"),
        )
        val persistencePolicy = RolePersistencePolicy(
            writeJournalOnCompletion = journalAllowed,
            allowRoleMemoryWrite = roleMemoryAllowed,
            allowUserMemoryWrite = !allProtocolText.contains("no user memory write"),
            memoryImportanceThreshold = extractMemoryThreshold(protocol).ifBlank {
                "stable_preference_or_reusable_working_habit"
            },
        )
        return RoleChatControlPlan(
            roleProfile = profile,
            executionModeHint = when (
                RoleExecutionPreferenceProtocol.parse(protocol.skillPolicy, protocol.responsePolicy)
            ) {
                RoleExecutionPreference.AUTO -> null
                RoleExecutionPreference.DIRECT_FIRST -> ChatExecutionMode.DIRECT_CHAT
                RoleExecutionPreference.AGENT_FIRST -> ChatExecutionMode.AGENT
            },
            contextPolicy = contextPolicy,
            toolPolicy = toolPolicy,
            intentPolicy = intentPolicy,
            responsePolicy = responsePolicy,
            visibilityPolicy = visibilityPolicy,
            persistencePolicy = persistencePolicy,
            promptDirectives = promptDirectives(protocol),
        )
    }

    fun promptDirectives(protocol: RoleExecutionProtocol): String = listOf(
        protocol.inputUnderstanding.takeIf { it.isNotBlank() }?.let { "Input Understanding:\n$it" },
        protocol.contextReading.takeIf { it.isNotBlank() }?.let { "Context Reading:\n$it" },
        protocol.memoryPolicy.takeIf { it.isNotBlank() }?.let { "Memory Policy:\n$it" },
        protocol.skillPolicy.takeIf { it.isNotBlank() }?.let { "Skill Policy:\n$it" },
        protocol.responsePolicy.takeIf { it.isNotBlank() }?.let { "Response Policy:\n$it" },
        protocol.persistencePolicy.takeIf { it.isNotBlank() }?.let { "Persistence Policy:\n$it" },
    )
        .filterNotNull()
        .joinToString("\n\n")

    private fun extractToolIds(skillPolicy: String, profile: RoleRuntimeProfile): List<String> {
        if (skillPolicy.isBlank()) return emptyList()
        val knownIds = profile.skills.map { it.id }.toSet()
        val tokens = Regex("""[`'"]?([a-zA-Z][a-zA-Z0-9_.-]{2,})[`'"]?""")
            .findAll(skillPolicy)
            .map { it.groupValues[1] }
            .toList()
        return tokens.filter { it in knownIds }.distinct()
    }

    private fun extractListAfterLabels(text: String, labels: List<String>): List<String> {
        val lines = text.lines()
        val labelIndex = lines.indexOfFirst { line -> labels.any { label -> line.contains(label, ignoreCase = true) } }
        if (labelIndex < 0) return emptyList()
        return lines.drop(labelIndex + 1)
            .takeWhile { it.trim().startsWith("-") || it.trim().startsWith("*") || it.contains(",") }
            .flatMap { line -> line.removePrefix("-").removePrefix("*").split(",") }
            .map { it.trim().trim('`', '\'', '"') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractMemoryThreshold(protocol: RoleExecutionProtocol): String {
        val line = protocol.persistencePolicy
            .lines()
            .firstOrNull { it.contains("threshold", ignoreCase = true) }
            .orEmpty()
        return line.substringAfter(":").trim()
    }

    private fun extractResponseStyle(responsePolicy: String): String {
        val text = responsePolicy.lowercase()
        return when {
            text.contains("directly") || text.contains("concise") -> "concise_direct"
            text.contains("detailed") || text.contains("thorough") -> "thorough"
            text.contains("warm") -> "warm"
            responsePolicy.isNotBlank() -> "concise_direct"
            else -> "role_default"
        }
    }
}

data class RoleContextPolicy(
    val readRoleFiles: List<String> = emptyList(),
    val includeUserMemory: Boolean = true,
    val includeRecentMessages: Boolean = false,
    val includeWorkspaceSummary: Boolean = true,
    val maxRoleContextChars: Int = 4200,
)

data class RoleToolPolicy(
    val preferredToolIds: List<String> = emptyList(),
    val blockedToolIds: List<String> = emptyList(),
    val allowMcp: Boolean = true,
    val requireConfirmationForExternalTools: Boolean = false,
)

data class RoleIntentPolicy(
    val shortFollowUpMode: String = "standard",
    val currentMessagePriority: Boolean = true,
    val artifactReferenceMode: String = "standard",
)

data class RoleResponsePolicy(
    val style: String = "role_default",
    val avoidCapabilityListing: Boolean = true,
    val allowUiBlocks: Boolean = false,
    val completionSummaryMode: String = "balanced",
)

data class RoleVisibilityPolicy(
    val showTimelineForToolCalls: Boolean = true,
    val showTimelineForMemoryWrites: Boolean = true,
    val exposeTraceByDefault: Boolean = false,
)

data class RolePersistencePolicy(
    val writeJournalOnCompletion: Boolean = false,
    val allowRoleMemoryWrite: Boolean = false,
    val allowUserMemoryWrite: Boolean = true,
    val memoryImportanceThreshold: String = "stable_preference_or_reusable_working_habit",
)
