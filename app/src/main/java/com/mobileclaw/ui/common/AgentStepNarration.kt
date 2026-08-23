package com.mobileclaw.ui.common

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.llm.cleanLocalGeneratedText

internal val VISUAL_SKILL_IDS = setOf("screenshot", "see_screen", "bg_screenshot")

private fun quoteForUi(text: String): String = "\"$text\""

internal fun sanitizeUserFacingNarration(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    val lowered = text.lowercase()
    return when {
        "video_api_endpoint" in lowered || "video_api_key" in lowered ->
            "Checking whether video generation is connected"
        "image_api_endpoint" in lowered || "image_api_key" in lowered ->
            "Checking whether image generation is connected"
        ("gateway" in lowered || "capability" in lowered || "endpoint" in lowered || "api key" in lowered) &&
            ("video" in lowered || "image" in lowered) ->
            if ("video" in lowered) {
                "Checking whether video generation is connected"
            } else {
                "Checking whether image generation is connected"
            }
        "loaded and active" in lowered ->
            "Confirming the current capability is connected"
        text.equals("organizing the current progress", ignoreCase = true) ||
            text.equals("the plan for this round is ready", ignoreCase = true) ->
            "Organizing the current progress"
        else -> text
    }
}

internal fun friendlyThinkingUpdate(rawThought: String, plannedSteps: List<String>): String {
    val clean = sanitizeUserFacingNarration(rawThought.cleanLocalGeneratedText().trim())
    val planned = plannedSteps.firstOrNull { it.isNotBlank() }?.let(::sanitizeUserFacingNarration)
    if (clean.isBlank()) return planned ?: "Organizing the current progress"
    val generic = listOf(
        "thinking complete",
        "analyzing next step",
        "reflection complete",
        "reviewing progress",
    )
    if (generic.any { clean.contains(it, ignoreCase = true) }) {
        return planned ?: when {
            clean.contains("repeat", ignoreCase = true) -> "The last step was not effective, trying another path"
            clean.contains("checkpoint", ignoreCase = true) || clean.contains("20 steps", ignoreCase = true) -> "Reviewing completed actions before continuing"
            clean.contains("review", ignoreCase = true) -> "Turning the previous result into the next step"
            else -> "Choosing the next step from the current result"
        }
    }
    return clean.take(120)
}

internal fun plannedStageForAction(plannedSteps: List<String>, actionIndex: Int): String {
    if (plannedSteps.isEmpty()) return ""
    val index = actionIndex.coerceIn(0, plannedSteps.lastIndex)
    return sanitizeUserFacingNarration(plannedSteps[index].trim())
}

internal fun stageAwareSkillDescription(stage: String, skillId: String, params: Map<String, Any>): String {
    val toolPurpose = friendlySkillDescription(skillId, params)
    if (stage.isBlank()) return toolPurpose
    return when {
        stage.contains(toolPurpose) -> stage
        toolPurpose == skillId -> stage
        else -> "$stage: $toolPurpose"
    }.take(140)
}

internal fun userFacingActionResult(skillId: String, stageText: String): String =
    when (skillId) {
        "tap", "long_click", "scroll", "input_text", "navigate", "bg_launch" -> ""
        "see_screen", "read_screen", "bg_read_screen", "screenshot", "bg_screenshot" -> ""
        "web_search", "fetch_url", "web_browse", "web_content", "web_js" -> ""
        "app_manager" -> if (stageText.contains("MiniAPP")) "This step changes the MiniAPP content and structure" else ""
        "ui_builder" -> if (stageText.contains("native", ignoreCase = true) || stageText.contains("page", ignoreCase = true)) {
            "This step changes the native page display or interaction"
        } else ""
        "memory", "user_config" -> ""
        else -> ""
    }

