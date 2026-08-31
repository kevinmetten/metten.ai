package com.mobileclaw.ui.workspace

internal object WorkspacePresentationSemantics {
    fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

    fun eventCategory(category: String): String = when (category) {
        "progress",
        "task_plan",
        "task_started",
        "direct_chat_started",
        "plan_created",
        "tool_call",
        "skill_observation",
        "reflection",
        "review_completed",
        "continuation_checkpoint",
        "deterministic_phone_launch",
        "deterministic_artifact_patch",
        "artifact_observation",
        "file_observation",
        "code_observation" -> "Progress"
        "reminder",
        "phone_control_guard",
        "repeated_perception_guard" -> "Reminder"
        "repair",
        "draft_repair",
        "validation_repair",
        "runtime_log_repair" -> "Repair"
        "completed",
        "task_complete",
        "task_completed",
        "direct_chat_completed" -> "Completed"
        "blocked",
        "task_error" -> "Blocked"
        else -> category
    }
}
