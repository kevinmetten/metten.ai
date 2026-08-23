package com.mobileclaw.agent

import com.mobileclaw.skill.SkillToolCategory
import com.mobileclaw.skill.SkillToolTaxonomy

enum class ChannelType {
    CHAT,
    INFO,
    MEMORY,
    SKILL,
    SELF_EVOLUTION,
    PLAN,
    ARTIFACT,
    PHONE,
    WEB,
    MEDIA,
    VPN,
    CODE,
}

data class ChannelDecision(
    val primary: ChannelType,
    val supporting: List<ChannelType>,
    val toolHints: List<String>,
    val userVisibleSummary: String,
)

class ChannelRouter {
    fun decide(
        taskType: TaskType,
        goal: String = "",
        hasImage: Boolean = false,
        hasFile: Boolean = false,
        roleId: String = "general",
        @Suppress("UNUSED_PARAMETER") language: String = "en",
        aiPrimary: ChannelType? = null,
        aiSupporting: List<ChannelType> = emptyList(),
        aiToolHints: List<String> = emptyList(),
        aiUserVisibleSteps: List<String> = emptyList(),
    ): ChannelDecision {
        val normalizedGoal = goal.lowercase()
        val primary = aiPrimary ?: when (taskType) {
            TaskType.PHONE_CONTROL -> ChannelType.PHONE
            TaskType.WEB_RESEARCH -> ChannelType.WEB
            TaskType.FILE_CREATE -> ChannelType.ARTIFACT
            TaskType.APP_BUILD -> ChannelType.ARTIFACT
            TaskType.IMAGE_GENERATION -> ChannelType.MEDIA
            TaskType.VPN_CONTROL -> ChannelType.VPN
            TaskType.SKILL_MANAGEMENT -> if (isSelfEvolutionGoal(normalizedGoal)) ChannelType.SELF_EVOLUTION else ChannelType.SKILL
            TaskType.CODE_EXECUTION -> ChannelType.CODE
            TaskType.CHAT, TaskType.GENERAL -> when {
                isMemoryGoal(normalizedGoal) -> ChannelType.MEMORY
                isSelfEvolutionGoal(normalizedGoal) -> ChannelType.SELF_EVOLUTION
                hasImage && !hasFile -> ChannelType.CHAT
                else -> ChannelType.CHAT
            }
        }

        val supporting = linkedSetOf<ChannelType>()
        supporting += aiSupporting
        supporting += ChannelType.MEMORY
        if (roleId.isNotBlank() && roleId != "general") supporting += ChannelType.SELF_EVOLUTION
        if (taskType == TaskType.GENERAL && (isMemoryGoal(normalizedGoal) || isSelfEvolutionGoal(normalizedGoal))) {
            supporting += ChannelType.SKILL
        }
        when (primary) {
            ChannelType.PHONE -> supporting += ChannelType.PLAN
            ChannelType.WEB -> supporting += ChannelType.PLAN
            ChannelType.ARTIFACT -> supporting += ChannelType.SKILL
            ChannelType.SKILL -> supporting += ChannelType.SELF_EVOLUTION
            ChannelType.SELF_EVOLUTION -> supporting += ChannelType.SKILL
            ChannelType.INFO -> Unit
            ChannelType.CHAT -> {
                if (taskType == TaskType.GENERAL) {
                    supporting += ChannelType.SKILL
                    supporting += ChannelType.ARTIFACT
                }
            }
            ChannelType.PLAN, ChannelType.MEDIA, ChannelType.VPN, ChannelType.CODE, ChannelType.MEMORY -> Unit
        }

        val toolHints = (aiToolHints + (listOf(primary) + supporting).flatMap { toolHintsFor(it) }).distinct()

        return ChannelDecision(
            primary = primary,
            supporting = supporting.filterNot { it == primary },
            toolHints = toolHints,
            userVisibleSummary = aiUserVisibleSteps.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                ?: buildUserSummary(primary, supporting.filterNot { it == primary }),
        )
    }