internal fun conciseUserPlanSummary(text: String, limit: Int = 90): String {
    val normalized = sanitizeUserFacingNarration(text)
        .lineSequence()
        .map { line ->
            line.trim().trimStart { char ->
                char.isDigit() || char.isWhitespace() || char.category in setOf(
                    CharCategory.CONNECTOR_PUNCTUATION,
                    CharCategory.DASH_PUNCTUATION,
                    CharCategory.OTHER_PUNCTUATION,
                )
            }
        }
        .filter { it.isNotBlank() }
        .joinToString("; ")
        .replace(Regex(" {2,}"), " ")
        .trim()
    return when {
        normalized.isBlank() -> ""
        normalized.length <= limit -> normalized
        else -> normalized.take(limit).trimEnd() + "…"
    }
}

internal fun userFacingPlanResult(steps: List<String>, summary: String): String =
    when {
        steps.size >= 2 -> "First: ${steps.first().trim()}; then: ${steps[1].trim()}"
        steps.size == 1 -> "Starting with: ${steps.first().trim()}"
        summary.isNotBlank() -> conciseUserPlanSummary(summary)
        else -> "The plan for this round is ready"
    }

internal fun userFacingInitialIntent(firstStep: String?, secondStep: String?, channelSummary: String): String =
    when {
        !firstStep.isNullOrBlank() ->
            firstStep.trim()
        channelSummary.isNotBlank() ->
            channelSummary.trim().trimEnd { it.category == CharCategory.OTHER_PUNCTUATION }
        !secondStep.isNullOrBlank() ->
            secondStep.trim()
        else -> ""
    }

internal fun userFacingThinkingResult(thought: String, plannedSteps: List<String>): String {
    val current = plannedSteps.firstOrNull { it.isNotBlank() }?.let(::sanitizeUserFacingNarration).orEmpty()
    val next = plannedSteps.drop(1).firstOrNull { it.isNotBlank() }?.let(::sanitizeUserFacingNarration).orEmpty()
    val clean = sanitizeUserFacingNarration(thought.cleanLocalGeneratedText().trim()).take(100)
    return when {
        current.isNotBlank() && next.isNotBlank() -> "$current / $next"
        current.isNotBlank() -> current
        clean.isNotBlank() ->
            clean
        else -> ""
    }
}

internal fun userFacingSkillStart(stageText: String, skillId: String, params: Map<String, Any>): String {
    fun p(key: String) = params[key]?.toString()?.trim().orEmpty()
    val stage = stageText.trim()
    val concrete = when (skillId) {
        "web_search" -> p("query").takeIf { it.isNotBlank() }?.let { "Searching for ${quoteForUi(it)}" }
        "fetch_url", "web_browse" -> p("url").takeIf { it.isNotBlank() }?.let { "Opening this page to look for useful information" }
        "web_content" -> "Reading the page and filtering useful content"
        "see_screen", "read_screen", "screenshot", "bg_screenshot", "bg_read_screen" -> "Reading the current screen before deciding the next action"
        "tap" -> p("label").ifBlank { p("text") }.takeIf { it.isNotBlank() }?.let { "Tapping ${quoteForUi(it)} to see what changes" }
        "long_click" -> p("label").ifBlank { p("text") }.takeIf { it.isNotBlank() }?.let { "Long-pressing ${quoteForUi(it)} to check hidden actions" }
        "scroll" -> "Scrolling to look for the target content"
        "input_text" -> p("text").takeIf { it.isNotBlank() }?.let { "Entering the required text: ${it.take(24)}" }
        "navigate" -> when {
            p("package_name").isNotBlank() -> "Opening the target app to an actionable screen"
            p("action") == "back" -> "Going back one level to verify the path"
            p("action") == "home" -> "Returning home to restart from the right entry"
            else -> "Switching the current screen to continue"
        }
        "list_apps" -> "Checking whether the app is installed"
        "app_manager" -> when (p("action")) {
            "create" -> "Creating MiniAPP"
            "update" -> "Updating MiniAPP"
            "validate" -> "Checking MiniAPP"
            "open" -> "Opening MiniAPP"
            else -> stage.ifBlank { "Handling MiniAPP" }
        }
        "ui_builder" -> when (p("action")) {
            "create" -> "Creating native page"
            "update" -> "Updating native page"
            "validate" -> "Checking native page"
            "open" -> "Opening native page"
            else -> stage.ifBlank { "Handling native page" }
        }
        "create_file" -> p("filename").takeIf { it.isNotBlank() }?.let { "Generating file: $it" }
        "create_html" -> p("title").takeIf { it.isNotBlank() }?.let { "Building the web result: $it" }
        "generate_image" -> "Generating the requested image"
        "generate_document" -> "Generating the document content"
        "memory" -> "Updating memory"
        "user_config" -> "Updating user configuration"
        "shell", "run_python" -> "Running command"
        else -> null
    }
    if (!concrete.isNullOrBlank()) return sanitizeUserFacingNarration(concrete)
    if (stage.isNotBlank()) return sanitizeUserFacingNarration(stage)
    return when {
        skillId in VISUAL_SKILL_IDS -> "Viewing the current screen"
        else -> ""
    }
}

