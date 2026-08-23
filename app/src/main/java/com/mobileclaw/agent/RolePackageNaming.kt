package com.mobileclaw.agent

internal object RolePackageNaming {
    fun sanitizePackageName(raw: String): String {
        val normalized = raw.trim().lowercase()
        val result = StringBuilder(normalized.length)
        var replacingUnsafeRun = false

        normalized.codePoints().forEach { codePoint ->
            val isSafe = Character.isLetter(codePoint) ||
                Character.isDigit(codePoint) ||
                codePoint == '_'.code ||
                codePoint == '-'.code
            if (isSafe) {
                result.appendCodePoint(codePoint)
                replacingUnsafeRun = false
            } else if (!replacingUnsafeRun) {
                result.append('_')
                replacingUnsafeRun = true
            }
        }

        return result.toString().trim('_').ifBlank { "role" }
    }

    fun sanitizeRoleId(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(48)
}