    private fun toolHintsFor(channel: ChannelType): List<String> = when (channel) {
        ChannelType.PHONE -> SkillToolTaxonomy.idsFor(SkillToolCategory.PHONE, SkillToolCategory.VPN)
        ChannelType.INFO -> emptyList()
        ChannelType.WEB -> SkillToolTaxonomy.idsFor(SkillToolCategory.WEB, SkillToolCategory.VPN)
        ChannelType.ARTIFACT -> SkillToolTaxonomy.idsFor(SkillToolCategory.ARTIFACT, SkillToolCategory.SKILL)
        ChannelType.MEDIA -> SkillToolTaxonomy.idsFor(SkillToolCategory.MEDIA)
        ChannelType.VPN -> SkillToolTaxonomy.idsFor(SkillToolCategory.VPN)
        ChannelType.CODE -> SkillToolTaxonomy.idsFor(SkillToolCategory.CODE, SkillToolCategory.ARTIFACT)
        ChannelType.SKILL -> SkillToolTaxonomy.idsFor(SkillToolCategory.SKILL, SkillToolCategory.SELF_EVOLUTION)
        ChannelType.SELF_EVOLUTION -> SkillToolTaxonomy.idsFor(SkillToolCategory.SELF_EVOLUTION, SkillToolCategory.SKILL)
        ChannelType.MEMORY -> SkillToolTaxonomy.idsFor(SkillToolCategory.MEMORY)
        ChannelType.CHAT -> SkillToolTaxonomy.idsFor(SkillToolCategory.CHAT)
        ChannelType.PLAN -> emptyList()
    }

    private fun isMemoryGoal(text: String): Boolean =
        text.matchesAny(
            "remember this", "remember that", "remember my", "save this preference", "save my preference",
            "my preference", "use this as the default", "make this the default", "always do this",
            "from now on", "forget this", "forget that", "delete this memory", "remove this memory",
            "delete the memory", "remove the memory",
        ) ||
            text.contains("user_config") ||
            text.contains("user_profile")

    private fun isSelfEvolutionGoal(text: String): Boolean {
        if (text.matchesAny(
                "improve yourself", "upgrade yourself", "repair yourself", "fix yourself",
                "fix your behavior", "repair your behavior", "improve your behavior", "change your behavior",
            )) return true
        val changeIntent = text.matchesAny("update", "change", "modify", "improve", "repair", "fix", "upgrade")
        val ownedCapability = text.matchesAny(
            "your skills", "your tools", "your capabilities", "your role configuration", "your persona configuration",
            "the agent's skills", "the agent's tools", "the agent's capabilities", "agent behavior",
        )
        return changeIntent && ownedCapability
    }

    private fun buildUserSummary(primary: ChannelType, supporting: List<ChannelType>): String {
        val primaryLabel = channelLabel(primary)
        val supportLabel = supporting.joinToString(", ") { channelLabel(it) }
        return when {
            supportLabel.isBlank() -> "Primary channel: $primaryLabel"
            else -> "Primary channel: $primaryLabel; supporting channels: $supportLabel"
        }
    }

    private fun channelLabel(channel: ChannelType): String = when (channel) {
        ChannelType.CHAT -> "Chat"
        ChannelType.INFO -> "Capability directory"
        ChannelType.MEMORY -> "Memory"
        ChannelType.SKILL -> "Skills"
        ChannelType.SELF_EVOLUTION -> "Self-improvement"
        ChannelType.PLAN -> "Planning"
        ChannelType.ARTIFACT -> "Artifacts"
        ChannelType.PHONE -> "Phone control"
        ChannelType.WEB -> "Web research"
        ChannelType.MEDIA -> "Media generation"
        ChannelType.VPN -> "VPN"
        ChannelType.CODE -> "Code execution"
    }

    private fun String.matchesAny(vararg phrases: String): Boolean = phrases.any { phrase ->
        val pattern = phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        Regex("(?<![a-z0-9_])$pattern(?![a-z0-9_])").containsMatchIn(this)
    }
}
