package com.mobileclaw.agent

/** Canonical, machine-significant headings for persisted role workspace Markdown. */
object RoleWorkspaceMarkdownSchema {
    object Core {
        const val ROLE_IDENTITY = "Role Identity"
        const val EXECUTION_PRINCIPLES = "Execution Principles"
        const val WORKING_METHOD = "Working Method"
        const val WORKING_BOUNDARIES = "Working Boundaries"
    }

    object Skills {
        const val DEFAULT_ROLE_SKILLS = "Default Role Skills"
        const val ON_DEMAND_SKILL_POLICY = "On-Demand Skill Policy"
        const val SKILL_SELECTION_HABITS = "Skill Selection Habits"
        const val CURRENT_SKILL_INDEX = "Current Skill Index"
    }

    object Memory {
        const val STABLE_PREFERENCES = "Stable Preferences"
        const val MEMORY_TRIGGERS = "Memory Triggers"
        const val TASK_EXPERIENCE = "Task Experience"
        const val USER_COLLABORATION_PREFERENCES = "User Collaboration Preferences"
    }

    object Model {
        const val CURRENT_CONFIGURATION = "Current Configuration"
        const val NOTES = "Notes"
    }

    object ChatProtocol {
        const val RUNTIME_CONTRACT = "Runtime Contract"
        const val INPUT_UNDERSTANDING = "Input Understanding"
        const val CONTEXT_READING = "Context Reading"
        const val MEMORY_POLICY = "Memory Policy"
        const val SKILL_POLICY = "Skill Policy"
        const val RESPONSE_POLICY = "Response Policy"
        const val PERSISTENCE_POLICY = "Persistence Policy"
        const val ROLE_SPECIFIC_RUNTIME_HINT = "Role-Specific Runtime Hint"
    }

    fun heading(section: String): String = "## $section"
}

/**
 * Upgrade adapter for role workspaces written before the English schema.
 *
 * Legacy headings are represented as Unicode code points so compatibility does not reintroduce
 * localized protocol literals into shipped source. This adapter can be removed after the legacy
 * workspace upgrade window closes.
 */
object RoleWorkspaceMarkdownMigrator {
    fun migrate(fileName: String, markdown: String): String {
        val headings = legacyMappings[fileName].orEmpty()
        return migrateSections(markdown, headings)
    }

    fun migrateJournal(markdown: String): String {
        val lines = markdown.lines().toMutableList()
        val legacySuffix = legacy(0x5de5, 0x4f5c, 0x65e5, 0x5fd7)
        val first = lines.firstOrNull().orEmpty()
        if (!first.startsWith("# ") || !first.endsWith(legacySuffix)) return markdown
        lines[0] = first.removeSuffix(legacySuffix).trimEnd() + " Work Log"
        return lines.joinToString("\n").preserveFinalNewline(markdown)
    }

    private fun migrateSections(markdown: String, mappings: Map<String, String>): String {
        if (mappings.isEmpty()) return markdown
        val lines = markdown.lines()
        if (lines.none { line -> mappings.containsKey(sectionTitle(line)) }) return markdown

        val preamble = mutableListOf<String>()
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        lines.forEach { line ->
            val title = sectionTitle(line)
            if (title != null) {
                val canonical = mappings[title] ?: title
                current = sections.getOrPut(canonical) { mutableListOf() }
                if (current!!.isNotEmpty() && current!!.last().isNotBlank()) current!!.add("")
            } else {
                (current ?: preamble).add(line)
            }
        }

        return buildString {
            append(preamble.joinToString("\n").trimEnd())
            sections.forEach { (title, body) ->
                if (isNotEmpty()) append("\n\n")
                append("## ").append(title)
                val content = body.joinToString("\n").trim()
                if (content.isNotEmpty()) append('\n').append(content)
            }
        }.trimEnd().plus("\n")
    }

    private fun sectionTitle(line: String): String? =
        line.takeIf { it.startsWith("## ") }?.removePrefix("## ")?.trim()

    private fun String.preserveFinalNewline(original: String): String =
        if (original.endsWith("\n") && !endsWith("\n")) "$this\n" else this

    private fun legacy(vararg codePoints: Int): String =
        codePoints.joinToString("") { String(Character.toChars(it)) }

