package com.mobileclaw.ui.chat

import com.mobileclaw.agent.Role
import com.mobileclaw.agent.TaskType
import com.mobileclaw.skill.SkillAttachment

internal data class ExplicitRoleSwitch(
    val role: Role,
    val remainingGoal: String,
)

internal object ConfirmationFlow {
    fun accessibilityActionCard(
        goal: String,
        skillName: String = "",
    ): SkillAttachment.ActionCard {
        val title = if (skillName.isNotBlank()) "$skillName requires Accessibility" else "Accessibility is required"
        return SkillAttachment.ActionCard(
            title = title,
            body = "This task will interact with your phone. Enable the MobileClaw Accessibility service, then return here and continue the same task.\n\n$goal",
            tone = "phone",
            actions = listOf(
                SkillAttachment.ActionCard.Action("Open Accessibility Settings", ConfirmationActionProtocol.encode(ConfirmationActionId.OPEN_ACCESSIBILITY_SETTINGS, goal), "primary"),
                SkillAttachment.ActionCard.Action("Enabled — Continue", ConfirmationActionProtocol.encode(ConfirmationActionId.CONFIRM_ACCESSIBILITY_TASK, goal), "secondary"),
                SkillAttachment.ActionCard.Action("Cancel", ConfirmationActionProtocol.encode(ConfirmationActionId.CANCEL), "secondary"),
            ),
        )
    }

    fun taskConfirmationCard(
        goal: String,
        taskType: TaskType,
    ): SkillAttachment.ActionCard {
        val title = when (taskType) {
            TaskType.PHONE_CONTROL -> "This will interact with your phone."
            TaskType.VPN_CONTROL -> "This will change your VPN or proxy state."
            else -> "This action requires confirmation."
        }
        return SkillAttachment.ActionCard(
            title = title,
            body = "Confirm that you want to continue. The AI will select the appropriate role and tools for this task without asking again for the same workflow.\n\n$goal",
            tone = if (taskType == TaskType.PHONE_CONTROL) "phone" else "warning",
            actions = listOf(
                SkillAttachment.ActionCard.Action("Confirm", ConfirmationActionProtocol.encode(ConfirmationActionId.CONFIRM_TASK, goal), "primary"),
                SkillAttachment.ActionCard.Action("Cancel", ConfirmationActionProtocol.encode(ConfirmationActionId.CANCEL), "secondary"),
            ),
        )
    }

    fun roleSwitchConfirmationCard(
        goal: String,
        role: Role,
    ): SkillAttachment.ActionCard =
        SkillAttachment.ActionCard(
            title = "Switch to ${role.name}",
            body = "Switching may change the active AI persona, model, or available capabilities. Confirm that you want to switch.",
            tone = "role",
            actions = listOf(
                SkillAttachment.ActionCard.Action("Switch Role", ConfirmationActionProtocol.encode(ConfirmationActionId.CONFIRM_ROLE_SWITCH, role.id, goal), "primary"),
                SkillAttachment.ActionCard.Action("Cancel", ConfirmationActionProtocol.encode(ConfirmationActionId.CANCEL), "secondary"),
            ),
        )

    fun isAccessibilityResumeText(text: String): Boolean =
        text.trim().equals("enabled", ignoreCase = true)

    fun inferExplicitRoleSwitch(goal: String, roles: List<Role>): ExplicitRoleSwitch? {
        val text = goal.lowercase()
        if (!text.anyContainsLocal("switch role", "switch to")) return null
        val role = roles.distinctBy { it.id }.firstOrNull { candidate ->
            text.contains(candidate.id.lowercase()) ||
                (candidate.name.isNotBlank() && text.contains(candidate.name.lowercase()))
        } ?: return null
        return ExplicitRoleSwitch(role, extractRoleSwitchRemainingGoal(goal, role))
    }

    private fun extractRoleSwitchRemainingGoal(goal: String, role: Role): String {
        var rest = goal
        listOf(role.name, role.id).filter { it.isNotBlank() }.forEach { token ->
            rest = rest.replace(token, "", ignoreCase = true)
        }
        return rest
            .replace(Regex("""(?i)switch\s+role\s+to|switch\s+to"""), " ")
            .trim()
    }

    private fun String.anyContainsLocal(vararg needles: String): Boolean = needles.any { contains(it) }
}