internal fun userFacingActionNext(stageText: String, skillId: String, text: String): String? {
    val explicit = nextStepHint(skillId, text)
    if (!explicit.isNullOrBlank()) return explicit
    return when {
        stageText.isNotBlank() -> sanitizeUserFacingNarration(stageText.trim())
        skillId in VISUAL_SKILL_IDS -> "Continue after reading the screen"
        skillId == "web_search" || skillId == "web_content" || skillId == "fetch_url" || skillId == "web_browse" ->
            "Filter the information"
        else -> null
    }
}

internal fun friendlySkillDescription(skillId: String, params: Map<String, Any>): String {
    fun p(key: String) = params[key]?.toString()?.trim() ?: ""
    return when (skillId) {
        "screenshot", "bg_screenshot" -> "Read the current screen and decide the next action"
        "read_screen", "bg_read_screen", "see_screen" -> "Identify actionable entries and status on the screen"
        "tap" -> {
            val label = p("label").ifBlank { p("text") }.take(28)
            if (label.isNotBlank()) "Tap the area related to ${quoteForUi(label)}" else "Tap the current target"
        }
        "long_click" -> "Long-press the target to check for more actions"
        "scroll" -> "Scroll to find the target content"
        "input_text" -> {
            val text = p("text").take(32)
            if (text.isNotBlank()) "Enter the required text" else "Fill the current input field"
        }
        "navigate" -> when {
            p("action") == "back" -> "Go back one level and continue on the right path"
            p("action") == "home" -> "Return home and reopen the target app"
            p("package_name").isNotBlank() -> "Open the target app and enter the actionable screen"
            else -> "Switch screens and continue the task"
        }
        "list_apps" -> "Check whether the target app is installed"
        "web_search" -> {
            val q = p("query").take(40)
            if (q.isNotBlank()) "Search the web for ${quoteForUi(q)}" else "Search the web for relevant information"
        }
        "fetch_url", "web_browse" -> "Open the relevant page and verify its information"
        "web_content" -> "Read the page and extract useful content"
        "web_js" -> "Let the page finish loading so it can be read"
        "bg_launch" -> if (p("package_name").isNotBlank()) "Open the target app in the background" else "Prepare the background app environment"
        "bg_stop" -> "Stop the background app environment"
        "vd_setup" -> "Check whether the background environment is available"
        "memory" -> when (p("action")) {
            "set" -> "Save information for later use"
            "get" -> "Read memory to confirm preferences or history"
            "delete" -> "Delete an obsolete memory"
            "list" -> "Review existing memories"
            else -> "Update memory"
        }
        "shell" -> if (p("command").isNotBlank()) "Run a local command to check or complete the task" else "Run a local command"
        "permission" -> "Check whether the required permission is enabled"
        "quick_skill" -> "Prepare a new capability for this kind of task"
        "meta" -> "Review available capabilities"
        "skill_check" -> "Check whether a suitable capability already exists"
        "skill_market" -> "Find an installable capability"
        "generate_image" -> if (p("prompt").isNotBlank()) "Generate image content from the request" else "Generate image content"
        "create_file" -> if (p("filename").isNotBlank()) "Generate the requested file" else "Generate a file"
        "create_html" -> if (p("title").isNotBlank()) "Generate a previewable web result" else "Generate a web preview"
        "ui_builder" -> when (p("action")) {
            "create" -> "Create a native page for this result"
            "analyze_change" -> "Analyze how to update the existing native page safely"
            "update" -> "Update the existing native page"
            "validate" -> "Check whether the native page works"
            "open" -> "Open the native page"
            "list" -> "Find existing native pages"
            "get" -> "Read the existing page before editing"
            else -> "Handle native page content"
        }
        "switch_model" -> "Switch to a better model for this task"
        "switch_role" -> "Switch to a better role for this task"
        "user_config" -> when (p("action")) {
            "set" -> "Save user configuration"
            "get" -> "Read user configuration"
            "delete" -> "Delete a user configuration item"
            "list" -> "Review user configuration"
            else -> "Handle user configuration"
        }
        "app_manager" -> when (p("action")) {
            "create" -> "Generate a usable MiniAPP"
            "analyze_change" -> "Analyze how to update the existing MiniAPP safely"
            "update" -> "Continue updating the MiniAPP"
            "validate" -> "Check whether the MiniAPP works"
            "open" -> "Open the MiniAPP"
            "delete" -> "Delete an obsolete MiniAPP"
            "list" -> "Find existing MiniAPPs"
            else -> "Handle MiniAPP content"
        }
        else -> skillId
    }
}

