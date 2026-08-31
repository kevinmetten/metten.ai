package com.mobileclaw.ui.update

internal object AppUpdatePresentation {
    const val NOT_CONFIGURED =
        "Update channel is not configured. Save the required App Key and API Key in User Configuration."

    fun formatInfo(
        hasNewVersion: Boolean,
        currentVersion: String,
        currentVersionCode: Int,
        remoteVersion: String,
        releaseNotes: String,
    ): String = buildString {
        appendLine(if (hasNewVersion) "New version available." else "You're already on the latest version.")
        appendLine("Current version: $currentVersion ($currentVersionCode)")
        remoteVersion.takeIf { it.isNotBlank() }?.let { appendLine("Latest version: $it") }
        releaseNotes.takeIf { it.isNotBlank() }?.let { appendLine("What's new: $it") }
    }.trim()

    fun formatRawResult(success: Boolean, rawOutput: String): String {
        if (
            rawOutput.contains("Configure pgyer_api_key", ignoreCase = true) ||
            rawOutput.contains("Configure release channel", ignoreCase = true)
        ) return NOT_CONFIGURED

        if (!success) {
            val cleaned = rawOutput
                .lineSequence()
                .filterNot { it.trimStart().startsWith("Download:", ignoreCase = true) }
                .joinToString("\n")
                .releaseMessageForUser()
                .ifBlank { "Network or configuration error" }
            return "Update check failed: $cleaned"
        }

        val lines = rawOutput.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        fun value(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }
        val hasNew = rawOutput.contains("newer MobileClaw build", ignoreCase = true)
        return buildString {
            appendLine(if (hasNew) "New version available." else "You're already on the latest version.")
            value("Current:")?.let { appendLine("Current version: $it") }
            value("Remote:")?.let { appendLine("Latest version: $it") }
            value("Notes:")?.let { appendLine("What's new: $it") }
        }.trim()
    }

    fun String.releaseMessageForUser(): String =
        replace(Regex("(?i)pgyer"), "update service")
            .replace("Configure release channel api_key and app_key first.", "Update channel is not configured.")
            .replace("Configure release channel", "Update channel is not configured.")
}