    private val legacyMappings: Map<String, Map<String, String>> by lazy {
        mapOf(
            RoleWorkspaceStore.CORE_MD to linkedMapOf(
                legacy(0x5b9a, 0x4f4d) to RoleWorkspaceMarkdownSchema.Core.ROLE_IDENTITY,
                legacy(0x6267, 0x884c, 0x539f, 0x5219) to RoleWorkspaceMarkdownSchema.Core.EXECUTION_PRINCIPLES,
                legacy(0x5de5, 0x4f5c, 0x65b9, 0x6cd5) to RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD,
                legacy(0x5de5, 0x4f5c, 0x8fb9, 0x754c) to RoleWorkspaceMarkdownSchema.Core.WORKING_BOUNDARIES,
            ),
            RoleWorkspaceStore.SKILLS_MD to linkedMapOf(
                legacy(0x89d2, 0x8272, 0x9ed8, 0x8ba4, 0x6280, 0x80fd) to RoleWorkspaceMarkdownSchema.Skills.DEFAULT_ROLE_SKILLS,
                legacy(0x6309, 0x9700, 0x8bfb, 0x53d6, 0x539f, 0x5219) to RoleWorkspaceMarkdownSchema.Skills.ON_DEMAND_SKILL_POLICY,
                legacy(0x6280, 0x80fd, 0x9009, 0x62e9, 0x4e60, 0x60ef) to RoleWorkspaceMarkdownSchema.Skills.SKILL_SELECTION_HABITS,
                legacy(0x5f53, 0x524d, 0x6280, 0x80fd, 0x7d22, 0x5f15, 0x6982, 0x89c8) to RoleWorkspaceMarkdownSchema.Skills.CURRENT_SKILL_INDEX,
            ),
            RoleWorkspaceStore.MEMORY_MD to linkedMapOf(
                legacy(0x7a33, 0x5b9a, 0x504f, 0x597d) to RoleWorkspaceMarkdownSchema.Memory.STABLE_PREFERENCES,
                legacy(0x8bb0, 0x5fc6, 0x89e6, 0x53d1) to RoleWorkspaceMarkdownSchema.Memory.MEMORY_TRIGGERS,
                legacy(0x4efb, 0x52a1, 0x7ecf, 0x9a8c) to RoleWorkspaceMarkdownSchema.Memory.TASK_EXPERIENCE,
                legacy(0x7528, 0x6237, 0x534f, 0x4f5c, 0x4e60, 0x60ef) to RoleWorkspaceMarkdownSchema.Memory.USER_COLLABORATION_PREFERENCES,
            ),
            RoleWorkspaceStore.MODEL_MD to linkedMapOf(
                legacy(0x5f53, 0x524d, 0x8bb0, 0x5f55) to RoleWorkspaceMarkdownSchema.Model.CURRENT_CONFIGURATION,
                legacy(0x6700, 0x8fd1, 0x4e00, 0x6b21, 0x8fd0, 0x884c) to RoleWorkspaceMarkdownSchema.Model.CURRENT_CONFIGURATION,
                legacy(0x8bf4, 0x660e) to RoleWorkspaceMarkdownSchema.Model.NOTES,
            ),
            RoleWorkspaceStore.CHAT_PROTOCOL_MD to linkedMapOf(
                legacy(0x8fd0, 0x884c, 0x65f6, 0x5951, 0x7ea6) to RoleWorkspaceMarkdownSchema.ChatProtocol.RUNTIME_CONTRACT,
                legacy(0x8f93, 0x5165, 0x7406, 0x89e3) to RoleWorkspaceMarkdownSchema.ChatProtocol.INPUT_UNDERSTANDING,
                legacy(0x4e0a, 0x4e0b, 0x6587, 0x8bfb, 0x53d6) to RoleWorkspaceMarkdownSchema.ChatProtocol.CONTEXT_READING,
                legacy(0x8bb0, 0x5fc6, 0x7b56, 0x7565) to RoleWorkspaceMarkdownSchema.ChatProtocol.MEMORY_POLICY,
                legacy(0x6280, 0x80fd, 0x7b56, 0x7565) to RoleWorkspaceMarkdownSchema.ChatProtocol.SKILL_POLICY,
                legacy(0x56de, 0x590d, 0x7b56, 0x7565) to RoleWorkspaceMarkdownSchema.ChatProtocol.RESPONSE_POLICY,
                legacy(0x6301, 0x4e45, 0x5316, 0x7b56, 0x7565) to RoleWorkspaceMarkdownSchema.ChatProtocol.PERSISTENCE_POLICY,
            ),
        )
    }
}
