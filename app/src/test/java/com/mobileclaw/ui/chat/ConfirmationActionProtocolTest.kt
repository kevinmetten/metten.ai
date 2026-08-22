package com.mobileclaw.ui.chat

import com.mobileclaw.agent.Role
import com.mobileclaw.agent.TaskType
import com.mobileclaw.skill.SkillAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationActionProtocolTest {
    @Test
    fun `task confirmation producer and consumer round trip`() {
        val goal = "Delete report / final?.txt"
        val card = ConfirmationFlow.taskConfirmationCard(goal, TaskType.FILE_CREATE)

        assertEquals("Confirm", card.actions[0].label)
        assertCommand(card.actions[0].message, ConfirmationActionId.CONFIRM_TASK, goal)
        assertCommand(card.actions[1].message, ConfirmationActionId.CANCEL)
    }

    @Test
    fun `accessibility actions use distinct stable IDs`() {
        val goal = "Open Maps and find cafés"
        val card = ConfirmationFlow.accessibilityActionCard(goal)

        assertCommand(card.actions[0].message, ConfirmationActionId.OPEN_ACCESSIBILITY_SETTINGS, goal)
        assertCommand(card.actions[1].message, ConfirmationActionId.CONFIRM_ACCESSIBILITY_TASK, goal)
        assertCommand(card.actions[2].message, ConfirmationActionId.CANCEL)
    }

    @Test
    fun `role switch keeps role ID separate from the goal`() {
        val role = Role(id = "coder", name = "Coder", description = "", avatar = "role:coder")
        val card = ConfirmationFlow.roleSwitchConfirmationCard("Fix the build", role)

        assertCommand(
            card.actions[0].message,
            ConfirmationActionId.CONFIRM_ROLE_SWITCH,
            "coder",
            "Fix the build",
        )
        assertCommand(card.actions[1].message, ConfirmationActionId.CANCEL)
    }

    @Test
    fun `unknown and malformed actions are rejected but remain identifiable as protocol values`() {
        val unknown = "mobileclaw://action/v1/not_registered"
        val missingField = "mobileclaw://action/v1/confirm_task"

        assertNull(ConfirmationActionProtocol.parse(unknown))
        assertNull(ConfirmationActionProtocol.parse(missingField))
        assertTrue(ConfirmationActionProtocol.isProtocolValue(unknown))
        assertFalse(ConfirmationActionProtocol.isProtocolValue("Confirm this task"))
    }

    @Test
    fun `legacy persisted cards migrate structurally without localized command matching`() {
        val legacy = SkillAttachment.ActionCard(
            title = "legacy title",
            body = "legacy body",
            tone = "warning",
            instanceId = "saved-card",
            actions = listOf(
                SkillAttachment.ActionCard.Action("legacy confirm", "legacy-prefix:original goal", "primary"),
                SkillAttachment.ActionCard.Action("legacy cancel", "legacy-cancel"),
            ),
        )

        val migrated = ConfirmationActionProtocol.migrateLegacyCard(
            legacy.title,
            legacy.body,
            legacy.tone,
            legacy.actions,
            legacy.instanceId,
        )

        assertEquals("saved-card", migrated.instanceId)
        assertEquals("Confirmation required", migrated.title)
        assertCommand(migrated.actions[0].message, ConfirmationActionId.CONFIRM_TASK, "original goal")
        assertCommand(migrated.actions[1].message, ConfirmationActionId.CANCEL)
    }

    private fun assertCommand(value: String, id: ConfirmationActionId, vararg fields: String) {
        val parsed = ConfirmationActionProtocol.parse(value)
        assertEquals(id, parsed?.id)
        assertEquals(fields.toList(), parsed?.fields)
    }
}