internal fun friendlyObservationDescription(skillId: String?, text: String, hasImage: Boolean): String {
    artifactObservationSummary(skillId, text)?.let { return it }
    if (text.contains("error", ignoreCase = true) || text.contains("failed", ignoreCase = true)) {
        return when (skillId) {
            "app_manager" -> "The app step did not pass; fixing it from the error"
            "ui_builder" -> "The page step did not pass; fixing it from the error"
            "navigate", "tap", "scroll", "input_text", "long_click" -> "The phone action did not work as expected; trying a better action"
            else -> "This step did not work as expected; adjusting from the result"
        }
    }
    return when (skillId) {
        "web_search" -> "Found a set of results; filtering for the useful parts"
        "fetch_url", "web_browse", "web_content", "web_js" -> "The page content is available; extracting the relevant parts"
        "see_screen", "screenshot", "read_screen", "bg_screenshot", "bg_read_screen" ->
            if (hasImage) "The screen is visible now; deciding where to act next" else "The screen state has been read"
        "tap", "long_click", "scroll", "input_text", "navigate", "bg_launch" -> "The action was sent; checking whether the screen changed as expected"
        "list_apps" -> "The app list is available; finding the target app"
        "ui_builder" -> "The page update returned; checking whether it is correct"
        "app_manager" -> "The MiniAPP result returned; checking whether it can run"
        "create_file", "create_html", "generate_document" -> "The file was generated; confirming it can be opened"
        "generate_image", "generate_icon", "generate_video" -> "The generation result returned; preparing usable output"
        "memory", "user_config" -> "Personalization data was updated"
        "shell", "run_python", "pip_install" -> "The command finished; continuing from its output"
        "permission" -> "Permission status is available; checking whether anything critical is missing"
        else -> if (text.isBlank()) "This step has a result" else "The result for this step is available"
    }
}

