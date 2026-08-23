package com.mobileclaw.ui.chat

import com.mobileclaw.skill.SkillAttachment
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class ConfirmationActionId(val wireId: String, val fieldCount: Int) {
    CONFIRM_TASK("confirm_task", 1),
    CANCEL("cancel", 0),
    OPEN_ACCESSIBILITY_SETTINGS("open_accessibility_settings", 1),
    CONFIRM_ACCESSIBILITY_TASK("confirm_accessibility_task", 1),
    CONFIRM_ROLE_SWITCH("confirm_role_switch", 2),
}

internal data class ConfirmationActionCommand(
    val id: ConfirmationActionId,
    val fields: List<String>,
)

internal object ConfirmationActionProtocol {
    private const val PREFIX = "mobileclaw://action/v1/"

    fun encode(id: ConfirmationActionId, vararg fields: String): String {
        require(fields.size == id.fieldCount) { "${id.wireId} requires ${id.fieldCount} fields" }
        return buildString {
            append(PREFIX)
            append(id.wireId)
            fields.forEach { field ->
                append('/')
                append(URLEncoder.encode(field, StandardCharsets.UTF_8.name()).replace("+", "%20"))
            }
        }
    }

    fun parse(value: String): ConfirmationActionCommand? {
        if (!value.startsWith(PREFIX)) return null
        val segments = value.removePrefix(PREFIX).split('/')
        val id = ConfirmationActionId.entries.firstOrNull { it.wireId == segments.firstOrNull() } ?: return null
        if (segments.size != id.fieldCount + 1) return null
        val fields = runCatching {
            segments.drop(1).map { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
        }.getOrNull() ?: return null
        return ConfirmationActionCommand(id, fields)
    }

    fun isProtocolValue(value: String): Boolean = value.startsWith(PREFIX)

    fun migrateLegacyActions(
        tone: String,
        actions: List<SkillAttachment.ActionCard.Action>,
    ): List<SkillAttachment.ActionCard.Action> {
        if (actions.isEmpty() || actions.all { isProtocolValue(it.message) }) return actions
        fun payload(action: SkillAttachment.ActionCard.Action): String =
            action.message.substringAfter(':', "").trim()
        fun action(index: Int, label: String, id: ConfirmationActionId, vararg fields: String) =
            actions[index].copy(label = label, message = encode(id, *fields))

        return when {
            tone == "phone" && actions.size == 3 -> listOf(
                action(0, "Open Accessibility Settings", ConfirmationActionId.OPEN_ACCESSIBILITY_SETTINGS, payload(actions[0])),
                action(1, "Enabled — Continue", ConfirmationActionId.CONFIRM_ACCESSIBILITY_TASK, payload(actions[1])),
                action(2, "Cancel", ConfirmationActionId.CANCEL),
            )
            tone == "role" && actions.size == 2 -> {
                val rolePayload = payload(actions[0])
                listOf(
                    action(
                        0,
                        "Switch Role",
                        ConfirmationActionId.CONFIRM_ROLE_SWITCH,
                        rolePayload.substringBefore("::"),
                        rolePayload.substringAfter("::", ""),
                    ),
                    action(1, "Cancel", ConfirmationActionId.CANCEL),
                )
            }
            tone in setOf("phone", "warning") && actions.size == 2 -> listOf(
                action(0, "Confirm", ConfirmationActionId.CONFIRM_TASK, payload(actions[0])),
                action(1, "Cancel", ConfirmationActionId.CANCEL),
            )
            else -> actions
        }
    }

    fun migrateLegacyCard(
        title: String,
        body: String,
        tone: String,
        actions: List<SkillAttachment.ActionCard.Action>,
        instanceId: String,
    ): SkillAttachment.ActionCard {
        val isLegacyConfirmation = actions.isNotEmpty() && actions.none { isProtocolValue(it.message) } &&
            ((tone == "phone" && actions.size in 2..3) || (tone == "role" && actions.size == 2) ||
                (tone == "warning" && actions.size == 2))
        if (!isLegacyConfirmation) {
            return SkillAttachment.ActionCard(title, body, actions, tone, instanceId)
        }
        val migratedActions = migrateLegacyActions(tone, actions)
        val goal = migratedActions.asSequence()
            .mapNotNull { parse(it.message) }
            .flatMap { it.fields.asSequence() }
            .lastOrNull()
            .orEmpty()
        val (migratedTitle, migratedBody) = when {
            tone == "phone" && actions.size == 3 -> "Accessibility is required" to
                "This task will interact with your phone. Enable the MobileClaw Accessibility service, then return here and continue the same task.\n\n$goal"
            tone == "role" -> "Switch Role" to
                "Switching may change the active AI persona, model, or available capabilities. Confirm that you want to switch."
            tone == "phone" -> "This will interact with your phone." to
                "Confirm that you want to continue.\n\n$goal"
            else -> "Confirmation required" to "Confirm that you want to continue.\n\n$goal"
        }
        return SkillAttachment.ActionCard(
            migratedTitle,
            migratedBody,
            migratedActions,
            tone,
            instanceId,
        )
    }
}
