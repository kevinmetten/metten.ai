package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSchedulerTest {
    @Test
    fun `built-in catalog uses stable ids and canonical English metadata`() {
        val expected = linkedMapOf(
            "general" to Pair("General Assistant", listOf(TaskType.CHAT, TaskType.GENERAL)),
            "coder" to Pair("Code Expert", listOf(TaskType.CODE_EXECUTION, TaskType.FILE_CREATE)),
            "web_agent" to Pair("Web Assistant", listOf(TaskType.WEB_RESEARCH)),
            "phone_operator" to Pair("Phone Operator", listOf(TaskType.PHONE_CONTROL)),
            "creator" to Pair("Creative Assistant", listOf(TaskType.IMAGE_GENERATION, TaskType.APP_BUILD, TaskType.FILE_CREATE)),
            "skill_admin" to Pair("Skill Manager", listOf(TaskType.SKILL_MANAGEMENT)),
            "vpn_operator" to Pair("VPN Manager", listOf(TaskType.VPN_CONTROL)),
        )

        assertEquals(expected.keys.toList(), Role.BUILTINS.map { it.id })
        Role.BUILTINS.forEach { role ->
            assertEquals(expected.getValue(role.id).first, role.name)
            assertEquals(expected.getValue(role.id).second, role.preferredTaskTypes)
            assertTrue(role.name.isNotBlank())
            assertTrue(role.description.isNotBlank())
            assertFalse((listOf(role.name, role.description) + role.keywords).any(::containsHan))
        }
    }

    @Test
    fun `task types route to their stable built-in roles`() {
        val cases = mapOf(
            TaskType.PHONE_CONTROL to "phone_operator",
            TaskType.WEB_RESEARCH to "web_agent",
            TaskType.CODE_EXECUTION to "coder",
            TaskType.VPN_CONTROL to "vpn_operator",
            TaskType.APP_BUILD to "creator",
            TaskType.SKILL_MANAGEMENT to "skill_admin",
            TaskType.CHAT to "general",
            TaskType.GENERAL to "general",
        )

        cases.forEach { (taskType, expectedId) ->
            assertEquals(expectedId, schedule(taskType, "Handle this task").role.id)
        }
    }

    @Test
    fun `explicit canonical built-in name selects that role`() {
        assertEquals("coder", schedule(TaskType.GENERAL, "Use the Code Expert for this task").role.id)
    }

    @Test
    fun `custom Unicode names and keywords remain schedulable`() {
        val custom = customRole(name = "مترجم", keywords = listOf("ترجمة"), schedulerPriority = 130)

        assertEquals(custom.id, schedule(TaskType.GENERAL, "استخدم مترجم", listOf(custom)).role.id)
        assertEquals(custom.id, schedule(TaskType.GENERAL, "أحتاج ترجمة", listOf(custom)).role.id)
    }

    @Test
    fun `stable memory key and English compatibility phrase keep current role`() {
        val current = Role.BUILTINS.first { it.id == "coder" }

        assertEquals(
            "coder",
            schedule(TaskType.WEB_RESEARCH, "Research this", currentRole = current, memory = "agent.behavior.keep_current_role=true").role.id,
        )
        assertEquals(
            "coder",
            schedule(TaskType.WEB_RESEARCH, "Research this", currentRole = current, memory = "Keep current role").role.id,
        )
    }

    @Test
    fun `common two-letter fragments do not create inferred role boosts`() {
        val unrelated = customRole(
            name = "Indoor Helper",
            description = "Routine work.",
            schedulerPriority = 118,
        )
        val intended = Role(
            id = "creator",
            name = "Artifact Z",
            description = ".",
            avatar = RoleAvatarDefaults.CREATOR,
            preferredTaskTypes = listOf(TaskType.FILE_CREATE),
        )

        assertEquals(
            "creator",
            schedule(TaskType.FILE_CREATE, "Create a document", listOf(unrelated, intended)).role.id,
        )
    }

    @Test
    fun `localized name returns canonical built-in and exact custom names`() {
        val builtin = Role.BUILTINS.first { it.id == "coder" }
        val custom = customRole(name = "مترجم")

        assertEquals("Code Expert", builtin.localizedName("en"))
        assertEquals("Code Expert", builtin.localizedName("ar"))
        assertEquals("مترجم", custom.localizedName("en"))
        assertEquals("مترجم", custom.localizedName("ar"))
    }

    private fun schedule(
        taskType: TaskType,
        goal: String,
        availableRoles: List<Role> = emptyList(),
        currentRole: Role = Role.DEFAULT,
        memory: String = "",
    ): RoleScheduleDecision = RoleScheduler.schedule(taskType, goal, availableRoles, currentRole, memory)

    private fun customRole(
        name: String,
        description: String = "Handles translation tasks.",
        keywords: List<String> = emptyList(),
        schedulerPriority: Int = 0,
    ) = Role(
        id = "custom_translator",
        name = name,
        description = description,
        avatar = RoleAvatarDefaults.CUSTOM,
        keywords = keywords,
        schedulerPriority = schedulerPriority,
    )

    private fun containsHan(value: String): Boolean = value.any { character ->
        character.code in 0x3400..0x4DBF || character.code in 0x4E00..0x9FFF
    }
}
