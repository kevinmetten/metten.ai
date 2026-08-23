package com.mobileclaw.ui

import android.util.Log
import com.mobileclaw.agent.AiTaskRouteDecision
import com.mobileclaw.agent.ChannelType
import com.mobileclaw.agent.Role
import com.mobileclaw.agent.TaskClassifier
import com.mobileclaw.agent.TaskType
import com.mobileclaw.app.MiniApp
import com.mobileclaw.artifact.ArtifactHistoryEntry
import com.mobileclaw.artifact.ArtifactSpec
import com.mobileclaw.skill.SkillAttachment
import com.mobileclaw.ui.aipage.AiPageDef
import com.mobileclaw.ui.chat.ChatMessage
import com.mobileclaw.ui.chat.ConfirmationActionProtocol
import com.mobileclaw.ui.chat.MessageRole
import com.mobileclaw.workspace.WorkspaceExecutionContext

data class ContextualTaskIntent(
    val classificationGoal: String,
    val taskTypeOverride: TaskType? = null,
    val aiPage: AiPageDef? = null,
    val miniApp: MiniApp? = null,
    val fileAttachment: SkillAttachment.FileData? = null,
    val htmlAttachment: SkillAttachment.HtmlData? = null,
    val executionHint: String = "",
    val aiPrimaryChannel: ChannelType? = null,
    val aiSupportingChannels: List<ChannelType> = emptyList(),
    val aiToolHints: List<String> = emptyList(),
    val userVisibleSteps: List<String> = emptyList(),
    val aiRouteConfidence: Float? = null,
    val aiRouteReason: String = "",
    val aiRouteTargetApp: String = "",
    val disableToolNarrowing: Boolean = false,
)

enum class TaskRouteSource {
    CLASSIFIER,
    AI_ROUTER,
    ACTIVE_WORKFLOW,
    RECENT_CONTEXT,
}

data class TaskRoute(
    val taskType: TaskType,
    val contextualIntent: ContextualTaskIntent,
    val goalForExecution: String,
    val source: TaskRouteSource,
    val goalToRemember: String = goalForExecution,
    val debugReason: String = "",
)

