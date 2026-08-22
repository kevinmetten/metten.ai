package com.mobileclaw.agent

import android.util.Log
import com.mobileclaw.config.responseLanguageShortInstruction
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.Message
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillRegistry
import com.mobileclaw.skill.SkillToolCategory
import com.mobileclaw.skill.SkillToolTaxonomy
import java.util.UUID

enum class TaskType {
    CHAT,
    GENERAL,
    PHONE_CONTROL,
    WEB_RESEARCH,
    FILE_CREATE,
    APP_BUILD,
    IMAGE_GENERATION,
    VPN_CONTROL,
    SKILL_MANAGEMENT,
    CODE_EXECUTION,
}

enum class TaskStatus {
    RUNNING,
    DONE,
    BLOCKED,
    NEED_USER,
    FAILED,
}

data class TaskSession(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val type: TaskType,
    val status: TaskStatus = TaskStatus.RUNNING,
    val context: Map<String, String> = emptyMap(),
    val startedAt: Long = System.currentTimeMillis(),
    val maxSteps: Int = 20,
)

data class TaskPlan(
    val taskType: TaskType,
    val summary: String,
    val steps: List<String>,
    val successCriteria: String,
) {
    fun toPrompt(): String = buildString {
        appendLine("## Task Plan")
        appendLine("Task type: $taskType")
        appendLine("Summary: $summary")
        appendLine("Steps:")
        steps.forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
        appendLine("Success criteria: $successCriteria")
        appendLine("Follow this plan, but update your next action based on observations. Do not restart planning unless blocked.")
    }
}

object TaskPlanner {
    private const val TAG = "TaskPlanner"

    fun fallback(goal: String, taskType: TaskType): TaskPlan = fallbackPlan(goal, taskType)

    suspend fun plan(
        llm: LlmGateway,
        goal: String,
        taskType: TaskType,
        language: String = "en",
        priorContext: String = "",
    ): TaskPlan {
        val prompt = """
You are planning one MobileClaw task before execution.
Return a concise operational plan. Do not call tools.

Task type: $taskType
User goal:
$goal

Recent chat context:
${priorContext.take(2400)}

Output format:
Summary: <one-line summary>
Steps:
1. <step>
2. <step>
3. <step>
Success criteria: <one-line criterion>

Rules:
- Use the English labels "Summary:", "Steps:", and "Success criteria:" exactly as shown.
- Do not add text before or after the structured plan.
- Keep 3-6 steps.
- Generate the todo steps from BOTH the current user goal and the recent chat context, especially the last 10 chat records if provided.
- Treat short follow-ups like "continue", "change it", "optimize", "not this", or "do the previous one" as references to the recent context, not standalone tasks.
- Align success criteria with what the user has been trying to achieve across the recent context, not only the literal latest sentence.
- For PHONE_CONTROL, plan in observe -> act -> verify terms.
- For WEB_RESEARCH, plan source gathering then synthesis.
- For APP_BUILD/FILE_CREATE, plan artifact creation and verification.
- ${responseLanguageShortInstruction(language)}
""".trimIndent()

        val response = try {
            llm.chat(
                ChatRequest(
                    messages = listOf(
                        Message(role = "system", content = "Create a short task plan only. No tools."),
                        Message(role = "user", content = prompt),
                    ),
                    tools = emptyList(),
                    stream = false,
                )
            ).content.orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Planning failed for taskType=$taskType goal=${goal.take(160)}", t)
            ""
        }

        return parseOrFallback(response, goal, taskType)
    }

    internal fun parseOrFallback(raw: String, goal: String, taskType: TaskType): TaskPlan =
        parsePlan(raw, taskType) ?: fallbackPlan(goal, taskType)

