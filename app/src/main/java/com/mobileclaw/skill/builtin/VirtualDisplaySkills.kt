package com.mobileclaw.skill.builtin

import android.os.Build
import com.mobileclaw.perception.VirtualDisplayManager
import com.mobileclaw.perception.AppResolution
import com.mobileclaw.perception.InstalledAppResolver
import com.mobileclaw.skill.Skill
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillParam
import com.mobileclaw.skill.SkillResult
import com.mobileclaw.skill.SkillType
import com.mobileclaw.skill.SkillToolCategory

class BgLaunchSkill(
    private val manager: VirtualDisplayManager,
    private val resolver: InstalledAppResolver,
) : Skill {
    override val meta = SkillMeta(
        id = "bg_launch",
        name = "Launch App on Virtual Display",
        description = "Creates a hidden virtual display and launches an app on it. The app runs completely invisible to the user. " +
            "After this, use bg_read_screen (XML tree) or bg_screenshot (visual) to observe the app, " +
            "and tap/scroll/input_text with node_id to interact — node-based actions work cross-display.",
        parameters = listOf(
            SkillParam("package_name", "string", "Exact package name; takes precedence over app_name", required = false),
            SkillParam("app_name", "string", "Human-facing installed app name; resolved locally", required = false),
            SkillParam("width", "number", "Virtual display width in pixels (default 1080)", required = false),
            SkillParam("height", "number", "Virtual display height in pixels (default 1920)", required = false),
        ),
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.PHONE, SkillToolCategory.SYSTEM),
        tags = listOf("Background"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val packageName = (params["package_name"] as? String)?.trim().orEmpty()
        val appName = (params["app_name"] as? String)?.trim().orEmpty()
        if (packageName.isEmpty() && appName.isEmpty()) return SkillResult(false, "package_name or app_name is required")
        val resolution = if (packageName.isNotEmpty()) resolver.resolvePackage(packageName) else resolver.resolveName(appName)
        val pkg = when (resolution) {
            is AppResolution.Resolved -> resolution.app.packageName
            is AppResolution.Ambiguous -> return SkillResult(
                false,
                "Multiple installed apps match \"${resolution.query}\": " +
                    resolution.candidates.take(5).joinToString { "${it.displayName} (${it.packageName})" } + ". Choose one.",
            )
            is AppResolution.NotInstalled -> return SkillResult(false, "No launchable installed app matched \"${resolution.query}\".")
            is AppResolution.InvalidPackage -> return SkillResult(false, "Package is not installed or is not launchable: ${resolution.packageName}")
        }
        val width = (params["width"] as? Number)?.toInt() ?: 1080
        val height = (params["height"] as? Number)?.toInt() ?: 1920
        return runCatching {
            val id = manager.start(width, height)
            manager.launchApp(pkg)
            // Wait for app to render, then verify it appeared on the virtual display (not redirected to main screen).
            kotlinx.coroutines.delay(2000)
            val xml = manager.readScreenXml()
            val appearedOnVd = xml.length > 400 && !xml.startsWith("Virtual display")
            val warning = if (!appearedOnVd) {
                "\n\n⚠️ App may have launched on the main screen instead of the virtual display. " +
                "This is a common issue on Chinese ROMs. Call vd_setup for device-specific fix instructions."
            } else ""
            SkillResult(
                success = true,
                output = "Launched $pkg on virtual display (displayId=$id, ${width}x${height}).$warning\n" +
                    "Use bg_read_screen or bg_screenshot to observe.",
            )
        }.getOrElse { e ->
            val isPermission = e is SecurityException || e.message?.contains("permission", ignoreCase = true) == true
            if (isPermission) {
                SkillResult(
                    false,
                    "Virtual display launch blocked by ROM security policy.\n" +
                    "${e.message}\n\n" +
                    "Call vd_setup to get device-specific instructions for enabling this feature.",
                )
            } else {
                SkillResult(false, "Launch failed: ${e.message}")
            }
        }
    }
}

