package com.mobileclaw.agent

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.Message
import com.mobileclaw.ui.ActiveWorkflow

data class AiTaskRouteDecision(
    val taskType: TaskType?,
    val requiresExecution: Boolean,
    val confidence: Float,
    val reason: String,
    val normalizedGoal: String,
    val targetApp: String,
    val primaryChannel: ChannelType?,
    val supportingChannels: List<ChannelType>,
    val toolHints: List<String>,
    val userVisibleSteps: List<String>,
)

data class IntentContextPack(
    val compressedContext: String,
    val recentContext: String,
    val activeWorkflowSummary: String = "",
    val roleSummary: String = "",
) {
    fun toPromptBlock(maxChars: Int = 5200): String = buildString {
        appendLine("## Context Pack")
        if (compressedContext.isNotBlank()) {
            appendLine()
            appendLine("### Compressed Complete Context")
            appendLine(compressedContext.take(2200))
        }
        if (recentContext.isNotBlank()) {
            appendLine()
            appendLine("### Recent Raw Context")
            appendLine(recentContext.take(2200))
        }
        if (activeWorkflowSummary.isNotBlank()) {
            appendLine()
            appendLine("### Active Workflow")
            appendLine(activeWorkflowSummary.take(500))
        }
        if (roleSummary.isNotBlank()) {
            appendLine()
            appendLine("### Active Role")
            appendLine(roleSummary.take(500))
        }
    }.trim().take(maxChars)
}