data class ActiveWorkflow(
    val originalGoal: String,
    val taskType: TaskType,
    val roleId: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

class TaskRouter(
    private val aiPagesProvider: () -> List<AiPageDef>,
    private val miniAppsProvider: () -> List<MiniApp>,
    private val messagesProvider: () -> List<ChatMessage>,
    private val currentRoleProvider: () -> Role,
    private val workspaceContextProvider: () -> WorkspaceExecutionContext?,
) {
    companion object {
        private const val TAG = "TaskRouter"
        private val PHONE_CONTROL_SKILLS = setOf(
            "see_screen", "screenshot", "tap", "scroll", "input_text", "long_click",
            "navigate", "list_apps", "phone_status", "check_permissions",
        )
        private val APP_BUILD_SKILLS = setOf("ui_builder", "app_manager", "create_html")
        private val FILE_SKILLS = setOf("generate_document", "create_file", "read_file", "list_files")
        private val WEB_SKILLS = setOf("web_search", "fetch_url", "web_browse", "web_content", "web_js")
        private val IMAGE_SKILLS = setOf("generate_image", "generate_icon", "generate_video")
        private val CODE_SKILLS = setOf("shell", "run_python", "pip_install")
    }

    fun resolveWithAiDecision(
        goal: String,
        effectiveGoal: String,
        hasImage: Boolean,
        hasFile: Boolean,
        activeWorkflow: ActiveWorkflow?,
        decision: AiTaskRouteDecision,
    ): TaskRoute? {
        explicitArtifactIntent(goal)?.let { forcedIntent ->
            return TaskRoute(
                taskType = TaskType.APP_BUILD,
                contextualIntent = forcedIntent,
                goalForExecution = effectiveGoal,
                source = TaskRouteSource.AI_ROUTER,
                goalToRemember = effectiveGoal,
                debugReason = "Explicit artifact intent overrode AI router output.",
            )
        }
        if (decision.taskType == null || decision.confidence < 0.52f) {
            Log.w(TAG, "Rejecting AI route because taskType=${decision.taskType} confidence=${decision.confidence} goal=${goal.take(160)}")
            return null
        }
        if (decision.requiresExecution && decision.taskType == TaskType.CHAT && decision.primaryChannel == ChannelType.CHAT) {
            Log.w(
                TAG,
                "AI route direct chat rejected because requires_execution=true. goal=${goal.take(160)} reason=${decision.reason.take(160)}"
            )
            return resolveAsAgentFallback(
                goal = goal,
                effectiveGoal = effectiveGoal,
                reason = "Intent resolver marked this turn executable while route was direct chat; forcing agent execution.",
            )
        }
        if (decision.requiresExecution && decision.primaryChannel == ChannelType.INFO) {
            Log.w(
                TAG,
                "AI route INFO rejected because requires_execution=true. goal=${goal.take(160)} reason=${decision.reason.take(160)}"
            )
            return resolveAsAgentFallback(
                goal = goal,
                effectiveGoal = effectiveGoal,
                reason = "Intent resolver marked this turn executable while route was INFO; forcing agent execution.",
            )
        }
        val normalizedGoal = decision.normalizedGoal.ifBlank { effectiveGoal }
        val executionHint = buildString {
            appendLine("AI router selected this execution path from the latest user message and recent context.")
            appendLine("Router reason: ${decision.reason.ifBlank { "No extra reason." }}")
            decision.targetApp.takeIf { it.isNotBlank() }?.let { appendLine("Target app: $it") }
            appendLine("Normalized goal: ${normalizedGoal.take(1000)}")
            if (decision.userVisibleSteps.isNotEmpty()) {
                appendLine("User-visible step plan:")
                decision.userVisibleSteps.take(6).forEachIndexed { index, step ->
                    appendLine("${index + 1}. $step")
                }
            }
        }.trim()
        return TaskRoute(
            taskType = decision.taskType,
            contextualIntent = ContextualTaskIntent(
                classificationGoal = normalizedGoal,
                taskTypeOverride = decision.taskType,
                executionHint = executionHint,
                aiPrimaryChannel = decision.primaryChannel,
                aiSupportingChannels = decision.supportingChannels,
                aiToolHints = decision.toolHints,
                userVisibleSteps = normalizeUserVisibleSteps(decision.userVisibleSteps, decision.taskType, normalizedGoal, decision.targetApp),
                aiRouteConfidence = decision.confidence,
                aiRouteReason = decision.reason,
                aiRouteTargetApp = decision.targetApp,
            ),
            goalForExecution = normalizedGoal,
            source = TaskRouteSource.AI_ROUTER,
            goalToRemember = normalizedGoal,
            debugReason = "AI router accepted with confidence=${"%.2f".format(decision.confidence)} reason=${decision.reason.take(180)}",
        )
    }

    fun resolveAsAgentFallback(
        goal: String,
        effectiveGoal: String,
        reason: String,
    ): TaskRoute {
        val steps = listOf(
            "Determine the best way to handle the latest request",
            "Address the core part of the task directly",
            "Connect web, file, phone, or memory capabilities if needed",
        )
        return TaskRoute(
            taskType = TaskType.GENERAL,
            contextualIntent = ContextualTaskIntent(
                classificationGoal = effectiveGoal,
                taskTypeOverride = TaskType.GENERAL,
                executionHint = buildString {
                    appendLine("AI router fallback is active.")
                    appendLine("Fallback reason: $reason")
                    appendLine("User goal: ${goal.take(1000)}")
                    appendLine("Do not answer that tools are unavailable. Inspect available capabilities and choose the best channel/tool path.")
                }.trim(),
                aiRouteConfidence = 0f,
                aiRouteReason = reason,
                userVisibleSteps = steps,
                disableToolNarrowing = true,
            ),
            goalForExecution = effectiveGoal,
            source = TaskRouteSource.AI_ROUTER,
            debugReason = "AI router fallback: $reason",
        )
    }

    fun resolve(
        goal: String,
        effectiveGoal: String,
        hasImage: Boolean,
        hasFile: Boolean,
        activeWorkflow: ActiveWorkflow?,
    ): TaskRoute {
        if (!hasImage && !hasFile && activeWorkflow != null && shouldContinueActiveWorkflow(goal, activeWorkflow)) {
            val continueGoal = activeWorkflow.originalGoal.ifBlank { effectiveGoal }
            return TaskRoute(
                taskType = activeWorkflow.taskType,
                contextualIntent = ContextualTaskIntent(
                    classificationGoal = goal,
                    taskTypeOverride = activeWorkflow.taskType,
                    userVisibleSteps = continueVisibleSteps(activeWorkflow.taskType, continueGoal, goal, ""),
                    executionHint = buildString {
                        appendLine("The user is continuing an active task in this chat.")
                        appendLine("Active task type: ${activeWorkflow.taskType}.")
                        appendLine("Original task: ${activeWorkflow.originalGoal.take(900)}")
                        appendLine("Latest user message: $goal")
                        append("Continue the active task from the latest observed state. Do not revive older unrelated artifacts or tasks.")
                    },
                ),
                goalForExecution = effectiveGoal,
                source = TaskRouteSource.ACTIVE_WORKFLOW,
                goalToRemember = activeWorkflow.originalGoal,
                debugReason = "Continuing active workflow ${activeWorkflow.taskType}.",
            )
        }

        latestContinueOnlyRoute(goal, effectiveGoal)?.let { return it }

        val contextualIntent = resolveContextualTaskIntent(goal, hasImage, hasFile)
        val explicitArtifactTaskType = explicitArtifactTaskType(goal)
        val classifiedTaskType = TaskClassifier.classify(
            goal = contextualIntent.classificationGoal.ifBlank { effectiveGoal },
            hasImage = hasImage,
            hasFile = hasFile,
        )
        val taskType = inferFollowUpTaskType(goal, classifiedTaskType)
            ?: explicitArtifactTaskType
            ?: contextualIntent.taskTypeOverride
            ?: if (contextualIntent.aiPage != null && !hasImage && !hasFile) TaskType.APP_BUILD else classifiedTaskType
        return TaskRoute(
            taskType = taskType,
            contextualIntent = contextualIntent,
            goalForExecution = effectiveGoal,
            source = TaskRouteSource.CLASSIFIER,
            debugReason = buildString {
                append("Classifier route selected taskType=$taskType")
                explicitArtifactTaskType?.let { append("; explicitArtifactTaskType=$it") }
                contextualIntent.taskTypeOverride?.let { append("; contextualOverride=$it") }
                append("; classifiedTaskType=$classifiedTaskType")
            },
        )
    }

    private fun latestContinueOnlyRoute(goal: String, effectiveGoal: String): TaskRoute? {
        if (!isGenericContinueOnly(goal)) return null
        workspaceContinueRoute(goal, effectiveGoal)?.let { return it }
        val recent = effectiveContextMessages(limit = 6)
        val latest = recent.lastOrNull() ?: return null
        val latestAttachment = latest.attachments.asReversed()
            .firstOrNull { it is SkillAttachment.FileData || it is SkillAttachment.HtmlData }
            ?.takeIf { !isLikelyStickerOrMediaAsset(it) }
        if (latestAttachment != null) {
            val intent = recentFileContextIntent(goal, latestAttachment)
            return TaskRoute(
                taskType = intent.taskTypeOverride ?: TaskType.FILE_CREATE,
                contextualIntent = intent,
                goalForExecution = effectiveGoal,
                source = TaskRouteSource.RECENT_CONTEXT,
                goalToRemember = recentAnchorGoal(recent, effectiveGoal),
                debugReason = "Recent attachment context continuation.",
            )
        }

        val inferredTaskType = inferTaskTypeFromMessage(latest)
        if (inferredTaskType != null) {
            val anchorGoal = recentAnchorGoal(recent, effectiveGoal)
            return TaskRoute(
                taskType = inferredTaskType,
                contextualIntent = ContextualTaskIntent(
                    classificationGoal = goal,
                    taskTypeOverride = inferredTaskType,
                    userVisibleSteps = continueVisibleSteps(inferredTaskType, anchorGoal, goal, inferTargetAppFromMessages(recent)),
                    executionHint = "The user's short follow-up refers to the latest meaningful ${inferredTaskType.name} task in this chat. Continue that task using the newest relevant chat records, not an older unrelated thread.",
                ),
                goalForExecution = effectiveGoal,
                source = TaskRouteSource.RECENT_CONTEXT,
                goalToRemember = anchorGoal,
                debugReason = "Recent message inferred follow-up taskType=$inferredTaskType.",
            )
        }

        val genericAnchorGoal = recentAnchorGoal(recent, effectiveGoal)
        return TaskRoute(
            taskType = TaskType.GENERAL,
            contextualIntent = ContextualTaskIntent(
                classificationGoal = goal,
                taskTypeOverride = TaskType.GENERAL,
                userVisibleSteps = continueVisibleSteps(TaskType.GENERAL, genericAnchorGoal, goal, inferTargetAppFromMessages(recent)),
                executionHint = "The user's short follow-up refers to the latest conversational thread. Answer based on the newest relevant user intent; do not start an unrelated artifact or tool workflow.",
            ),
            goalForExecution = effectiveGoal,
            source = TaskRouteSource.RECENT_CONTEXT,
            debugReason = "Generic continue-only follow-up defaulted to latest conversational thread.",
        )
    }

    private fun workspaceContinueRoute(goal: String, effectiveGoal: String): TaskRoute? {
        val workspace = workspaceContextProvider() ?: return null
        val taskType = workspaceTaskType(workspace) ?: return null
        val intent = ContextualTaskIntent(
            classificationGoal = goal,
            taskTypeOverride = taskType,
            userVisibleSteps = continueVisibleSteps(taskType, workspace.goal.ifBlank { effectiveGoal }, goal, workspace.latestArtifactTitle),
            aiPage = workspaceAiPageTarget(workspace),
            miniApp = workspaceMiniAppTarget(workspace),
            executionHint = buildString {
                appendLine("The user is continuing the current workspace-backed task.")
                appendLine("Always resume from the current workspace state before reading older chat history.")
                appendLine("Workspace: ${workspace.title} (${workspace.workspaceId})")
                appendLine("Workspace goal: ${workspace.goal.take(1000)}")
                workspace.taskType.takeIf { it.isNotBlank() }?.let { appendLine("Workspace task type: $it") }
                workspace.checkpointLabel.takeIf { it.isNotBlank() }?.let { appendLine("Latest checkpoint: $it") }
                workspace.checkpointSummary.takeIf { it.isNotBlank() }?.let { appendLine("Checkpoint summary: ${it.take(800)}") }
                if (workspace.latestArtifactType.isNotBlank() || workspace.latestArtifactId.isNotBlank()) {
                    appendLine(
                        "Current artifact: ${workspace.latestArtifactType.ifBlank { "unknown" }} " +
                            "${workspace.latestArtifactId.ifBlank { "" }} ${workspace.latestArtifactTitle.ifBlank { "" }}".trim()
                    )
                }
                workspace.latestArtifactAction.takeIf { it.isNotBlank() }?.let { appendLine("Latest artifact action: $it") }
                workspace.latestEventSummary.takeIf { it.isNotBlank() }?.let { appendLine("Latest event: ${it.take(800)}") }
                append("Continue the current task. Do not switch to an older unrelated thread.")
            }.trim(),
        )
        return TaskRoute(
            taskType = taskType,
            contextualIntent = intent,
            goalForExecution = effectiveGoal,
            source = TaskRouteSource.RECENT_CONTEXT,
            goalToRemember = workspace.goal.ifBlank { effectiveGoal },
        )
    }

    fun applyContextualTaskConstraints(
        effectiveGoal: String,
        intent: ContextualTaskIntent,
        taskType: TaskType,
    ): String {
        if (taskType != TaskType.APP_BUILD) return effectiveGoal
        intent.aiPage?.let { page ->
            val spec = page.safeSpec()
            return buildString {
                append(effectiveGoal.trim())
                append("\n\n[artifact_update_contract]")
                append("\nartifact_type=ai_native_page")
                append("\ntarget_id=${page.id}")
                append("\ntarget_title=${page.title}")
                append("\nmode=patch_existing")
                append("\nDo not create a new page unless the user explicitly asks.")
                append("\nUse tool flow: ui_builder(get) -> ui_builder(analyze_change) -> ui_builder(update) -> ui_builder(validate) -> ui_builder(open if user-facing).")
                append("\nOriginal goal: ${spec.goal.ifBlank { page.description.ifBlank { page.title } }}")
                append("\nRequired features: ${renderSpecList(spec.requiredFeatures, fallback = "preserve all existing visible features unless explicitly removed")}")
                append("\nConstraints: ${renderSpecList(spec.constraints, fallback = "keep current artifact style and platform behavior consistent")}")
                append("\nAccepted corrections: ${renderSpecList(spec.acceptedCorrections, fallback = "none yet")}")
                append("\nKnown bugs: ${renderSpecList(spec.knownBugs, fallback = "none recorded")}")
                append("\nNon-goals: ${renderSpecList(spec.nonGoals, fallback = "none recorded")}")
                append("\nRecent artifact history: ${renderHistory(page.history)}")
                append("\nLatest change request: ${effectiveGoal.take(1200)}")
            }
        }
        intent.miniApp?.let { app ->
            val spec = app.safeSpec()
            return buildString {
                append(effectiveGoal.trim())
                append("\n\n[artifact_update_contract]")
                append("\nartifact_type=miniapp")
                append("\ntarget_id=${app.id}")
                append("\ntarget_title=${app.title}")
                append("\nmode=patch_existing")
                append("\nDo not create a new app unless the user explicitly asks.")
                append("\nUse tool flow: app_manager(analyze_change) -> app_manager(update) -> app_manager(validate) -> app_manager(open if user-facing).")
                append("\nOriginal goal: ${spec.goal.ifBlank { app.description.ifBlank { app.title } }}")
                append("\nRequired features: ${renderSpecList(spec.requiredFeatures, fallback = "preserve all existing user-visible features unless explicitly removed")}")
                append("\nConstraints: ${renderSpecList(spec.constraints, fallback = "keep current artifact style and runtime behavior consistent")}")
                append("\nAccepted corrections: ${renderSpecList(spec.acceptedCorrections, fallback = "none yet")}")
                append("\nKnown bugs: ${renderSpecList(spec.knownBugs, fallback = "none recorded")}")
                append("\nNon-goals: ${renderSpecList(spec.nonGoals, fallback = "none recorded")}")
                append("\nRecent artifact history: ${renderHistory(app.history)}")
                append("\nLatest change request: ${effectiveGoal.take(1200)}")
            }
        }
        return effectiveGoal
    }

    fun buildArtifactContext(intent: ContextualTaskIntent): String {
        val sections = mutableListOf<String>()
        intent.executionHint.takeIf { it.isNotBlank() }?.let {
            sections += "## Current Task Focus\n$it"
        }
        intent.aiPage?.let { page ->
            buildAiPageArtifactContext(page).takeIf { it.isNotBlank() }?.let { sections += it }
        }
        intent.miniApp?.let { mini ->
            buildMiniAppArtifactContext(mini).takeIf { it.isNotBlank() }?.let { sections += it }
        }
        intent.fileAttachment?.let {
            sections += "## Current File Artifact\nActive file target: path=${it.path}, name=${it.name}, mime=${it.mimeType}, size=${it.sizeBytes}. For follow-up edits, read/update this file instead of creating an unrelated artifact."
        }
        intent.htmlAttachment?.let {
            sections += "## Current HTML Artifact\nActive HTML target: path=${it.path}, title=${it.title}. For follow-up edits, continue from this result instead of creating an unrelated artifact."
        }
        return sections.joinToString("\n\n")
    }

    fun effectiveContextMessages(limit: Int): List<ChatMessage> =
        messagesProvider()
            .asReversed()
            .filterNot { it.isContextNoiseMessage() }
            .take(limit)
            .asReversed()

    private fun recentAnchorGoal(messages: List<ChatMessage>, fallback: String): String =
        messages
            .asReversed()
            .firstOrNull { msg ->
                msg.role == MessageRole.USER &&
                    msg.text.isNotBlank() &&
                    !isGenericContinueOnly(msg.text) &&
                    !isMobileClawInternalChatTopic(msg.text.lowercase())
            }
            ?.text
            ?.trim()
            ?.take(1200)
            ?: fallback

    fun summarizeAttachmentsForContext(attachments: List<SkillAttachment>): String {
        if (attachments.isEmpty()) return ""
        return attachments.joinToString("; ") { attachment ->
            when (attachment) {
                is SkillAttachment.ImageData -> buildString {
                    append("image(prompt=${attachment.prompt.orEmpty().take(60)}")
                    if (attachment.localPath.isNotBlank()) append(", local_path=${attachment.localPath}")
                    append(")")
                }
                is SkillAttachment.FileData -> "file(name=${attachment.name}, path=${attachment.path}, mime=${attachment.mimeType}, size=${attachment.sizeBytes})"
                is SkillAttachment.HtmlData -> "html(title=${attachment.title}, path=${attachment.path})"
                is SkillAttachment.WebPage -> "webpage(title=${attachment.title}, url=${attachment.url})"
                is SkillAttachment.SearchResults -> "search_results(query=${attachment.query}, count=${attachment.pages.size})"
                is SkillAttachment.AccessibilityRequest -> "accessibility_request(${attachment.skillName})"
                is SkillAttachment.ActionCard -> "action_card(title=${attachment.title}, actions=${attachment.actions.size})"
                is SkillAttachment.FileList -> "file_list(directory=${attachment.directory}, count=${attachment.files.size})"
            }
        }
    }

    private fun inferFollowUpTaskType(goal: String, classifiedTaskType: TaskType): TaskType? {
        if (classifiedTaskType != TaskType.GENERAL) return null
        if (!isContextualFollowUp(goal)) return null
        val recent = effectiveContextMessages(limit = 6)
        if (currentRoleProvider().id == "phone_operator" && isPhoneContinuationContext(recent)) {
            return TaskType.PHONE_CONTROL
        }
        if (recent.any { msg ->
                msg.senderRoleId == "phone_operator" ||
                    msg.logLines.any { it.skillId in PHONE_CONTROL_SKILLS || it.text.contains("VLM_PHONE_CONTROL") } ||
                    messageContextText(msg).lowercase().containsAnyTerm(
                        "vlm_phone_control", "foreground app", "phone control",
                    )
            }) {
            return TaskType.PHONE_CONTROL
        }
        return null
    }

    private fun isPhoneContinuationContext(messages: List<ChatMessage>): Boolean =
        messages.any { msg ->
            msg.senderRoleId == "phone_operator" ||
                msg.logLines.any { it.skillId in PHONE_CONTROL_SKILLS || it.text.contains("VLM_PHONE_CONTROL") } ||
                messageContextText(msg).lowercase().containsAnyTerm(
                    "vlm_phone_control", "foreground app", "phone control",
                )
        }

    private fun inferTaskTypeFromMessage(msg: ChatMessage): TaskType? {
        val skillIds = msg.logLines.mapNotNull { it.skillId }.toSet()
        val text = messageContextText(msg).lowercase()
        return when {
            msg.senderRoleId == "phone_operator" ||
                skillIds.any { it in PHONE_CONTROL_SKILLS } ||
                text.containsAnyTerm("vlm_phone_control", "phone control", "foreground app") -> TaskType.PHONE_CONTROL
            skillIds.any { it in APP_BUILD_SKILLS } ||
                text.containsAnyTerm("ui_builder", "app_manager", "ai native page", "miniapp") -> TaskType.APP_BUILD
            skillIds.any { it in FILE_SKILLS } ||
                text.containsAnyTerm("generate_document", "create_file", "read_file", "file_list", "file", "document") -> TaskType.FILE_CREATE
            skillIds.any { it in WEB_SKILLS } ||
                text.containsAnyTerm("web_search", "fetch_url", "web_browse", "search_results") -> TaskType.WEB_RESEARCH
            skillIds.any { it in IMAGE_SKILLS } ||
                text.containsAnyTerm("generate_image", "generate_icon", "image generated") -> TaskType.IMAGE_GENERATION
            skillIds.any { it == "vpn_control" } ||
                text.containsAnyTerm("vpn", "mihomo") -> TaskType.VPN_CONTROL
            skillIds.any { it in CODE_SKILLS } -> TaskType.CODE_EXECUTION
            else -> null
        }
    }

    private fun workspaceTaskType(workspace: WorkspaceExecutionContext): TaskType? {
        val direct = workspace.taskType
            .takeIf { it.isNotBlank() }
            ?.let { raw -> TaskType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
        if (direct != null) return direct
        return when (workspace.latestArtifactType.lowercase()) {
            "ai_native_page", "miniapp" -> TaskType.APP_BUILD
            "document", "pdf", "sheet", "slide", "text", "file", "html" -> TaskType.FILE_CREATE
            else -> when {
                workspace.latestEventCategory == "code_observation" -> TaskType.CODE_EXECUTION
                workspace.latestEventCategory == "file_observation" -> TaskType.FILE_CREATE
                workspace.latestEventCategory == "artifact_observation" -> TaskType.APP_BUILD
                else -> null
            }
        }
    }

    private fun workspaceAiPageTarget(workspace: WorkspaceExecutionContext): AiPageDef? {
        if (!workspace.latestArtifactType.equals("ai_native_page", ignoreCase = true)) return null
        val pages = aiPagesProvider()
        return pages.firstOrNull { it.id == workspace.latestArtifactId }
            ?: pages.firstOrNull { it.title == workspace.latestArtifactTitle }
    }

    private fun workspaceMiniAppTarget(workspace: WorkspaceExecutionContext): MiniApp? {
        if (!workspace.latestArtifactType.equals("miniapp", ignoreCase = true)) return null
        val apps = miniAppsProvider()
        return apps.firstOrNull { it.id == workspace.latestArtifactId }
            ?: apps.firstOrNull { it.title == workspace.latestArtifactTitle }
    }

    private fun shouldContinueActiveWorkflow(goal: String, workflow: ActiveWorkflow): Boolean {
        val text = goal.trim().lowercase()
        if (text.isBlank()) return false
        if (TaskClassifier.classify(goal) !in listOf(TaskType.CHAT, TaskType.GENERAL)) return false
        if (isMobileClawInternalChatTopic(text)) return false
        if (isGenericContinueOnly(text) || isContextualFollowUp(text)) return true
        return false
    }

    private fun resolveContextualTaskIntent(
        goal: String,
        hasImage: Boolean,
        hasFile: Boolean,
    ): ContextualTaskIntent {
        if (hasImage || hasFile) return ContextualTaskIntent(goal)
        val text = goal.lowercase()
        explicitArtifactIntent(goal)?.let { return it }
        val recentArtifact = inferRecentFileContextTarget(goal)
        if (recentArtifact != null && shouldUseRecentFileContext(text, recentArtifact)) {
            return recentFileContextIntent(goal, recentArtifact)
        }
        val miniApp = inferMiniAppContextTarget(goal)
        if (miniApp != null) {
            return ContextualTaskIntent(
                classificationGoal = goal,
                taskTypeOverride = TaskType.APP_BUILD,
                miniApp = miniApp,
                executionHint = "The user is referring to an existing MiniAPP. Update/open that MiniAPP instead of creating a new unrelated artifact.",
                aiPrimaryChannel = ChannelType.ARTIFACT,
                aiSupportingChannels = listOf(ChannelType.SKILL, ChannelType.MEMORY),
                aiToolHints = listOf("app_manager", "read_file", "create_file", "list_files", "create_html"),
            )
        }

        val aiPage = inferAiPageContextTarget(goal)
        if (aiPage != null) {
            return ContextualTaskIntent(
                classificationGoal = goal,
                taskTypeOverride = TaskType.APP_BUILD,
                aiPage = aiPage,
                executionHint = "The user is referring to an existing AI Native Page. Update that page instead of creating HTML or a new unrelated artifact.",
                aiPrimaryChannel = ChannelType.ARTIFACT,
                aiSupportingChannels = listOf(ChannelType.SKILL, ChannelType.MEMORY),
                aiToolHints = listOf("ui_builder", "read_file", "create_file", "list_files"),
            )
        }

        if (recentArtifact != null) return recentFileContextIntent(goal, recentArtifact)
        return ContextualTaskIntent(goal)
    }

    private fun explicitArtifactIntent(goal: String): ContextualTaskIntent? {
        val text = goal.lowercase()
        return when {
            isExplicitMiniAppIntent(text) -> ContextualTaskIntent(
                classificationGoal = goal,
                taskTypeOverride = TaskType.APP_BUILD,
                executionHint = "The user explicitly wants a MiniAPP/program/game/HTML runtime. Route this request through app_manager, not ui_builder, and create or update a persistent MiniAPP artifact.",
                aiPrimaryChannel = ChannelType.ARTIFACT,
                aiSupportingChannels = listOf(ChannelType.SKILL, ChannelType.MEMORY),
                aiToolHints = listOf("app_manager", "read_file", "create_file", "list_files"),
                userVisibleSteps = listOf(
                    "Clarify what this MiniAPP should do",
                    "Build or fix the MiniAPP screens and logic",
                    "Run a check, then open the result",
                ),
            )
            isExplicitNativePageIntent(text) -> ContextualTaskIntent(
                classificationGoal = goal,
                taskTypeOverride = TaskType.APP_BUILD,
                executionHint = "The user explicitly wants an AI Native Page / original page. Route this request through ui_builder, not app_manager.",
                aiPrimaryChannel = ChannelType.ARTIFACT,
                aiSupportingChannels = listOf(ChannelType.SKILL, ChannelType.MEMORY),
                aiToolHints = listOf("ui_builder", "read_file", "create_file", "list_files"),
                userVisibleSteps = listOf(
                    "Clarify what this native page should show",
                    "Create or update the native page",
                    "Check the result, then open it",
                ),
            )
            else -> null
        }
    }

    private fun normalizeUserVisibleSteps(
        rawSteps: List<String>,
        taskType: TaskType?,
        normalizedGoal: String,
        targetApp: String,
    ): List<String> {
        val cleaned = rawSteps
            .map { it.trim().trimStart { char -> char.isDigit() || char.isWhitespace() || char in setOf('-', '•', '*', '.') } }
            .filter { it.isNotBlank() }
            .map { rewriteGenericStep(it, taskType, normalizedGoal, targetApp) }
            .distinct()
            .take(4)
        return if (cleaned.isNotEmpty()) cleaned else defaultVisibleSteps(taskType, normalizedGoal, targetApp)
    }

    private fun rewriteGenericStep(
        step: String,
        taskType: TaskType?,
        normalizedGoal: String,
        targetApp: String,
    ): String {
        val normalized = step.lowercase()
        val app = targetApp.takeIf { it.isNotBlank() } ?: extractQuotedTopic(normalizedGoal)
        val topic = extractQuotedTopic(normalizedGoal)
        return when {
            normalized.containsAnyTerm("confirm target", "confirm page", "clarify") ->
                defaultVisibleSteps(taskType, normalizedGoal, targetApp).first()
            normalized.containsAnyTerm("validate", "check result", "open result") ->
                defaultVisibleSteps(taskType, normalizedGoal, targetApp).last()
            normalized.containsAnyTerm("continue progress", "improve flow", "continue handling") ->
                defaultVisibleSteps(taskType, normalizedGoal, targetApp).getOrElse(1) { step }
            taskType == TaskType.PHONE_CONTROL && app != null && normalized.containsAnyTerm("open app", "target app") ->
                "Open $app and get to the screen that needs action"
            taskType == TaskType.WEB_RESEARCH && topic != null && normalized.containsAnyTerm("find", "look up") ->
                "Look up the most useful information about ${quoteTopic(topic)}"
            taskType == TaskType.CODE_EXECUTION && topic != null && normalized.containsAnyTerm("fix", "repair") ->
                "Find the key code issue in ${quoteTopic(topic)}"
            else -> step
        }
    }

    private fun defaultVisibleSteps(taskType: TaskType?, normalizedGoal: String, targetApp: String): List<String> {
        val topic = extractQuotedTopic(normalizedGoal)
        return when (taskType) {
            TaskType.PHONE_CONTROL -> listOf(
                if (targetApp.isNotBlank()) "Open $targetApp and find the place to act" else "Read the current screen and find the entry point",
                if (topic != null) "Handle the operation related to ${quoteTopic(topic)}" else "Continue the phone operation directly",
                "Check whether the screen changed as expected",
            )
            TaskType.WEB_RESEARCH -> listOf(
                if (topic != null) "Find the most useful information about ${quoteTopic(topic)}" else "Find the most relevant web information",
                "Filter out noise and keep the useful parts",
                "Summarize the result into something directly usable",
            )
            TaskType.APP_BUILD -> listOf(
                if (topic != null) "Clarify what ${quoteTopic(topic)} should become" else "Clarify what this page or app should look like",
                "Build the core screen, logic, or interaction",
                "Run a check, then open the working result",
            )
            TaskType.FILE_CREATE -> listOf(
                if (topic != null) "Organize the content needed for ${quoteTopic(topic)}" else "Organize what this file should contain",
                "Generate the file content",
                "Confirm it can be opened or edited further",
            )
            TaskType.CODE_EXECUTION -> listOf(
                if (topic != null) "Locate the key issue in ${quoteTopic(topic)}" else "Locate the most important code issue",
                "Patch the code and fill in the missing logic",
                "Run a check to confirm the change works",
            )
            else -> listOf(
                if (topic != null) "Handle the core part of ${quoteTopic(topic)}" else "Handle the core part of this request",
                if (topic != null) "Fill in anything still missing for ${quoteTopic(topic)}" else "Fill in anything still missing",
                "Return a result that is easy to understand and use",
            )
        }
    }

    private fun extractQuotedTopic(text: String): String? {
        val clean = text.trim().replace('\n', ' ')
        val quoted = Regex("[\\p{Pi}\\p{Pf}\"']([^\\p{Pi}\\p{Pf}\"']{2,32})[\\p{Pi}\\p{Pf}\"']")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted
        val compact = clean.split(Regex("[\\p{P}&&[^'_-]]+"))
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.length in 4..28 &&
                    !candidate.lowercase().containsAnyTerm("please", "help me", "i want")
            }
        return compact?.takeIf { it.isNotBlank() }
    }

    private fun quoteTopic(topic: String): String = "\"$topic\""

    private fun explicitArtifactTaskType(goal: String): TaskType? {
        val text = goal.lowercase()
        return when {
            isExplicitMiniAppIntent(text) || isExplicitNativePageIntent(text) -> TaskType.APP_BUILD
            else -> null
        }
    }

    private fun isExplicitMiniAppIntent(text: String): Boolean =
        text.containsAnyTerm(
            "miniapp", "mini app", "program", "application", "app", "game", "webview",
            "html", "javascript", "js", "canvas", "sqlite", "python backend", "web app",
        ) && text.containsAnyTerm(
            "build", "create", "make", "develop", "update", "modify", "edit", "optimize", "fix",
        )

    private fun isExplicitNativePageIntent(text: String): Boolean =
        !isExplicitMiniAppIntent(text) && text.containsAnyTerm(
            "ai native page", "native page", "page", "dashboard", "form", "management page",
            "settings page", "panel", "screen", "ui",
        ) && text.containsAnyTerm(
            "build", "create", "make", "develop", "update", "modify", "edit", "optimize", "fix",
        )

    private fun recentFileContextIntent(goal: String, recentArtifact: SkillAttachment): ContextualTaskIntent {
        val taskType = when {
            recentArtifact is SkillAttachment.HtmlData && goal.lowercase().containsAnyTerm("page", "html", "web", "preview", "style", "layout", "ui") -> TaskType.APP_BUILD
            else -> TaskType.FILE_CREATE
        }
        return ContextualTaskIntent(
            classificationGoal = goal,
            taskTypeOverride = taskType,
            fileAttachment = recentArtifact as? SkillAttachment.FileData,
            htmlAttachment = recentArtifact as? SkillAttachment.HtmlData,
            userVisibleSteps = continueVisibleSteps(taskType, recentArtifactSummary(recentArtifact), goal, ""),
            executionHint = "The user is referring to the latest relevant file or HTML artifact in this chat. Continue from that artifact, unless the user explicitly asks for a new one.",
        )
    }

    private fun continueVisibleSteps(
        taskType: TaskType?,
        anchorGoal: String,
        latestGoal: String,
        targetApp: String,
    ): List<String> {
        val base = if (isGenericContinueOnly(latestGoal) || isContextualFollowUp(latestGoal)) anchorGoal else latestGoal
        val topic = extractQuotedTopic(base).orEmpty()
        return when (taskType) {
            TaskType.PHONE_CONTROL -> listOf(
                    if (targetApp.isNotBlank()) "Return to the current $targetApp operation" else "Continue the current phone operation",
                    if (topic.isNotBlank()) "Handle this step for ${quoteTopic(topic)}" else "Finish the step currently on screen",
                    "Check the screen change, then decide the next action",
                )
            TaskType.APP_BUILD -> listOf(
                    if (topic.isNotBlank()) "Continue fixing the key part of ${quoteTopic(topic)}" else "Continue with the key part of this page or app",
                    "Fix what is still wrong in this pass",
                    "Run another check and show the result",
                )
            TaskType.FILE_CREATE -> listOf(
                    if (topic.isNotBlank()) "Continue filling in the content for ${quoteTopic(topic)}" else "Continue filling in the missing file content",
                    "Apply this change to the file",
                    "Confirm the result can be opened or edited further",
                )
            TaskType.WEB_RESEARCH -> listOf(
                    if (topic.isNotBlank()) "Find the remaining information for ${quoteTopic(topic)}" else "Find the missing key information",
                    "Filter out noisy information",
                    "Keep only the conclusion you can use directly",
                )
            TaskType.CODE_EXECUTION -> listOf(
                    if (topic.isNotBlank()) "Continue fixing the key issue in ${quoteTopic(topic)}" else "Continue fixing the most important code issue",
                    "Fill in missing logic or resolve the error",
                    "Run a check to confirm the fix works",
                )
            else -> listOf(
                    if (topic.isNotBlank()) "Continue handling ${quoteTopic(topic)}" else "Continue the previous task",
                    "Fill in what is wrong or unfinished",
                    "Return a more complete result",
                )
        }
    }

    private fun recentArtifactSummary(attachment: SkillAttachment): String =
        when (attachment) {
            is SkillAttachment.FileData -> attachment.name.ifBlank { attachment.path.substringAfterLast('/') }
            is SkillAttachment.HtmlData -> attachment.title.ifBlank { attachment.path.substringAfterLast('/') }
            else -> ""
        }

    private fun inferTargetAppFromMessages(messages: List<ChatMessage>): String =
        messages
            .asReversed()
            .firstNotNullOfOrNull { msg ->
                Regex("Target app: ([^\\n]+)").find(messageContextText(msg))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()

    private fun shouldUseRecentFileContext(text: String, attachment: SkillAttachment): Boolean {
        if (isLikelyStickerOrMediaAsset(attachment)) return false
        val followUpSignals = text.containsAnyTerm(
            "this", "this file", "this document", "this spreadsheet", "this ppt", "this pdf",
            "this html", "this page", "it", "above", "previous", "continue", "keep going",
            "modify", "edit", "change", "adjust", "optimize", "improve", "update", "open",
            "save", "export", "preview", "save as", "revise",
        )
        if (!followUpSignals) return false
        val newCreationIntent = text.containsAnyTerm(
            "create", "make", "generate", "build", "design", "write",
        ) && text.containsAnyTerm(
            "ppt", "pptx", "doc", "docx", "word", "excel", "xlsx", "pdf", "csv", "markdown", "html",
            "file", "document", "spreadsheet", "page",
        )
        return !newCreationIntent
    }

    private fun isLikelyStickerOrMediaAsset(attachment: SkillAttachment): Boolean = when (attachment) {
        is SkillAttachment.FileData -> {
            val path = attachment.path.lowercase()
            val name = attachment.name.lowercase()
            val mime = attachment.mimeType.lowercase()
            mime.startsWith("image/") ||
                path.contains("/stickers/") ||
                path.contains("bqb") ||
                name.contains("sticker") ||
                name.contains("bqb") ||
                name.contains("emoji")
        }
        is SkillAttachment.HtmlData -> false
        else -> false
    }

    private fun buildAiPageArtifactContext(activeAiPage: AiPageDef? = null): String {
        val pages = aiPagesProvider().take(5)
        if (pages.isEmpty() && activeAiPage == null) return ""
        val active = activeAiPage ?: pages.firstOrNull()
        val lines = pages.joinToString("\n") { page ->
            val marker = if (page.id == active?.id) "active" else "recent"
            val spec = page.safeSpec()
            "- [$marker] id=${page.id}, title=${page.title}, version=${page.version}, description=${page.description.take(80)}, goal=${spec.goal.take(80)}, features=${renderSpecList(spec.requiredFeatures, fallback = "unspecified")}"
        }
        val activeLine = active?.let {
            val spec = it.safeSpec()
            """
            
            Current AI Native Page target: id=${it.id}, title=${it.title}, version=${it.version}.
            Goal: ${spec.goal.ifBlank { it.description.ifBlank { it.title } }}
            Must preserve: ${renderSpecList(spec.requiredFeatures, fallback = "all current visible features unless explicitly removed")}
            Current features: ${renderSpecList(spec.currentFeatures, fallback = "not summarized yet")}
            Accepted corrections: ${renderSpecList(spec.acceptedCorrections, fallback = "none yet")}
            Known bugs: ${renderSpecList(spec.knownBugs, fallback = "none recorded")}
            Last diff: ${spec.lastDiffSummary.ifBlank { "none" }}
            Recent history: ${renderHistory(it.history)}
            For follow-up edits like "change it / optimize it / continue / adjust it", patch this page instead of creating HTML or a new page.
            Required tool flow: ui_builder(action=get) -> ui_builder(action=analyze_change) -> ui_builder(action=update) -> ui_builder(action=validate) -> ui_builder(action=open if needed).
            """.trimIndent()
        }.orEmpty()
        return "## Current AI Native Page Artifacts\n$lines$activeLine"
    }

    private fun buildMiniAppArtifactContext(activeMiniApp: MiniApp? = null): String {
        val apps = miniAppsProvider().take(5)
        if (apps.isEmpty() && activeMiniApp == null) return ""
        val active = activeMiniApp ?: apps.firstOrNull()
        val lines = apps.joinToString("\n") { mini ->
            val marker = if (mini.id == active?.id) "active" else "recent"
            val spec = mini.safeSpec()
            "- [$marker] id=${mini.id}, title=${mini.title}, description=${mini.description.take(80)}, goal=${spec.goal.take(80)}, features=${renderSpecList(spec.requiredFeatures, fallback = "unspecified")}, updatedAt=${mini.updatedAt}"
        }
        val activeLine = active?.let {
            val spec = it.safeSpec()
            """
            
            Current MiniAPP target: id=${it.id}, title=${it.title}.
            Goal: ${spec.goal.ifBlank { it.description.ifBlank { it.title } }}
            Must preserve: ${renderSpecList(spec.requiredFeatures, fallback = "all current visible features unless explicitly removed")}
            Current features: ${renderSpecList(spec.currentFeatures, fallback = "not summarized yet")}
            Accepted corrections: ${renderSpecList(spec.acceptedCorrections, fallback = "none yet")}
            Known bugs: ${renderSpecList(spec.knownBugs, fallback = "none recorded")}
            Last diff: ${spec.lastDiffSummary.ifBlank { "none" }}
            Recent history: ${renderHistory(it.history)}
            For follow-up edits like "change it / optimize it / continue / adjust it", patch this MiniAPP instead of creating a new app.
            Required tool flow: app_manager(action=analyze_change) -> app_manager(action=update) -> app_manager(action=validate) -> app_manager(action=open if needed).
            """.trimIndent()
        }.orEmpty()
        return "## Current MiniAPP Artifacts\n$lines$activeLine"
    }

    private fun renderSpecList(items: List<String>, fallback: String): String =
        items
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(8)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" | ")
            ?: fallback

    private fun renderHistory(history: List<ArtifactHistoryEntry>): String =
        history
            .takeLast(4)
            .map { entry ->
                listOf(entry.action, entry.request.ifBlank { entry.summary }.take(80))
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" -> ")
            ?: "no prior history"

    private fun ChatMessage.isContextNoiseMessage(): Boolean {
        if (attachments.any { it is SkillAttachment.ActionCard || it is SkillAttachment.AccessibilityRequest }) return true
        val normalized = text.trim()
        if (normalized.isBlank() && attachments.isEmpty() && logLines.isEmpty() && imageBase64.isNullOrBlank()) return true
        if (role == MessageRole.AGENT && normalized in setOf("Canceled.", "Switched to phone_operator.")) return true
        if (role == MessageRole.AGENT && normalized.startsWith("Accessibility settings opened.")) return true
        if (ConfirmationActionProtocol.isProtocolValue(normalized)) return true
        return false
    }

    private fun messageContextText(msg: ChatMessage): String {
        val logSummary = msg.logLines
            .takeLast(4)
            .joinToString("\n") { line ->
                listOfNotNull(line.skillId, line.text.take(180)).joinToString(": ")
            }
        val attachmentSummary = summarizeAttachmentsForContext(msg.attachments)
        return listOf(msg.text, logSummary, attachmentSummary)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun inferAiPageContextTarget(goal: String): AiPageDef? {
        val pages = aiPagesProvider()
        if (pages.isEmpty()) return null
        val text = goal.lowercase()
        if (isMobileClawInternalChatTopic(text)) return null
        val explicit = pages.firstOrNull { page ->
            text.contains(page.id.lowercase()) ||
                page.title.isNotBlank() && text.contains(page.title.lowercase())
        }
        if (explicit != null) return explicit
        val refersToPrevious = isContextualFollowUp(text)
        val pageIntent = text.containsAnyTerm(
            "this page", "native page", "ai native page", "aipage", "ai page", "page", "ui",
        )
        val recentPageContext = currentConversationMentionsAiPage(pages)
        val shortFollowUp = text.length <= 30 && refersToPrevious
        return if (recentPageContext != null && (refersToPrevious && pageIntent || shortFollowUp)) recentPageContext else null
    }

    private fun inferMiniAppContextTarget(goal: String): MiniApp? {
        val apps = miniAppsProvider()
        if (apps.isEmpty()) return null
        val text = goal.lowercase()
        if (isMobileClawInternalChatTopic(text)) return null
        val explicit = apps.firstOrNull { mini ->
            (mini.id.isNotBlank() && text.contains(mini.id.lowercase())) ||
                (mini.title.isNotBlank() && text.contains(mini.title.lowercase()))
        }
        if (explicit != null) return explicit
        val refersToPrevious = isContextualFollowUp(text)
        val appIntent = text.containsAnyTerm(
            "app", "miniapp", "mini app", "application", "program", "game", "web app", "app_manager",
        )
        val recentMiniAppContext = currentConversationMentionsMiniApp(apps)
        val shortFollowUp = text.length <= 30 && refersToPrevious
        return if (recentMiniAppContext != null && ((refersToPrevious && appIntent) || shortFollowUp)) recentMiniAppContext else null
    }

    private fun inferRecentFileContextTarget(goal: String): SkillAttachment? {
        val text = goal.lowercase()
        if (isMobileClawInternalChatTopic(text)) return null
        if (!isContextualFollowUp(text)) return null
        if (isGenericContinueOnly(text) && recentEffectiveUserMessageBeforeCurrent()?.let { TaskClassifier.classify(it) } == TaskType.PHONE_CONTROL) {
            return null
        }
        if (text.containsAnyTerm(
                "ppt", "pptx", "doc", "docx", "word", "excel", "xlsx", "pdf", "csv", "markdown", "html",
            ) && text.containsAnyTerm("create", "make", "generate", "build", "design", "write")) {
            return null
        }
        val fileIntent = text.containsAnyTerm(
            "file", "document", "attachment", "this", "it", "above", "previous", "continue", "keep going",
            "modify", "edit", "change", "optimize", "update", "open", "save", "export", "preview",
            "ppt", "pptx", "doc", "docx", "word", "excel", "xlsx", "pdf", "csv", "markdown", "html",
        )
        if (!fileIntent) return null
        return effectiveContextMessages(limit = 8)
            .asReversed()
            .flatMap { it.attachments.asReversed() }
            .firstOrNull { it is SkillAttachment.FileData || it is SkillAttachment.HtmlData }
            ?.takeIf { !isLikelyStickerOrMediaAsset(it) }
    }

    private fun currentConversationMentionsAiPage(pages: List<AiPageDef>): AiPageDef? {
        val recent = effectiveContextMessages(limit = 4)
        if (recent.isEmpty()) return null
        val text = recent.joinToString("\n") { messageContextText(it).lowercase() }
        val explicit = pages.firstOrNull { page ->
            (page.id.isNotBlank() && text.contains(page.id.lowercase())) ||
                (page.title.isNotBlank() && text.contains(page.title.lowercase()))
        }
        if (explicit != null) return explicit
        if (text.containsAnyTerm("ai native page", "native page", "ai page", "aipage", "ui_builder") &&
            !text.containsAnyTerm("miniapp", "mini app", "app_manager", "app")
        ) {
            return pages.firstOrNull()
        }
        return null
    }

    private fun currentConversationMentionsMiniApp(apps: List<MiniApp>): MiniApp? {
        val recent = effectiveContextMessages(limit = 4)
        if (recent.isEmpty()) return null
        val text = recent.joinToString("\n") { msg ->
            messageContextText(msg).lowercase() + "\n" + summarizeAttachmentsForContext(msg.attachments).lowercase()
        }
        val explicit = apps.firstOrNull { mini ->
            (mini.id.isNotBlank() && text.contains(mini.id.lowercase())) ||
                (mini.title.isNotBlank() && text.contains(mini.title.lowercase()))
        }
        if (explicit != null) return explicit
        if (text.containsAnyTerm("miniapp", "mini app", "app_manager", "application", "program", "game", "web app", "app")) {
            return apps.firstOrNull()
        }
        return null
    }

    private fun isContextualFollowUp(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.containsAnyTerm(
            "it", "this", "that", "this page", "this app", "this file", "this document",
            "previous", "previous version", "above", "continue", "keep going", "change it", "update it",
            "modify it", "adjust it", "optimize it", "improve it", "fix it", "redo it", "try again",
            "not this", "that's wrong", "use this", "use that", "based on this", "okay", "ok",
        )
    }

    private fun isGenericContinueOnly(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized in setOf("continue", "go on", "keep going", "resume", "okay", "ok")
    }

    private fun recentEffectiveUserMessageBeforeCurrent(): String? =
        effectiveContextMessages(limit = 8)
            .asReversed()
            .firstOrNull { it.role == MessageRole.USER }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun isMobileClawInternalChatTopic(text: String): Boolean =
        text.containsAnyTerm(
            "chat", "conversation", "context", "role", "memory", "vlm", "tool", "model",
            "local model", "cloud model", "execution", "routing", "bug", "crash",
        )

    private fun MiniApp.safeSpec(): ArtifactSpec {
        val raw: ArtifactSpec? = runCatching { spec }.getOrNull()
        return raw ?: ArtifactSpec()
    }

    private fun AiPageDef.safeSpec(): ArtifactSpec {
        val raw: ArtifactSpec? = runCatching { spec }.getOrNull()
        return raw ?: ArtifactSpec()
    }

    private fun String.containsAnyTerm(vararg terms: String): Boolean {
        val tokens = Regex("[\\p{L}\\p{N}_]+").findAll(lowercase()).map { it.value }.toList()
        return terms.any { term ->
            val termTokens = Regex("[\\p{L}\\p{N}_]+").findAll(term.lowercase()).map { it.value }.toList()
            termTokens.isNotEmpty() && tokens.windowed(termTokens.size).any { it == termTokens }
        }
    }

}