private fun artifactObservationSummary(skillId: String?, text: String): String? {
    if (skillId !in setOf("app_manager", "ui_builder")) return null
    val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
    val action = payload.stringOrNull("action").orEmpty()
    val savedAsDraft = payload.stringOrNull("saved_as_draft").equals("true", ignoreCase = true)
    val summary = payload.stringOrNull("summary").orEmpty()
    val runtimeIssues = payload.stringListOrEmpty("runtime_issues")
    val preflightIssues = payload.stringListOrEmpty("preflight_issues")
    val errorLogs = payload.stringListOrEmpty("error_logs")
    val warnings = payload.stringListOrEmpty("preflight_warnings")
    return when (skillId) {
        "app_manager" -> when {
            action == "create" && savedAsDraft -> "The MiniAPP was saved, but the first check failed; fixing startup or runtime issues"
            action == "update" && savedAsDraft -> "The MiniAPP update was saved, but this version still needs fixes"
            action == "validate" && (runtimeIssues.isNotEmpty() || preflightIssues.isNotEmpty()) -> "The MiniAPP still has missing pieces or runtime issues; fixing from the check result"
            action == "inspect_logs" && errorLogs.isNotEmpty() -> "The MiniAPP logs still contain errors; locating and fixing them"
            action == "open" -> "The MiniAPP is open; if you are still in chat, it will be previewed in the corner first"
            summary.isNotBlank() -> sanitizeUserFacingNarration(summary).take(90)
            warnings.isNotEmpty() -> "The MiniAPP result was generated, with warnings to review"
            else -> "The MiniAPP step returned a result; checking whether it is usable"
        }
        "ui_builder" -> when {
            action == "create" -> "The native page was generated; checking display and behavior"
            action == "update" -> "The native page update is complete; checking whether it took effect"
            action == "validate" && (runtimeIssues.isNotEmpty() || preflightIssues.isNotEmpty()) -> "The page still has unresolved issues; fixing from the check result"
            action == "inspect_runtime" && errorLogs.isNotEmpty() -> "The page still has runtime errors; continuing to fix them"
            action == "open" -> "The page is open; checking whether it matches the request"
            summary.isNotBlank() -> sanitizeUserFacingNarration(summary).take(90)
            else -> "The page step returned a result; checking whether it is usable"
        }
        else -> null
    }
}

internal fun nextStepHint(skillId: String?, text: String): String? {
    val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    return when (skillId) {
        "app_manager" -> when {
            payload?.stringOrNull("saved_as_draft").equals("true", ignoreCase = true) ->
                "Review the check result and logs, then fix the parts that did not pass"
            payload?.stringOrNull("action") == "open" ->
                "Check whether the chat preview rendered correctly; inspect logs if needed"
            payload?.stringOrNull("action") == "inspect_logs" && payload.stringListOrEmpty("error_logs").isNotEmpty() ->
                "Fix from the log error, then run the check again"
            payload?.stringOrNull("action") == "validate" ->
                "Use the validation result to decide whether to keep fixing or open it"
            else -> "Continue confirming whether this result is actually usable"
        }
        "ui_builder" -> when {
            payload?.stringOrNull("action") == "validate" ->
                "Use the validation result to decide whether to keep fixing or open it"
            else -> "Continue checking whether the page matches the request"
        }
        "navigate", "tap", "scroll", "input_text", "long_click" ->
            "Read the screen again to confirm this step moved the task forward"
        "see_screen", "screenshot", "read_screen", "bg_screenshot", "bg_read_screen" ->
            "Act from the current screen instead of only reading it again"
        "web_search", "fetch_url", "web_browse", "web_content", "web_js" ->
            "Filter the retrieved content and keep only the useful conclusion"
        else -> null
    }
}

internal fun JsonObject.stringOrNull(name: String): String? =
    runCatching {
        get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
    }.getOrNull()

internal fun JsonObject.stringListOrEmpty(name: String): List<String> =
    runCatching {
        get(name)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                runCatching { element.asString.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
    }.getOrDefault(emptyList())