internal object AiIntentRoutingPrompt {
    fun build(
        goal: String,
        contextPack: IntentContextPack,
        hasImage: Boolean,
        hasFile: Boolean,
        activeWorkflow: ActiveWorkflow?,
    ): String = """
Route the latest MobileClaw user message into either direct chat or an agent execution path.

Your most important decision is whether THIS LATEST TURN needs the agent runtime.
The agent runtime is only for work beyond a normal reply: operating the phone, browsing or searching the web, creating or editing files, building MiniAPPs, pages, or artifacts, generating media, running code, changing skills or settings, or continuing a previous execution task.
If a normal assistant answer, explanation, acknowledgement, casual conversation, or emotional response satisfies the latest turn, route it to direct chat.

Latest user message:
$goal

Input flags:
- has_image: $hasImage
- has_file: $hasFile

Active workflow:
${activeWorkflow?.let { "type=${it.taskType}; original_goal=${it.originalGoal.take(800)}" } ?: "none"}

${contextPack.toPromptBlock()}

Available task_type values:
CHAT, GENERAL, PHONE_CONTROL, WEB_RESEARCH, FILE_CREATE, APP_BUILD, IMAGE_GENERATION, VPN_CONTROL, SKILL_MANAGEMENT, CODE_EXECUTION

Available channel values:
CHAT, INFO, MEMORY, SKILL, SELF_EVOLUTION, PLAN, ARTIFACT, PHONE, WEB, MEDIA, VPN, CODE

Routing principles:
- Decide from meaning and context, not fixed keywords.
- Infer the concrete goal for THIS turn from the compressed complete context and recent raw context. The latest user message wins when context conflicts.
- Set requires_execution=true when the user asks MobileClaw to inspect, create, change, run, operate, search, continue, retry, configure, persist memory, or select and use tools.
- Set requires_execution=false only when a normal answer, explanation, acknowledgement, casual conversation, or pure capability-directory answer is sufficient.
- Direct non-execution route: use task_type=CHAT. Use primary_channel=CHAT for conversation or primary_channel=INFO for a capability-directory question. Always use supporting_channels=[], tool_hints=[], and user_visible_steps=[].
- Use direct chat for greetings, small talk, thanks, emotional support, explanations, questions, ordinary conversation, and requests such as "Just chat with me."
- Questions such as "What kinds of apps can you build?" or "Can you generate images or video?" are informational when they ask only about available capabilities.
- Do not use INFO when the message asks for a concrete action, even when phrased as a question. "Can you build a page for me?", "Please handle this problem", and "Please find out why this integration does not work" require execution.
- "Can you connect MCP?" may be informational when it asks only whether that capability exists. "Connect this MCP server" requires execution.
- Do not add MEMORY, SKILL, ARTIFACT, PLAN, or tool_hints to direct chat merely because those capabilities exist. Supporting channels mean the runtime should actually use them in this turn.
- Use a non-chat execution route only when the latest turn asks MobileClaw to act, create, inspect, modify, operate, search, generate, run, continue, retry, or revise something beyond a normal text reply.

Continuation and workflow context:
- Active workflow is context, not an automatic continuation command. Continue it only when the latest message explicitly asks to continue, revise, retry, or clearly refers to the previous artifact or task.
- A short "Continue" should continue the active or latest execution only when context clearly identifies that execution.
- English example: ["Hi", "Build me a mini-app", "Continue", "Great, just chat with me now"] routes as [CHAT, APP_BUILD/ARTIFACT, APP_BUILD/ARTIFACT, CHAT].
- "Stop working on that and just chat with me" exits execution context and routes to CHAT.
- Ordinary chat, emotion, small talk, or entertainment routes to CHAT even when an active workflow exists.

Execution routing:
- Use PHONE_CONTROL when the user asks MobileClaw to operate another phone app or inspect the screen.
- Requests such as "Open Gmail", "Search inside Reddit", "Order dinner in DoorDash", or "Navigate in Maps" use PHONE_CONTROL. These are examples, not an app allowlist.
- If the user explicitly asks to create or update a mini-app, app, program, or game, or requests HTML, CSS, JavaScript, WebView, canvas, Python-backend, SQLite, or other runtime behavior, use APP_BUILD with primary_channel=ARTIFACT and include app_manager in tool_hints.
- If the user explicitly asks for an AI Native Page, native page, dashboard, form, or management page, use APP_BUILD with primary_channel=ARTIFACT and include ui_builder in tool_hints.
- Do not route explicit MiniAPP, program, or runtime requests to ui_builder.
- Do not route explicit native-page requests to app_manager unless runtime features are also explicitly requested.
- If the user attaches an image and asks what it is, use GENERAL with primary_channel=CHAT, not WEB, unless web lookup is explicitly requested.

User-visible output contract:
- Write reason as concise English regardless of the input language.
- Write normalized_goal as a clear, concise, executable goal in English regardless of the input language.
- Write user_visible_steps as concise, natural English regardless of the input language.
- Preserve the actual app or product name in target_app; do not artificially translate a proper name.
- Generate 2-4 short, concrete, user-facing steps only for execution routes. Describe what the AI is about to do in language the user can understand.
- For direct chat, user_visible_steps must be [].
- Never mention internal configuration keys, endpoint names, gateway field names, API-key fields, capability IDs, or raw parameter names in normal user_visible_steps unless the user is explicitly debugging configuration.
- Good steps: "Find nearby restaurants that match the request", "Open the target app and navigate to the order screen", "Complete the missing piano-key code and fix the error".
- Bad steps: "Confirm the goal", "Continue the workflow", "Verify the result", "Improve the implementation".
- Tool hints are optional known tool IDs. Include only obvious IDs and leave the array empty when unsure.
- For direct chat, tool_hints must be [] even if memory might improve the wording.
- Set confidence above 0.7 when the channel is clear. Use low confidence only when the latest message is genuinely ambiguous.
- Never output placeholder values from the example. Fill every field for the actual latest user message.

Return JSON only:
{
  "task_type": "PHONE_CONTROL",
  "requires_execution": true,
  "confidence": 0.92,
  "reason": "The user wants MobileClaw to operate a named phone app.",
  "normalized_goal": "Open DoorDash and navigate to the restaurant ordering screen.",
  "target_app": "DoorDash",
  "primary_channel": "PHONE",
  "supporting_channels": ["MEMORY","PLAN"],
  "tool_hints": ["see_screen","phone_status"],
  "user_visible_steps": ["Confirm the target app and requested order", "Check phone-control access", "Open DoorDash and navigate to the order screen"]
}
""".trimIndent()
}

