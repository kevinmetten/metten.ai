package com.mobileclaw.ui.chat

internal enum class ProgressDetailKey(val wireKey: String, val displayLabel: String) {
    PURPOSE("purpose", "Purpose"),
    RESULT("result", "Result"),
    PLAN("plan", "Plan"),
    NOTE("note", "Note"),
    NEXT("next", "Next"),
    NEXT_PLAN("next_plan", "Next plan"),
    FULL_RESULT("full_result", "Full result"),
    DEBUG("debug", "Debug"),
    ROLE("role", "Role"),
    EXECUTION_MODE("execution_mode", "Execution mode"),
    INTENT("intent", "Intent"),
    RESPONSE("response", "Response"),
    CONTEXT("context", "Context"),
    TOOLS("tools", "Tools"),
    MEMORY("memory", "Memory"),
}

internal data class ProgressDetail(
    val key: ProgressDetailKey,
    val value: String,
)

internal object ProgressDetailProtocol {
    fun encode(key: ProgressDetailKey, value: String): String = "${key.wireKey}:$value"

    fun parse(detail: String): ProgressDetail? {
        val separator = detail.indexOf(':')
        if (separator <= 0) return null
        val key = ProgressDetailKey.entries.firstOrNull { it.wireKey == detail.substring(0, separator) }
            ?: return null
        return ProgressDetail(key, detail.substring(separator + 1))
    }

    fun value(details: Iterable<String>, key: ProgressDetailKey): String =
        details.firstNotNullOfOrNull { detail ->
            parse(detail)?.takeIf { it.key == key }?.value?.trim()
        }.orEmpty()

    fun values(details: Iterable<String>, key: ProgressDetailKey): List<String> =
        details.mapNotNull { detail ->
            parse(detail)?.takeIf { it.key == key }?.value?.trim()
        }
}