    internal fun parsePlan(raw: String, taskType: TaskType): TaskPlan? {
        if (raw.isBlank()) return null
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val summaryPattern = Regex("^summary\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val stepsHeaderPattern = Regex("^steps\\s*:\\s*$", RegexOption.IGNORE_CASE)
        val criteriaPattern = Regex("^success\\s+criteria\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val summaryIndex = lines.indexOfFirst { summaryPattern.matches(it) }
        val stepsHeaderIndex = lines.indexOfFirst { stepsHeaderPattern.matches(it) }
        if (summaryIndex != 0 || stepsHeaderIndex != 1) return null

        val summary = summaryPattern.matchEntire(lines[summaryIndex])
            ?.groupValues?.get(1)?.trim()?.take(180)?.ifBlank { null }
            ?: return null
        val criteriaIndex = lines.indexOfFirst { criteriaPattern.matches(it) }
        if (criteriaIndex >= 0 && criteriaIndex != lines.lastIndex) return null
        val stepLines = lines.subList(stepsHeaderIndex + 1, if (criteriaIndex > stepsHeaderIndex) criteriaIndex else lines.size)
        val numberedStepPattern = Regex("^\\d+[.)]\\s+(.+)$")
        val bulletStepPattern = Regex("^-\\s+(.+)$")
        val parsedSteps = stepLines.map { line ->
            numberedStepPattern.matchEntire(line)?.groupValues?.get(1)
                ?: bulletStepPattern.matchEntire(line)?.groupValues?.get(1)
        }
        if (parsedSteps.any { it == null }) return null
        val steps = parsedSteps.filterNotNull().map(String::trim).filter(String::isNotBlank).take(6)
        val criteria = criteriaIndex.takeIf { it > stepsHeaderIndex }
            ?.let { criteriaPattern.matchEntire(lines[it])?.groupValues?.get(1)?.trim()?.ifBlank { null } }
            ?: "The user goal is satisfied and the result is verified."
        return TaskPlan(taskType, summary, steps.ifEmpty { fallbackPlan("", taskType).steps }, criteria)
    }

    private fun fallbackPlan(goal: String, taskType: TaskType): TaskPlan = when (taskType) {
        TaskType.PHONE_CONTROL -> TaskPlan(
            taskType,
            "Operate the phone UI to complete the requested goal.",
            listOf("Observe the current screen.", "Choose the next visible UI action.", "Execute the action.", "Verify the new screen and continue until complete."),
            "The requested phone-side state is reached, or a clear blocker is reported.",
        )
        TaskType.WEB_RESEARCH -> TaskPlan(taskType, "Research the requested information.", listOf("Search or open relevant pages.", "Extract useful facts.", "Summarize the answer with source context."), "The answer addresses the user's question.")
        TaskType.APP_BUILD -> TaskPlan(taskType, "Build the requested app/artifact.", listOf("Create or update the artifact.", "Check the result.", "Report where it is available."), "The artifact is created and usable.")
        TaskType.FILE_CREATE -> TaskPlan(taskType, "Create or update requested files.", listOf("Determine the needed file format.", "Create or update the file.", "Verify the result."), "The requested file work is complete.")
        TaskType.IMAGE_GENERATION -> TaskPlan(taskType, "Generate the requested media.", listOf("Use the generation tool with the prompt.", "Return the generated result."), "The media result is available.")
        TaskType.VPN_CONTROL -> TaskPlan(taskType, "Change or inspect VPN state.", listOf("Check the requested VPN action.", "Call VPN control.", "Report the resulting state."), "VPN state matches the request.")
        TaskType.SKILL_MANAGEMENT -> TaskPlan(taskType, "Manage skills as requested.", listOf("Inspect current skills if needed.", "Create/install/update the skill.", "Report the change."), "The skill change is complete.")
        TaskType.CODE_EXECUTION -> TaskPlan(taskType, "Run code or commands.", listOf("Choose the right execution tool.", "Run the code/command.", "Report output and errors."), "The command result is clear.")
        TaskType.CHAT, TaskType.GENERAL -> TaskPlan(taskType, "Execute the user task.", listOf("Select the right tool or answer path.", "Act or answer.", "Verify completion."), "The user goal is satisfied.")
    }
}

object TaskClassifier {
    fun classify(goal: String, hasImage: Boolean = false, hasFile: Boolean = false): TaskType {
        val text = goal.lowercase().replace(Regex("\\s+"), " ").trim()
        if (hasImage && hasPhoneControlIntent(text, hasScreenContext = true)) return TaskType.PHONE_CONTROL
        if (hasImage) return TaskType.GENERAL
        if (hasFile) return TaskType.FILE_CREATE

        if (hasVpnControlIntent(text)) return TaskType.VPN_CONTROL
        if (hasPhoneControlIntent(text)) return TaskType.PHONE_CONTROL
        if (looksLikePhoneAppOperation(text)) return TaskType.PHONE_CONTROL
        if (isFollowUpOnly(text)) return TaskType.GENERAL
        if (hasExplicitWebResearchIntent(text)) return TaskType.WEB_RESEARCH
        if (hasExplicitImageGenerationIntent(text)) return TaskType.IMAGE_GENERATION
        if (hasExplicitPageBuildIntent(text)) return TaskType.APP_BUILD
        if (hasExplicitAppBuildIntent(text)) return TaskType.APP_BUILD
        if (hasExplicitFileIntent(text)) return TaskType.FILE_CREATE
        if (hasSkillManagementIntent(text)) return TaskType.SKILL_MANAGEMENT
        if (hasCodeExecutionIntent(text)) return TaskType.CODE_EXECUTION
        return TaskType.GENERAL
    }

    private fun hasExplicitPageBuildIntent(text: String): Boolean {
        val pageNoun = text.matchesAny(
            "page", "screen", "dashboard", "form", "admin page", "management page", "settings page",
            "native page", "ai page", "aipage",
        )
        return pageNoun && text.hasCreationVerb()
    }

    private fun hasExplicitWebResearchIntent(text: String): Boolean {
        if (text.matchesAny(
                "don't search the web", "do not search the web", "don't browse", "do not browse",
                "don't go online", "do not go online", "use what i gave you", "use the information above",
                "based only on the above", "based only on the previous content",
            )) return false
        if (text.matchesAny("search box", "search field", "search button", "search screen", "search page") &&
            text.matchesAny("tap", "click", "press", "type", "enter", "open", "scroll")) return false
        return text.matchesAny(
            "search the web", "search online", "web search", "browse the web", "research online",
            "find sources", "find reliable sources", "look this up online", "look it up online",
            "official website", "official source", "latest news", "current news",
        )
    }

    private fun looksLikePhoneAppOperation(text: String): Boolean {
        if (isInformationalQuestion(text)) return false
        val appTarget = text.matchesAny(
            "gmail", "google maps", "maps", "settings", "messages", "whatsapp", "telegram",
            "chrome", "browser", "instagram", "facebook", "amazon", "youtube", "spotify",
        ) || Regex("\\b(?:in|on|using)\\s+(?:the\\s+)?[a-z0-9._-]+\\s+app\\b|\\b(?:the|an|my)\\s+app\\b").containsMatchIn(text)
        val appAction = text.matchesAny(
            "find", "search", "order", "buy", "send", "enter", "select", "choose", "navigate",
            "check", "book", "post", "play", "message", "nearby",
        )
        return appTarget && appAction
    }

    private fun hasExplicitImageGenerationIntent(text: String): Boolean {
        if (text.matchesAny(
                "describe this image", "describe the image", "what's in this picture", "what is in this picture",
                "what is in the image", "analyze this image", "analyse this image", "identify what is shown",
                "recognize this image", "read this image",
            )) return false
        val mediaNoun = text.matchesAny("image", "picture", "illustration", "poster", "icon", "logo", "cover", "video", "animation")
        val generationVerb = text.matchesAny("generate", "create", "draw", "design", "make", "produce", "render")
        return mediaNoun && generationVerb
    }

    private fun hasExplicitFileIntent(text: String): Boolean {
        if (text.matchesAny("read this file", "explain this file", "inspect this file", "what is in this file", "list files")) return true
        val officeNoun = text.matchesAny("file", "document", "report", "ppt", "pptx", "docx", "word", "xlsx", "excel", "pdf", "csv", "markdown")
        val createVerb = text.matchesAny("create", "generate", "export", "save", "write", "produce", "convert")
        if (officeNoun && createVerb) return true
        return false
    }

    private fun isFollowUpOnly(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.length > 40) return false
        return normalized in setOf(
            "continue", "keep going", "next", "try again", "retry", "change it", "update it", "fix it",
            "improve it", "optimize it", "optimise it", "not this", "that's wrong", "that is wrong",
            "try another way", "use this", "use that", "do the previous one", "based on the above",
        )
    }

    private fun hasPhoneControlIntent(text: String, hasScreenContext: Boolean = false): Boolean {
        if (isInformationalQuestion(text)) return false
        if (hasAppLaunchIntent(text)) return true

        val explicitDeviceAction = text.matchesAny(
            "go back", "go home", "send a message", "send a text", "make a call", "place a call",
            "operate the phone", "control the phone", "use my phone", "use the phone",
            "inspect the current screen", "read the current screen", "look at the current screen",
        ) || Regex("^call\\s+(?!stack\\b)[a-z0-9]").containsMatchIn(text)
        if (explicitDeviceAction) return true

        val directionalGesture = Regex("^(?:please\\s+)?(?:scroll\\s+(?:up|down)|swipe\\s+(?:left|right|up|down))(?:\\b|$)")
            .containsMatchIn(text)
        if (directionalGesture) return true

        val uiTarget = "(?:button|field|text\\s+box|message\\s+box|input|menu|tab|screen|page|settings|app|icon|item|option|control|link)"
        val targetedUiAction = Regex(
            "^(?:please\\s+)?(?:tap|click|press|long\\s+press|select|choose)\\s+(?:the\\s+)?(?:[a-z0-9_-]+\\s+){0,4}$uiTarget\\b",
        ).containsMatchIn(text)
        if (targetedUiAction) return true

        val targetedTextEntry = Regex(
            "^(?:please\\s+)?(?:type|enter)\\s+.+?\\s+(?:into|in)\\s+(?:the\\s+)?(?:[a-z0-9_-]+\\s+){0,3}$uiTarget\\b",
        ).containsMatchIn(text)
        if (targetedTextEntry) return true

        val contextualAction = Regex("^(?:please\\s+)?(?:tap|click|press|swipe|scroll|select|choose)\\s+(?:this|that)(?:\\b|$)")
            .containsMatchIn(text)
        if (contextualAction) return true

        return hasScreenContext && Regex("^(?:please\\s+)?(?:tap|click|press|swipe|scroll|type|select|choose|enter)\\b")
            .containsMatchIn(text)
    }

    private fun hasAppLaunchIntent(text: String): Boolean {
        val request = Regex("^(?:please\\s+)?(open|launch|start)\\s+(.+?)[.!?]?$")
            .matchEntire(text) ?: return false
        val verb = request.groupValues[1]
        if (verb.isBlank()) return false
        val rawTarget = request.groupValues[2].trim().removePrefix("the ")
        val target = rawTarget
            .substringBefore(" and ")
            .substringBefore(" then ")
            .trim()
        if (target.isBlank() || target.split(Regex("\\s+")).size > 4) return false

        val explicitApp = target.endsWith(" app")
        val commonSystemTarget = target in setOf(
            "gmail", "google maps", "maps", "settings", "messages", "chrome", "browser", "calculator",
            "camera", "calendar", "clock", "spotify",
        )
        if (verb == "start") return explicitApp || commonSystemTarget
        if (explicitApp || commonSystemTarget) return true

        if (target.startsWith("a ") || target.startsWith("an ")) return false
        val rejectedTargets = setOf(
            "source", "source code", "source software", "source licensing", "bank account", "account",
            "marketing campaign", "campaign", "business", "discussion", "conversation", "report", "document",
            "attached pdf", "pdf", "file", "folder", "project", "repository", "repo", "website", "web page",
            "link", "url",
        )
        if (target in rejectedTargets) return false
        val targetWords = target.split(Regex("\\s+"))
        val rejectedWords = setOf(
            "source", "account", "campaign", "business", "discussion", "report", "document", "pdf", "file",
            "folder", "project", "repository", "repo", "website", "link", "url",
        )
        if (targetWords.any { it in rejectedWords || it.endsWith("ing") }) return false
        return Regex("^[a-z0-9][a-z0-9+._'-]*(?:\\s+[a-z0-9][a-z0-9+._'-]*){0,3}$").matches(target)
    }

    private fun hasVpnControlIntent(text: String): Boolean {
        if (isInformationalQuestion(text)) return false
        val vpnContext = text.matchesAny("vpn", "proxy", "vpn subscription", "vpn node", "vpn server")
        val controlIntent = text.matchesAny(
            "connect", "disconnect", "enable", "disable", "turn on", "turn off", "change", "switch", "select",
            "configure", "set up", "setup", "import", "subscribe", "check status", "show status",
        )
        return vpnContext && controlIntent
    }

    private fun hasExplicitAppBuildIntent(text: String): Boolean {
        val artifactNoun = text.matchesAny("app", "mini app", "miniapp", "web app", "game", "program", "calculator app")
        return artifactNoun && text.hasCreationVerb()
    }

    private fun hasSkillManagementIntent(text: String): Boolean {
        val subsystem = text.matchesAny("role", "persona", "skill", "skill marketplace", "capability", "agent behavior", "own behavior")
        val management = text.matchesAny(
            "create", "add", "edit", "update", "change", "switch", "manage", "install", "remove", "delete",
            "repair", "fix", "improve", "upgrade",
        )
        return subsystem && management
    }

    private fun hasCodeExecutionIntent(text: String): Boolean {
        if (isInformationalQuestion(text)) return false
        val execution = text.matchesAny("run", "execute", "install", "invoke")
        val codeTarget = text.matchesAny("python", "script", "shell", "command", "code", "pip", "terminal")
        return execution && codeTarget
    }

    private fun isInformationalQuestion(text: String): Boolean =
        text.startsWith("explain ") || text.startsWith("what is ") || text.startsWith("what are ") ||
            text.startsWith("why ") || text.startsWith("how does ") || text.startsWith("how do ") ||
            text.startsWith("how can ") || text.startsWith("tell me about ") ||
            text.startsWith("describe how ")

    private fun String.hasCreationVerb(): Boolean =
        matchesAny("create", "build", "make", "design", "develop", "generate", "implement")

    private fun String.matchesAny(vararg phrases: String): Boolean = phrases.any { phrase ->
        val pattern = phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        Regex("(?<![a-z0-9_])$pattern(?![a-z0-9_])").containsMatchIn(this)
    }
}

object TaskToolPolicy {
    fun select(
        registry: SkillRegistry,
        taskType: TaskType,
        goal: String = "",
        forcedSkillIds: List<String> = emptyList(),
        memoryContext: String = "",
    ): List<SkillMeta> {
        val staticAllowed = allowedSkillIds(taskType)
        val allowedCategories = allowedSkillCategories(taskType)
        val forcedSet = forcedSkillIds.toSet()
        val metaById = registry.allMetasWithTaxonomy().associateBy { it.id }
        val dynamicAllowed = metaById.values
            .filter { meta ->
                meta.id !in staticAllowed &&
                    meta.categories.any { it in allowedCategories } &&
                    shouldExposeCategorizedSkill(meta, goal, taskType, forcedSet)
            }
            .map { it.id }
        val allowed = (staticAllowed + dynamicAllowed + forcedSkillIds).distinct()
        val memoryFiltered = applyMemoryToolPolicy(allowed, taskType, memoryContext)
        val goalFiltered = narrowByGoal(memoryFiltered, taskType, goal)
        val dynamicGoalMatches = dynamicAllowed.filter { id ->
            val meta = metaById[id] ?: return@filter false
            id in memoryFiltered && skillMatchesGoal(meta, goal)
        }
        val selectedIds = (goalFiltered + dynamicGoalMatches + forcedSkillIds).distinct()
        val taskSkills = selectedIds.mapNotNull { id -> metaById[id] ?: registry.get(id)?.meta }
        val forced = forcedSkillIds.mapNotNull { id -> metaById[id] ?: registry.get(id)?.meta }
        return (taskSkills + forced).distinctBy { it.id }
    }