class AiIntentRouter(
    private val llm: LlmGateway,
) {
    companion object {
        private const val TAG = "AiIntentRouter"
    }

    suspend fun decide(
        goal: String,
        contextPack: IntentContextPack,
        hasImage: Boolean,
        hasFile: Boolean,
        activeWorkflow: ActiveWorkflow?,
    ): AiTaskRouteDecision? {
        val prompt = AiIntentRoutingPrompt.build(goal, contextPack, hasImage, hasFile, activeWorkflow)
        val raw = try {
            llm.chat(
                ChatRequest(
                    messages = listOf(
                        Message(
                            role = "system",
                            content = "You are MobileClaw's intent router. Return one strict JSON object only. Do not answer the user. Do not wrap JSON in markdown.",
                        ),
                        Message(role = "user", content = prompt),
                    ),
                    tools = emptyList(),
                    stream = false,
                )
            ).content.orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Primary routing failed for goal=${goal.take(160)}", t)
            ""
        }
        return parseDecision(raw) ?: repairDecision(prompt, raw)
    }

    private suspend fun repairDecision(originalPrompt: String, invalidOutput: String): AiTaskRouteDecision? {
        if (invalidOutput.isBlank()) return null
        val raw = try {
            llm.chat(
                ChatRequest(
                    messages = listOf(
                        Message(
                            role = "system",
                            content = "Repair invalid router output into one strict JSON object only. Do not explain. Do not use markdown.",
                        ),
                        Message(
                            role = "user",
                            content = """
The previous router output was not valid JSON.

Original routing request:
${originalPrompt.take(2200)}

Invalid output:
${invalidOutput.take(1600)}

Return only a valid JSON object with these keys:
task_type, requires_execution, confidence, reason, normalized_goal, target_app, primary_channel, supporting_channels, tool_hints, user_visible_steps

Enum values must be uppercase exactly as documented. Arrays must be JSON arrays of strings.
The reason, normalized_goal, and user_visible_steps fields must be written in English. Preserve actual app and product names in target_app.
""".trimIndent(),
                        ),
                    ),
                    tools = emptyList(),
                    stream = false,
                )
            ).content.orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "Router repair failed. invalidOutput=${invalidOutput.take(300)}", t)
            ""
        }
        return parseDecision(raw)
    }

    private fun parseDecision(raw: String): AiTaskRouteDecision? {
        val jsonText = raw.let { text ->
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end >= start) text.substring(start, end + 1) else text
        }
            .trim()
        val obj = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (t: Throwable) {
            if (jsonText.isNotBlank()) {
                Log.w(TAG, "Router JSON parse failed: ${jsonText.take(300)}", t)
            }
            return null
        }
        val taskType = obj.string("task_type").toTaskTypeOrNull()
        val primaryChannel = obj.string("primary_channel").toChannelTypeOrNull()
        val rawConfidence = obj.float("confidence").coerceIn(0f, 1f)
        val confidence = when {
            rawConfidence > 0f -> rawConfidence
            taskType != null && primaryChannel != null -> 0.62f
            else -> 0f
        }
        return AiTaskRouteDecision(
            taskType = taskType,
            requiresExecution = obj.boolean("requires_execution"),
            confidence = confidence,
            reason = obj.string("reason"),
            normalizedGoal = obj.string("normalized_goal"),
            targetApp = obj.string("target_app"),
            primaryChannel = primaryChannel,
            supportingChannels = obj.stringList("supporting_channels").mapNotNull { it.toChannelTypeOrNull() }.distinct(),
            toolHints = obj.stringList("tool_hints").map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            userVisibleSteps = obj.stringList("user_visible_steps").map { it.trim() }.filter { it.isNotBlank() }.take(6),
        )
    }

    private fun JsonObject.string(name: String): String =
        try {
            get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        } catch (_: Throwable) {
            ""
        }

    private fun JsonObject.float(name: String): Float =
        try {
            get(name)?.takeIf { !it.isJsonNull }?.asFloat ?: 0f
        } catch (_: Throwable) {
            0f
        }

    private fun JsonObject.boolean(name: String): Boolean =
        try {
            get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        } catch (_: Throwable) {
            false
        }

    private fun JsonObject.stringList(name: String): List<String> {
        val element = get(name)?.takeIf { !it.isJsonNull } ?: return emptyList()
        return when {
            element.isJsonArray -> element.asJsonArray.toStringList()
            element.isJsonPrimitive -> element.asString.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun JsonArray.toStringList(): List<String> =
        mapNotNull { element -> try { element.asString } catch (_: Throwable) { null } }

    private fun String.toTaskTypeOrNull(): TaskType? =
        try { TaskType.valueOf(toEnumToken()) } catch (_: Throwable) { null }

    private fun String.toChannelTypeOrNull(): ChannelType? =
        try { ChannelType.valueOf(toEnumToken()) } catch (_: Throwable) { null }

    private fun String.toEnumToken(): String =
        trim()
            .uppercase()
            .replace('-', '_')
            .replace(' ', '_')
}