class BgReadScreenSkill(private val manager: VirtualDisplayManager) : Skill {
    override val meta = SkillMeta(
        id = "bg_read_screen",
        name = "Read Virtual Display Screen (XML)",
        description = "Returns the accessibility UI tree of the virtual background display as XML with node IDs. " +
            "Use after bg_launch. Node IDs from this output can be used directly with tap, scroll, input_text. " +
            "If the tree is empty, use bg_screenshot for visual analysis instead.",
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.PHONE, SkillToolCategory.SYSTEM),
        tags = listOf("Background"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val xml = manager.readScreenXml()
        val hasText = xml.contains(Regex("""text="[^"]+""""))
        val nodeCount = xml.count { it == '<' }
        return if (hasText || nodeCount >= 5) {
            SkillResult(true, xml.take(8000))
        } else {
            // XML unavailable (Flutter/React Native/WebView/game) — auto-capture screenshot
            // so the agent can use tap(x=..., y=...) coordinates from the visual.
            val frame = runCatching { manager.captureFrame() }.getOrNull()
            SkillResult(
                success = false,
                output = "XML tree empty/unreadable (likely Flutter/React Native/WebView/game). " +
                    "Use tap(x=..., y=...) with coordinates estimated from the screenshot below.",
                imageBase64 = frame,
            )
        }
    }
}

class BgScreenshotSkill(private val manager: VirtualDisplayManager) : Skill {
    override val meta = SkillMeta(
        id = "bg_screenshot",
        name = "Screenshot Virtual Display",
        description = "Captures a screenshot from the hidden virtual display and returns it for visual analysis. " +
            "Use after bg_launch when bg_read_screen returns no useful content (Flutter/game/WebView apps).",
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.PHONE, SkillToolCategory.SYSTEM),
        tags = listOf("Background"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val frame = manager.captureFrame()
            ?: return SkillResult(
                success = false,
                output = "No frame available. Ensure bg_launch was called and the app has had time to render (~2s).",
            )
        return SkillResult(
            success = true,
            output = "Virtual display screenshot captured.",
            imageBase64 = frame,
        )
    }
}

class BgStopSkill(private val manager: VirtualDisplayManager) : Skill {
    override val meta = SkillMeta(
        id = "bg_stop",
        name = "Stop Virtual Display",
        description = "Releases the virtual background display and stops all apps running on it. " +
            "Call this when the background task is complete to free resources.",
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.PHONE, SkillToolCategory.SYSTEM),
        tags = listOf("Background"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        manager.stop()
        return SkillResult(true, "Virtual display stopped and resources released.")
    }
}

class VirtualDisplaySetupSkill(private val manager: VirtualDisplayManager) : Skill {
    override val meta = SkillMeta(
        id = "vd_setup",
        name = "Virtual Display Setup Guide",
        description = "Tests whether the virtual display is available and returns ROM-specific setup instructions. " +
            "Call this when bg_launch fails or the user reports the virtual display is unavailable.",
        type = SkillType.NATIVE,
        injectionLevel = 2,
        categories = listOf(SkillToolCategory.PHONE, SkillToolCategory.SYSTEM),
        tags = listOf("Background"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val testResult = manager.testSupport()
        val romName = detectRom()
        val guide = romGuide(romName)

        return if (testResult.startsWith("ok:")) {
            val id = testResult.substringAfter(":")
            SkillResult(
                success = true,
                output = "✓ Virtual display available (display #$id, device: $romName)\n\n$guide",
            )
        } else {
            val error = testResult.substringAfter(":")
            SkillResult(
                success = false,
                output = buildString {
                    appendLine("✗ Virtual display unavailable: $error")
                    appendLine()
                    appendLine("Device: $romName")
                    appendLine()
                    appendLine(guide)
                    appendLine()
                    appendLine("─────────────────────────")
                    appendLine("📲 You can also open Settings → Virtual Display → Setup guide for illustrated steps and copyable commands.")
                },
            )
        }
    }

    private fun detectRom(): String {
        val brand = Build.BRAND.lowercase()
        val mfr = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
                mfr.contains("xiaomi") -> "MIUI (Xiaomi/Redmi/POCO)"
            brand.contains("huawei") || brand.contains("honor") -> "EMUI/HarmonyOS (Huawei/Honor)"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ||
                mfr.contains("oppo") -> "ColorOS (OPPO/Realme/OnePlus)"
            brand.contains("vivo") -> "OriginOS/FuntouchOS (Vivo)"
            brand.contains("samsung") || mfr.contains("samsung") -> "One UI (Samsung)"
            brand.contains("meizu") -> "Flyme (Meizu)"
            else -> "Stock Android (${Build.BRAND})"
        }
    }

    private fun romGuide(rom: String): String = when {
        rom.contains("MIUI") -> """
            📱 MIUI setup steps:
            1. Settings → My device → All specs → tap MIUI version seven times to enable developer mode
            2. Settings → Additional settings → Developer options
            3. Enable Free-form windows
            4. Restart the app, then retry bg_launch

            If it still fails, run this ADB command from a connected computer:
              adb shell settings put global enable_freeform_support 1
        """.trimIndent()

        rom.contains("EMUI") || rom.contains("HarmonyOS") -> """
            📱 EMUI/HarmonyOS setup steps:
            1. Settings → About phone → tap Build number seven times to enable developer mode
            2. Settings → System → Developer options
            3. Enable multi-window and free-form windows
            4. Restart the app, then retry bg_launch

            If it still fails, run this ADB command:
              adb shell settings put global enable_freeform_support 1
        """.trimIndent()

        rom.contains("ColorOS") -> """
            📱 ColorOS setup steps (OPPO/Realme/OnePlus):
            1. Settings → About device → tap Build number seven times to enable developer mode
            2. Settings → Additional settings → Developer options
            3. Enable free-form windows (called Force activities to be resizable on some devices)
            4. Restart the app, then retry bg_launch

            If it still fails after configuration (common on ColorOS 12+), run this ADB command from a connected computer:
              adb shell settings put global enable_freeform_support 1
              adb shell settings put global force_desktop_mode_on_external_displays 1

            No restart is needed after running it; retry bg_launch directly.
        """.trimIndent()

        rom.contains("OriginOS") || rom.contains("FuntouchOS") -> """
            📱 OriginOS/FuntouchOS setup steps:
            1. Settings → General → About phone → tap Build number seven times to enable developer mode
            2. Settings → General → Developer options
            3. Enable multi-task display
            4. Restart the app, then retry bg_launch

            If it still fails, run this ADB command:
              adb shell settings put global enable_freeform_support 1
        """.trimIndent()

        else -> """
            📱 General setup steps:
            1. Settings → About phone → tap Build number seven times to enable Developer options
            2. Developer options → enable free-form windows or multi-window
            3. If it still fails, run this ADB command from a connected computer:
               adb shell settings put global enable_freeform_support 1
            4. Restart the app and try again.
        """.trimIndent()
    }
}