    fun prompt(taskType: TaskType): String = when (taskType) {
        TaskType.PHONE_CONTROL -> """
## Current Task Mode: VLM_PHONE_CONTROL
- Primary channel: phone tool channel.
- Supporting channels: memory channel for preferences, task plan channel for step-by-step control, self-evolution channel only if the user explicitly changes roles or capabilities.
- Treat the current phone screen as your operating environment.
- Work as: observe -> act -> verify -> continue/done. Do not drift into UI building, web research, or generic chat unless the user explicitly changes the task.
- First inspect with `see_screen` unless the latest step already observed the screen.
- The screenshot coordinates returned by `see_screen`/`screenshot` are image coordinates. Use them directly with `tap`, `scroll`, and `long_click`; those tools map image pixels to real device pixels.
- Use `screenshot` only as a fallback when marker detection is unusable or a raw visual check is needed.
- After an observation, take a concrete action (`tap`, `scroll`, `input_text`, `long_click`, `navigate`) or finish/block. Do not observe twice in a row.
- After launching or navigating, use the foreground package/activity returned by tool results, or call `phone_status`, to verify whether the target app is open.
""".trimIndent()
        TaskType.WEB_RESEARCH -> """
## Current Task Mode: WEB_RESEARCH
- Primary channel: web tool channel.
- Supporting channels: memory channel for prior facts and citations, chat channel for concise synthesis, and phone/tool channels when they are useful for opening sources, verifying app-side state, or continuing a user workflow.
- Prefer web/search tools for source gathering, then synthesize a concise answer. You may cross into phone, file, MCP, or skill tools when the user's workflow benefits from it.
""".trimIndent()
        TaskType.APP_BUILD -> """
## Current Task Mode: APP_BUILD
- Primary channel: artifact channel.
- Supporting channels: skill channel for builders, memory channel for existing pages/apps, self-evolution channel for updating existing roles or app behavior.
- Build or update runnable UI with tools. NEVER return raw code/HTML as chat text.
- Default route: for pages, dashboards, forms, settings panels, management screens, data viewers, and lightweight tools, use `ui_builder` to create an AI Native Page.
- Hard rule: if the user explicitly asks for a mini-app/app/program/game or for custom HTML/CSS/JavaScript/WebView/canvas/Python/SQLite runtime, do not use `ui_builder`; use `app_manager`.
- Use `app_manager` only when the user explicitly asks for an app/mini-app/program/game, or when the artifact needs custom HTML/CSS/JavaScript, canvas, complex browser rendering, Python backend, SQLite, or a WebView-style runtime.
- Use `create_html` only for one-off rich HTML reports/previews shown in chat, not persistent apps or native pages.
- For follow-up edits to an existing artifact, default to patch mode. Carry forward the artifact goal, required features, constraints, accepted corrections, known bugs, and non-goals.
- Existing AI page flow: `ui_builder(get)` -> `ui_builder(analyze_change)` -> `ui_builder(update)` -> `ui_builder(validate)` -> `ui_builder(open if needed)`.
- Existing MiniAPP flow: `app_manager(analyze_change)` -> `app_manager(update)` -> `app_manager(validate)` -> `app_manager(open if needed)`.
""".trimIndent()
        TaskType.IMAGE_GENERATION -> """
## Current Task Mode: IMAGE_GENERATION
- Primary channel: media generation channel.
- Supporting channels: memory channel for style continuity, chat channel for brief explanation.
- Use image/video/icon generation tools. Ask only if a required prompt detail is missing.
""".trimIndent()
        TaskType.FILE_CREATE -> """
## Current Task Mode: FILE_CREATE
- Primary channel: file/document channel.
- Supporting channels: memory channel for existing artifacts, skill channel for document generation helpers, self-evolution channel only when the user asks for tool upgrades.
- Use file/document tools to create, read, list, or update files.
- For office documents (PPT/PPTX, Word/DOCX, Excel/XLSX, PDF), you MUST call `generate_document`.
- Do NOT use `create_file` for PPTX/DOCX/XLSX/PDF. Do NOT use `run_python`, `pip_install`, pandas, python-pptx, openpyxl, or xlsxwriter for office generation.
- Use `create_file` only for plain text-like files such as txt, md, json, csv when no office layout is needed.
""".trimIndent()
        TaskType.VPN_CONTROL -> """
## Current Task Mode: VPN_CONTROL
- Primary channel: VPN control channel.
- Supporting channels: memory channel for preferred nodes/proxies.
- Use `vpn_control` to start, stop, or check VPN state. Do not use phone UI unless permission/setup requires the user.
""".trimIndent()
        TaskType.SKILL_MANAGEMENT -> """
## Current Task Mode: SKILL_MANAGEMENT
- Primary channel: skill channel and self-evolution channel.
- Supporting channels: memory channel for durable preferences and role state, artifact channel if the user wants a page/app to manage skills.
- Use skill inventory, market, or creation tools. Keep new skills disabled/on-demand unless the user promotes them.
- Skills and tools are category-grouped. Inspect the smallest relevant category first, then call one tool inside it.
- For role/persona requests, use `role_manager` to list, create, update, delete, or activate roles. Use `switch_role` only for simple activation.
""".trimIndent()
        TaskType.CODE_EXECUTION -> """
## Current Task Mode: CODE_EXECUTION
- Primary channel: code/tool channel.
- Supporting channels: memory channel for notes and results, skill channel only if a reusable helper should be created.
- Use shell/python/pip tools only when needed. Report command results clearly.
""".trimIndent()
        TaskType.CHAT, TaskType.GENERAL -> """
## Current Task Mode: GENERAL
- Primary channel: chat channel.
- Supporting channels: memory channel, skill channel, self-evolution channel, artifact channel, tool channel when the task is better solved by action than explanation.
- This is a conversation-first orchestration mode, not a capability limit.
- Skills and tools are grouped by category; do not treat them as a flat buffet.
- Answer directly when no tool is needed, but do not claim you lack tools if a helper can solve the task better.
- Use helper tools when the user's request benefits from memory, skills, roles, files, pages, web lookup, model switching, or self-improvement.
- If the user is asking you to create, update, inspect, repair, or extend your own skills/tools/roles/pages/files, treat that as a valid task and use the matching tools.
- For casual conversation, humor, teasing, celebration, awkwardness, comfort, thanks, surprise, speechless moments, or meme-like replies, actively consider `sticker_bqb`.
- When the user gives durable personal preferences or asks you to remember/configure something, store it with `user_config` for explicit user settings or `user_profile`/`memory` for inferred facts.
- Before personalized advice, prefer the injected User Memory and Configuration context; call `user_config` or `user_profile` only if you need fresher or complete details.
- Only call `sticker_bqb` when the sticker's query matches your intended reaction or emotion. Send at most one sticker per turn, and do not use it for serious, professional, or safety-critical answers.
- If you send a sticker, keep accompanying text short and natural; do not explain the sticker as an attachment.
""".trimIndent()
    }

