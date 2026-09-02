package com.mobileclaw.skill.builtin

import com.mobileclaw.perception.AppResolution
import com.mobileclaw.perception.InstalledAppResolver
import com.mobileclaw.skill.SkillResult

internal object AppLaunchRouting {
    suspend fun execute(
        params: Map<String, Any>,
        resolver: InstalledAppResolver,
        launch: suspend (packageName: String, foreground: Boolean) -> SkillResult,
    ): SkillResult? {
        if (params["action"] != "launch") return null
        val packageName = (params["package_name"] as? String)?.trim().orEmpty()
        val appName = (params["app_name"] as? String)?.trim().orEmpty()
        if (packageName.isEmpty() && appName.isEmpty()) {
            return SkillResult(false, "package_name or app_name required for launch")
        }

        val resolution = if (packageName.isNotEmpty()) {
            resolver.resolvePackage(packageName)
        } else {
            resolver.resolveName(appName)
        }
        val app = when (resolution) {
            is AppResolution.Resolved -> resolution.app
            is AppResolution.Ambiguous -> return SkillResult(
                false,
                buildString {
                    appendLine("Multiple installed apps match \"${resolution.query}\":")
                    resolution.candidates.take(MAX_VISIBLE_CANDIDATES).forEach {
                        appendLine("- ${it.displayName} (${it.packageName})")
                    }
                    append("Choose one.")
                },
            )
            is AppResolution.NotInstalled -> return SkillResult(
                false,
                "No launchable installed app matched \"${resolution.query}\".",
            )
            is AppResolution.InvalidPackage -> return SkillResult(
                false,
                "Package is not installed or is not launchable: ${resolution.packageName}",
            )
        }
        return launch(app.packageName, params["foreground"] as? Boolean ?: false)
    }

    private const val MAX_VISIBLE_CANDIDATES = 5
}
