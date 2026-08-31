package com.mobileclaw.skill.builtin

import com.mobileclaw.server.ConsoleServer
import com.mobileclaw.skill.Skill
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillParam
import com.mobileclaw.skill.SkillResult
import com.mobileclaw.skill.SkillType
import com.mobileclaw.skill.SkillToolCategory

/**
 * Lets the agent fully customise the LAN console web page — the "customizable console" feature.
 *
 * The console lives at filesDir/console_web/index.html and is served by ConsoleServer on
 * the local network. The agent can read the current page, replace it entirely with a
 * personalised dashboard, inject incremental CSS/JS patches, or reset to the default.
 *
 * Personalisation ideas:
 *   - Match the user's favourite colour scheme
 *   - Add shortcut buttons for their most-used commands
 *   - Embed live data widgets (weather, tasks, stocks) fetched by JS
 *   - Show user's name and avatar in the header
 *   - Switch between chat/command/dashboard layouts
 */
class ConsoleEditorSkill(private val server: ConsoleServer) : Skill {

    override val meta = SkillMeta(
        id = "console_editor",
        name = "Console Editor",
        description = "Read or rewrite the customizable LAN web console page. " +
            "Actions: " +
            "read — get the current console HTML; " +
            "write — replace the entire page with new HTML (use for full redesigns); " +
            "patch_css — inject a <style> block into the page head (for incremental theme tweaks); " +
            "patch_js — inject a <script> block into the page body (for adding widgets/features); " +
            "reset — restore the factory-default console; " +
            "get_url — return the LAN URL users should visit. " +
            "Always keep the /api/events SSE connection, /api/send POST, /api/sessions, and /api/messages " +
            "endpoints wired correctly so the console stays functional after edits.",
        parameters = listOf(
            SkillParam(
                "action", "string",
                "One of: read | write | patch_css | patch_js | reset | get_url",
            ),
            SkillParam(
                "html", "string",
                "Complete HTML document for action=write. Must be a full <!DOCTYPE html>…</html> document.",
                required = false,
            ),
            SkillParam(
                "css", "string",
                "Raw CSS rules (no <style> tags) to inject for action=patch_css.",
                required = false,
            ),
            SkillParam(
                "js", "string",
                "Raw JavaScript (no <script> tags) to inject for action=patch_js.",
                required = false,
            ),
        ),
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.SYSTEM, SkillToolCategory.ARTIFACT),
        tags = listOf("System"),
    )

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        return when (val action = params["action"] as? String) {
            "read" -> {
                val html = server.readHtml()
                SkillResult(true, "Current console HTML(${html.length} characters):\n\n$html")
            }

            "write" -> {
                val html = params["html"] as? String
                    ?: return SkillResult(false, "html is required")
                if (!html.contains("<html", ignoreCase = true)) {
                    return SkillResult(false, "html must be a complete HTML document containing an <html> tag")
                }
                server.writeHtml(html)
                SkillResult(true, "✅ Console page updated(${html.length} characters).Refresh the browser to see the new page.")
            }

            "patch_css" -> {
                val css = params["css"] as? String
                    ?: return SkillResult(false, "css is required")
                val current = server.readHtml()
                val styleBlock = "\n<style>\n/* console_patch */\n$css\n</style>\n"
                val patched = injectBeforeClosingHead(current, styleBlock)
                server.writeHtml(patched)
                SkillResult(true, "✅ CSS injected into the console. Refresh the browser to apply it.")
            }

            "patch_js" -> {
                val js = params["js"] as? String
                    ?: return SkillResult(false, "js is required")
                val current = server.readHtml()
                val scriptBlock = "\n<script>\n/* console_patch */\n$js\n</script>\n"
                val patched = injectBeforeClosingBody(current, scriptBlock)
                server.writeHtml(patched)
                SkillResult(true, "✅ JavaScript injected into the console. Refresh the browser to apply it.")
            }

            "reset" -> {
                server.resetHtml()
                SkillResult(true, "✅ The console was reset to its default style. Refresh the browser to apply it.")
            }

            "get_url" -> {
                val url = server.getLanUrl()
                SkillResult(true, "Console LAN URL: $url\n\nOpen it in a browser on the same LAN.")
            }

            null, "" -> SkillResult(false, "action is required")

            else -> SkillResult(
                false,
                "Unknown action: $action.Allowed values: read | write | patch_css | patch_js | reset | get_url",
            )
        }
    }

    private fun injectBeforeClosingHead(html: String, block: String): String {
        val idx = html.indexOf("</head>", ignoreCase = true)
        return if (idx >= 0) html.substring(0, idx) + block + html.substring(idx)
        else block + html
    }

    private fun injectBeforeClosingBody(html: String, block: String): String {
        val idx = html.indexOf("</body>", ignoreCase = true)
        return if (idx >= 0) html.substring(0, idx) + block + html.substring(idx)
        else html + block
    }
}