    private fun allowedSkillIds(taskType: TaskType): List<String> = when (taskType) {
        TaskType.PHONE_CONTROL -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.PHONE,
            SkillToolCategory.VPN,
            SkillToolCategory.SYSTEM,
            SkillToolCategory.MEMORY,
        )
        TaskType.WEB_RESEARCH -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.WEB,
            SkillToolCategory.VPN,
            SkillToolCategory.MEMORY,
        )
        TaskType.FILE_CREATE -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.CODE,
            SkillToolCategory.MEMORY,
            SkillToolCategory.SKILL,
        )
        TaskType.APP_BUILD -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.MEMORY,
            SkillToolCategory.MEDIA,
        )
        TaskType.IMAGE_GENERATION -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.MEDIA,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEMORY,
        )
        TaskType.VPN_CONTROL -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.VPN,
            SkillToolCategory.SYSTEM,
            SkillToolCategory.MEMORY,
        )
        TaskType.SKILL_MANAGEMENT -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.MEMORY,
        )
        TaskType.CODE_EXECUTION -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.CODE,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEMORY,
        )
        TaskType.CHAT, TaskType.GENERAL -> SkillToolTaxonomy.idsFor(
            SkillToolCategory.CHAT,
            SkillToolCategory.MEMORY,
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEDIA,
            SkillToolCategory.WEB,
            SkillToolCategory.SYSTEM,
        )
    }

    private fun allowedSkillCategories(taskType: TaskType): Set<SkillToolCategory> = when (taskType) {
        TaskType.PHONE_CONTROL -> setOf(
            SkillToolCategory.PHONE,
            SkillToolCategory.VPN,
            SkillToolCategory.SYSTEM,
            SkillToolCategory.MEMORY,
        )
        TaskType.WEB_RESEARCH -> setOf(
            SkillToolCategory.WEB,
            SkillToolCategory.VPN,
            SkillToolCategory.MEMORY,
        )
        TaskType.FILE_CREATE -> setOf(
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.CODE,
            SkillToolCategory.MEMORY,
            SkillToolCategory.SKILL,
        )
        TaskType.APP_BUILD -> setOf(
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.MEMORY,
            SkillToolCategory.MEDIA,
        )
        TaskType.IMAGE_GENERATION -> setOf(
            SkillToolCategory.MEDIA,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEMORY,
        )
        TaskType.VPN_CONTROL -> setOf(
            SkillToolCategory.VPN,
            SkillToolCategory.SYSTEM,
            SkillToolCategory.MEMORY,
        )
        TaskType.SKILL_MANAGEMENT -> setOf(
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.MEMORY,
        )
        TaskType.CODE_EXECUTION -> setOf(
            SkillToolCategory.CODE,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEMORY,
        )
        TaskType.CHAT, TaskType.GENERAL -> setOf(
            SkillToolCategory.CHAT,
            SkillToolCategory.MEMORY,
            SkillToolCategory.SKILL,
            SkillToolCategory.SELF_EVOLUTION,
            SkillToolCategory.ARTIFACT,
            SkillToolCategory.MEDIA,
            SkillToolCategory.WEB,
            SkillToolCategory.SYSTEM,
        )
    }

    private fun shouldExposeCategorizedSkill(
        meta: SkillMeta,
        goal: String,
        taskType: TaskType,
        forcedSkillIds: Set<String>,
    ): Boolean {
        if (meta.id in forcedSkillIds) return true
        if (meta.injectionLevel <= 1) return true
        if (taskType == TaskType.SKILL_MANAGEMENT) return true
        return skillMatchesGoal(meta, goal)
    }

    private fun skillMatchesGoal(meta: SkillMeta, goal: String): Boolean {
        val text = goal.lowercase().trim()
        if (text.isBlank()) return false
        val seed = buildString {
            append(meta.id.lowercase())
            append(' ')
            append(meta.name.lowercase())
            append(' ')
            append(meta.nameZh.orEmpty().lowercase())
            append(' ')
            append(meta.description.lowercase())
            append(' ')
            append(meta.descriptionZh.orEmpty().lowercase())
            append(' ')
            append(meta.tags.joinToString(" ").lowercase())
            append(' ')
            append(meta.categories.joinToString(" ") { it.name.lowercase() })
        }
        val directNames = listOf(meta.id, meta.name, meta.nameZh.orEmpty())
            .map { it.lowercase().trim() }
            .filter { it.length >= 2 }
        if (directNames.any { it in text || text in it }) return true
        val goalTerms = text
            .split(Regex("""[\s,，。.!?！？;；:：/\\|()\[\]{}"'`~]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (goalTerms.any { it in seed }) return true
        return meta.tags.any { tag -> tag.length >= 2 && text.contains(tag.lowercase()) }
    }

    private fun applyMemoryToolPolicy(
        allowed: List<String>,
        taskType: TaskType,
        memoryContext: String,
    ): List<String> {
        if (memoryContext.isBlank()) return allowed
        val lower = memoryContext.lowercase()
        var result = allowed
        val noWeb = lower.contains("不要搜索") ||
            lower.contains("不要网页搜索") ||
            lower.contains("不要联网") ||
            lower.contains("不用联网") ||
            lower.contains("no web search") ||
            lower.contains("offline only") ||
            lower.contains("no_web_search") ||
            lower.contains("image_understanding.no_web_search") ||
            (lower.contains("网页搜索") && listOf("不该", "不希望", "老是", "总是", "经常", "不停").any { lower.contains(it) })
        if (noWeb && taskType != TaskType.WEB_RESEARCH) {
            result = result.filterNot { it in listOf("web_search", "fetch_url", "web_browse", "web_content", "web_js") }
        }
        val noPhone = lower.contains("不要操作手机") || lower.contains("no phone control")
        if (noPhone && taskType != TaskType.PHONE_CONTROL) {
            result = result.filterNot { it in listOf("see_screen", "screenshot", "tap", "scroll", "input_text", "long_click", "navigate", "phone_status") }
        }
        val preferNativePage = lower.contains("页面优先生成原生页面") ||
            lower.contains("优先生成原生页面") ||
            lower.contains("ai native page") ||
            lower.contains("native page first")
        val preferMiniApp = lower.contains("程序优先生成miniapp") ||
            lower.contains("优先生成miniapp") ||
            lower.contains("prefer miniapp")
        if (taskType == TaskType.APP_BUILD) {
            result = when {
                preferNativePage && !preferMiniApp -> result.sortedBy { if (it == "ui_builder") 0 else 1 }
                preferMiniApp && !preferNativePage -> result.sortedBy { if (it == "app_manager") 0 else 1 }
                else -> result
            }
        }
        return result
    }

    private fun narrowByGoal(allowed: List<String>, taskType: TaskType, goal: String): List<String> {
        val text = goal.lowercase()
        if (text.isBlank()) return allowed
        val artifactPreference = detectArtifactToolPreferenceFromGoal(text)
        val preferred = when (taskType) {
            TaskType.PHONE_CONTROL -> when {
                text.anyContains("截图", "看屏幕", "识别", "布局", "界面", "坐标", "当前页面") ->
                    listOf("see_screen", "screenshot", "phone_status")
                text.anyContains("点击", "tap", "打开", "选择", "进入", "切换", "提交") ->
                    listOf(
                        "see_screen",
                        "phone_status",
                        "list_apps",
                        "navigate",
                        "bg_read_screen",
                        "bg_screenshot",
                        "tap",
                        "scroll",
                        "long_click",
                        "input_text",
                    )
                text.anyContains("输入", "搜索", "填写", "键入", "type") ->
                    listOf("input_text", "tap", "see_screen")
                else -> listOf(
                    "see_screen",
                    "phone_status",
                    "list_apps",
                    "navigate",
                    "bg_read_screen",
                    "bg_screenshot",
                    "tap",
                    "scroll",
                    "input_text",
                    "long_click",
                    "screenshot",
                )
            }
            TaskType.WEB_RESEARCH -> when {
                text.anyContains("搜索", "查找", "研究", "找到", "资料", "来源", "新闻", "最新") ->
                    listOf("web_search", "web_browse", "fetch_url", "web_content")
                text.anyContains("抓取", "网页", "打开", "页面", "提取", "阅读") ->
                    listOf("fetch_url", "web_browse", "web_content", "web_js")
                else -> listOf("web_search", "fetch_url", "web_browse", "web_content")
            }
            TaskType.FILE_CREATE -> when {
                text.anyContains("ppt", "pptx", "word", "doc", "docx", "excel", "xlsx", "pdf", "文档", "报告") ->
                    listOf("generate_document", "create_html", "read_file", "list_files")
                text.anyContains("html", "页面", "预览", "报告") ->
                    listOf("create_html", "create_file", "read_file", "list_files")
                else -> listOf("create_file", "read_file", "list_files", "generate_document", "create_html")
            }
            TaskType.APP_BUILD -> when {
                artifactPreference == ArtifactToolPreference.MINI_APP ->
                    listOf("app_manager", "read_file", "create_file", "list_files", "create_html")
                artifactPreference == ArtifactToolPreference.AI_PAGE ->
                    listOf("ui_builder", "read_file", "create_file", "list_files")
                text.anyContains("页面", "原生", "native", "ui", "dashboard", "settings", "列表", "卡片") ->
                    listOf("ui_builder", "read_file", "create_file", "list_files")
                text.anyContains("miniapp", "程序", "应用", "游戏", "webview", "html") ->
                    listOf("app_manager", "read_file", "create_file", "list_files", "create_html")
                else -> listOf("ui_builder", "app_manager", "create_html", "read_file", "create_file", "list_files")
            }
            TaskType.IMAGE_GENERATION -> when {
                text.anyContains("icon", "头像", "图标", "logo") ->
                    listOf("generate_icon", "generate_image")
                text.anyContains("视频", "动图", "animation") ->
                    listOf("generate_video")
                else -> listOf("generate_image", "generate_icon", "generate_video")
            }
            TaskType.VPN_CONTROL -> listOf("vpn_control")
            TaskType.SKILL_MANAGEMENT -> when {
                text.anyContains("查看", "检查", "inventory", "现有", "是否已经有") ->
                    listOf("skill_check", "skill_notes")
                text.anyContains("安装", "市场", "search", "导入", "获取") ->
                    listOf("skill_market", "quick_skill", "create_skill")
                text.anyContains("角色", "切换", "persona", "自我升级", "调整") ->
                    listOf("role_manager", "switch_role", "skill_check")
                else -> listOf("skill_check", "quick_skill", "skill_market", "create_skill", "skill_notes", "role_manager", "switch_role")
            }
            TaskType.CODE_EXECUTION -> when {
                text.anyContains("python", "脚本", "代码") ->
                    listOf("run_python", "shell", "pip_install")
                text.anyContains("安装", "依赖", "pip") ->
                    listOf("pip_install", "run_python", "shell")
                else -> listOf("shell", "run_python", "pip_install", "read_file", "create_file", "list_files")
            }
            TaskType.CHAT, TaskType.GENERAL -> when {
                artifactPreference == ArtifactToolPreference.MINI_APP ->
                    listOf("app_manager", "read_file", "create_file", "list_files", "create_html", "ui_builder")
                artifactPreference == ArtifactToolPreference.AI_PAGE ->
                    listOf("ui_builder", "app_manager", "create_html", "read_file", "create_file", "list_files")
                text.anyContains("记忆", "记住", "偏好", "配置", "用户画像") ->
                    listOf("memory", "user_profile", "user_config")
                text.anyContains("角色", "切换", "persona", "风格") ->
                    listOf("role_manager", "switch_role", "memory")
                text.anyContains("技能", "skill", "创建技能", "能力") ->
                    listOf("skill_check", "skill_market", "quick_skill", "create_skill", "skill_notes")
                text.anyContains("miniapp", "mini app", "小程序", "程序", "应用", "game", "游戏", "webview", "html", "javascript", "canvas") ->
                    listOf("app_manager", "read_file", "create_file", "list_files", "create_html", "ui_builder")
                text.anyContains("页面", "ui", "原生", "app", "应用中心") ->
                    listOf("ui_builder", "app_manager", "create_html", "read_file", "create_file")
                text.anyContains("图片", "图标", "头像", "视频", "bqb", "表情") ->
                    listOf("generate_image", "generate_icon", "generate_video", "sticker_bqb")
                text.anyContains("网页", "搜索", "资料", "查找", "联网") ->
                    listOf("web_search", "fetch_url", "web_browse", "web_content", "web_js")
                else -> allowed
            }
        }
        return allowed.filter { it in preferred }.ifEmpty { allowed }
    }

    // When a continuation already carries an artifact contract or workspace anchor, prefer that tool
    // over broad keyword matches like "页面" or "UI", which previously misrouted MiniAPP patch flows.
    private fun detectArtifactToolPreferenceFromGoal(text: String): ArtifactToolPreference? = when {
        text.contains("artifact_type=miniapp") ||
            text.contains("current miniapp target:") ||
            text.contains("current artifact: miniapp") ||
            text.contains("latest artifact action:") && text.contains("app_manager") ->
            ArtifactToolPreference.MINI_APP
        text.contains("artifact_type=ai_native_page") ||
            text.contains("current ai native page target:") ||
            text.contains("current artifact: ai_native_page") ||
            text.contains("latest artifact action:") && text.contains("ui_builder") ->
            ArtifactToolPreference.AI_PAGE
        else -> null
    }

    private enum class ArtifactToolPreference {
        MINI_APP,
        AI_PAGE,
    }

    private fun String.anyContains(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }
}
