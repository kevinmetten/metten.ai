package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleWorkspaceMarkdownSchemaTest {
    @Test
    fun `canonical schema defines every generated workspace section`() {
        assertEquals("## Role Identity", RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.ROLE_IDENTITY))
        assertEquals("## Execution Principles", RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.EXECUTION_PRINCIPLES))
        assertEquals("## Working Method", RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD))
        assertEquals("## Working Boundaries", RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.WORKING_BOUNDARIES))
        assertEquals("Default Role Skills", RoleWorkspaceMarkdownSchema.Skills.DEFAULT_ROLE_SKILLS)
        assertEquals("On-Demand Skill Policy", RoleWorkspaceMarkdownSchema.Skills.ON_DEMAND_SKILL_POLICY)
        assertEquals("Skill Selection Habits", RoleWorkspaceMarkdownSchema.Skills.SKILL_SELECTION_HABITS)
        assertEquals("Current Skill Index", RoleWorkspaceMarkdownSchema.Skills.CURRENT_SKILL_INDEX)
        assertEquals("Stable Preferences", RoleWorkspaceMarkdownSchema.Memory.STABLE_PREFERENCES)
        assertEquals("Memory Triggers", RoleWorkspaceMarkdownSchema.Memory.MEMORY_TRIGGERS)
        assertEquals("Task Experience", RoleWorkspaceMarkdownSchema.Memory.TASK_EXPERIENCE)
        assertEquals("User Collaboration Preferences", RoleWorkspaceMarkdownSchema.Memory.USER_COLLABORATION_PREFERENCES)
        assertEquals("Current Configuration", RoleWorkspaceMarkdownSchema.Model.CURRENT_CONFIGURATION)
        assertEquals("Notes", RoleWorkspaceMarkdownSchema.Model.NOTES)
        assertEquals("Persistence Policy", RoleWorkspaceMarkdownSchema.ChatProtocol.PERSISTENCE_POLICY)
    }

    @Test
    fun `legacy core and memory headings migrate while bodies and custom sections survive`() {
        val workingMethod = chars(0x5de5, 0x4f5c, 0x65b9, 0x6cd5)
        val stablePreferences = chars(0x7a33, 0x5b9a, 0x504f, 0x597d)
        val core = "# Custom Role\n\n## $workingMethod\nUser-authored method.\n\n## Custom Section\nKeep this.\n"
        val memory = "# Memory\n\n## $stablePreferences\nUser-authored preference.\n"

        val migratedCore = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CORE_MD, core)
        val migratedMemory = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.MEMORY_MD, memory)

        assertTrue(migratedCore.contains("## Working Method\nUser-authored method."))
        assertTrue(migratedCore.contains("## Custom Section\nKeep this."))
        assertFalse(migratedCore.contains(workingMethod))
        assertTrue(migratedMemory.contains("## Stable Preferences\nUser-authored preference."))
        assertFalse(migratedMemory.contains(stablePreferences))
    }

    @Test
    fun `legacy chat protocol headings migrate to parser schema`() {
        val input = chars(0x8f93, 0x5165, 0x7406, 0x89e3)
        val persistence = chars(0x6301, 0x4e45, 0x5316, 0x7b56, 0x7565)
        val markdown = "# Chat Execution Protocol\n\n## $input\nInput body.\n\n## $persistence\nPersistence body.\n"

        val migrated = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, markdown)

        assertTrue(migrated.contains("## Input Understanding\nInput body."))
        assertTrue(migrated.contains("## Persistence Policy\nPersistence body."))
    }

    @Test
    fun `stock body line migrates without replacing custom additions`() {
        val stockSkillLine = chars(
            0x2d, 0x20, 0x6240, 0x6709, 0x6280, 0x80fd, 0x90fd, 0x53ef, 0x4ee5, 0x6309,
            0x9700, 0x53d1, 0x73b0, 0x548c, 0x8bfb, 0x53d6, 0xff0c, 0x4f46, 0x5fc5, 0x987b,
            0x5148, 0x5224, 0x65ad, 0x4efb, 0x52a1, 0x662f, 0x5426, 0x771f, 0x7684, 0x9700,
            0x8981, 0x6280, 0x80fd, 0x3002,
        )
        val markdown = "# Chat Execution Protocol\n\n## Skill Policy\n$stockSkillLine\nCustom user-authored instruction.\n"

        val migrated = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, markdown)

        assertTrue(migrated.contains("- Discover and read any installed skill on demand, but first decide whether the task genuinely requires a tool."))
        assertTrue(migrated.contains("Custom user-authored instruction."))
        assertFalse(migrated.contains(stockSkillLine))
        assertEquals(migrated, RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, migrated))
    }

    @Test
    fun `legacy empty forced skills fallback migrates without changing role data or Unicode`() {
        val legacyFallback = chars(
            0x2d, 0x20, 0x5f53, 0x524d, 0x89d2, 0x8272, 0x6ca1, 0x6709, 0x5f3a, 0x5236,
            0x6280, 0x80fd, 0xff1b, 0x6309, 0x4efb, 0x52a1, 0x9700, 0x8981, 0x9009, 0x62e9,
            0x3002,
        )
        val forcedSkills = "- web_search, generate_document"
        val unrelatedUnicode = "User note: 天気 ☀️ — لوحة المشروع"
        val markdown = "# Chat Execution Protocol\n\n## Skill Policy\n$legacyFallback\n$forcedSkills\n$unrelatedUnicode\n"

        val migrated = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, markdown)

        assertTrue(migrated.contains("- This role has no forced skills; select them according to the task."))
        assertTrue(migrated.contains(forcedSkills))
        assertTrue(migrated.contains(unrelatedUnicode))
        assertFalse(migrated.contains(legacyFallback))
    }

    @Test
    fun `migration merges duplicate canonical sections and is idempotent`() {
        val workingMethod = chars(0x5de5, 0x4f5c, 0x65b9, 0x6cd5)
        val markdown = "# Role\n\n## Working Method\nCanonical body.\n\n## $workingMethod\nLegacy body.\n"

        val once = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CORE_MD, markdown)
        val twice = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CORE_MD, once)

        assertEquals(1, Regex("^## Working Method$", RegexOption.MULTILINE).findAll(once).count())
        assertTrue(once.contains("Canonical body."))
        assertTrue(once.contains("Legacy body."))
        assertEquals(once, twice)
    }

    @Test
    fun `English workspace is unchanged and journal history is preserved`() {
        val english = "# Role\n\n## Working Method\nKeep exact formatting.\n"
        assertEquals(english, RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CORE_MD, english))

        val legacyWorkLog = chars(0x5de5, 0x4f5c, 0x65e5, 0x5fd7)
        val journal = "# Custom $legacyWorkLog\n\n## 2026-01-01\nHistorical entry.\n"
        val migrated = RoleWorkspaceMarkdownMigrator.migrateJournal(journal)
        assertTrue(migrated.startsWith("# Custom Work Log"))
        assertTrue(migrated.contains("## 2026-01-01\nHistorical entry."))
        assertEquals(migrated, RoleWorkspaceMarkdownMigrator.migrateJournal(migrated))
    }

    private fun chars(vararg codePoints: Int): String =
        codePoints.joinToString("") { String(Character.toChars(it)) }
}
