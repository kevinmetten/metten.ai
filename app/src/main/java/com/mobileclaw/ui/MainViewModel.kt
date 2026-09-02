package com.mobileclaw.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileclaw.ClawApplication
import com.mobileclaw.app.MiniAppPreflightValidator
import com.mobileclaw.app.MiniAppStore
import com.mobileclaw.agent.AgentEvent
import com.mobileclaw.agent.AiIntentRouter
import com.mobileclaw.agent.AiToolSelector
import com.mobileclaw.agent.IntentContextPack
import com.mobileclaw.agent.AgentRuntime
import com.mobileclaw.agent.AgentCancellationReason
import com.mobileclaw.agent.AgentExecutionForegroundService
import com.mobileclaw.agent.AgentWorkspaceUpdate
import com.mobileclaw.agent.ChatBubbleStyle
import com.mobileclaw.agent.ChannelType
import com.mobileclaw.agent.ChannelPermissionPolicy
import com.mobileclaw.agent.Role
import com.mobileclaw.agent.RolePackageStore
import com.mobileclaw.agent.RoleWorkspaceStore
import com.mobileclaw.agent.RoleAvatarDefaults
import com.mobileclaw.agent.RoleScheduler
import com.mobileclaw.agent.RoleScheduleDecision
import com.mobileclaw.agent.normalizeRoleAvatar
import com.mobileclaw.agent.TaskOrchestrator
import com.mobileclaw.agent.TaskOrchestration
import com.mobileclaw.agent.ToolSelectionInput
import com.mobileclaw.agent.TaskClassifier
import com.mobileclaw.agent.TaskToolPolicy
import com.mobileclaw.agent.TaskType
import com.mobileclaw.permission.DeviceCapability
import com.mobileclaw.permission.ReadinessLevel
import com.mobileclaw.permission.runIfDeviceReady
import com.mobileclaw.config.ConfigEntry
import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.GatewayConfig
import com.mobileclaw.config.GatewayCapabilityConfig
import com.mobileclaw.config.capabilityApiKey
import com.mobileclaw.config.capabilityEndpoint
import com.mobileclaw.config.capabilityModel
import com.mobileclaw.config.hasCapability
import com.mobileclaw.config.responseLanguageShortInstruction
import com.mobileclaw.config.supportsCapabilityMultimodal
import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.LlmCallOptions
import com.mobileclaw.llm.Message
import com.mobileclaw.llm.OpenAiGateway
import com.mobileclaw.llm.RoleModelResolver
import com.mobileclaw.llm.ToolDefinition
import com.mobileclaw.llm.ToolParameters
import com.mobileclaw.llm.ToolProperty
import com.mobileclaw.llm.cleanLocalGeneratedText
import com.mobileclaw.llm.decodeLocalTokenizerSpacing
import com.mobileclaw.memory.EpisodicMemory
import com.mobileclaw.memory.MemoryContextBuilder
import com.mobileclaw.memory.MemoryWriter
import com.mobileclaw.memory.db.SessionEntity
import com.mobileclaw.memory.db.SessionMessageEntity
import com.mobileclaw.perception.ClawAccessibilityService
import com.mobileclaw.skill.SkillAttachment
import com.mobileclaw.skill.SkillLoader
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillType
import com.mobileclaw.skill.SkillToolCategory
import com.mobileclaw.skill.builtin.BgLaunchSkill
import com.mobileclaw.skill.builtin.BgReadScreenSkill
import com.mobileclaw.skill.builtin.BgScreenshotSkill
import com.mobileclaw.skill.builtin.BgStopSkill
import com.mobileclaw.skill.builtin.VirtualDisplaySetupSkill
import com.mobileclaw.skill.builtin.ClipboardSkill
import com.mobileclaw.skill.builtin.CloudinaryImageUploader
import com.mobileclaw.skill.builtin.CodexDesktopSkill
import com.mobileclaw.skill.builtin.CreateFileSkill
import com.mobileclaw.skill.builtin.CreateHtmlSkill
import com.mobileclaw.skill.builtin.DeviceInfoSkill
import com.mobileclaw.skill.builtin.ListFilesSkill
import com.mobileclaw.skill.builtin.ReadFileSkill
import com.mobileclaw.skill.builtin.ShowToastSkill
import com.mobileclaw.skill.builtin.FetchUrlSkill
import com.mobileclaw.skill.builtin.GenerateDocumentSkill
import com.mobileclaw.skill.builtin.GenerateIconSkill
import com.mobileclaw.skill.builtin.GenerateImageSkill
import com.mobileclaw.skill.builtin.GenerateVideoSkill
import com.mobileclaw.skill.builtin.InputTextSkill
import com.mobileclaw.skill.builtin.ListAppsSkill
import com.mobileclaw.skill.builtin.LongClickSkill
import com.mobileclaw.skill.builtin.MemorySkill
import com.mobileclaw.skill.builtin.MetaSkill
import com.mobileclaw.skill.builtin.McpClientSkill
import com.mobileclaw.skill.builtin.McpConnectSkill
import com.mobileclaw.skill.builtin.NavigateSkill
import com.mobileclaw.skill.builtin.PageControlSkill
import com.mobileclaw.skill.builtin.PermissionSkill
import com.mobileclaw.skill.builtin.PhoneStatusSkill
import com.mobileclaw.skill.builtin.PgyerReleaseSkill
import com.mobileclaw.skill.builtin.PgyerUpdateInfo
import com.mobileclaw.skill.builtin.RoleManagerSkill
import com.mobileclaw.skill.builtin.RoleWorkspaceSkill
import com.mobileclaw.skill.builtin.SessionManagerSkill
import com.mobileclaw.skill.builtin.VideoGenerationTaskManager
import com.mobileclaw.skill.builtin.VideoTaskStatuses
import com.mobileclaw.skill.builtin.WorkspaceManagerSkill
import com.mobileclaw.ui.chat.AiQuizQuestion
import com.mobileclaw.ui.chat.AgentSenderMeta
import com.mobileclaw.ui.chat.ChatMessage
import com.mobileclaw.ui.chat.ChatContextComposer
import com.mobileclaw.ui.chat.ConfirmationFlow
import com.mobileclaw.ui.chat.ConfirmationActionId
import com.mobileclaw.ui.chat.ConfirmationActionProtocol
import com.mobileclaw.ui.chat.ExplicitRoleSwitch
import com.mobileclaw.ui.chat.FileAttachment
import com.mobileclaw.ui.chat.buildNarrativeAgentMessages
import com.mobileclaw.ui.common.VISUAL_SKILL_IDS
import com.mobileclaw.ui.common.friendlyObservationDescription
import com.mobileclaw.ui.common.friendlySkillDescription
import com.mobileclaw.ui.common.friendlyThinkingUpdate
import com.mobileclaw.ui.common.buildSmartRecommendations
import com.mobileclaw.ui.common.nextStepHint
import com.mobileclaw.ui.common.plannedStageForAction
import com.mobileclaw.ui.common.stageAwareSkillDescription
import com.mobileclaw.ui.common.stringListOrEmpty
import com.mobileclaw.ui.common.stringOrNull
import com.mobileclaw.ui.common.userFacingActionResult
import com.mobileclaw.ui.common.userFacingActionNext
import com.mobileclaw.ui.common.userFacingInitialIntent
import com.mobileclaw.ui.common.userFacingPlanResult
import com.mobileclaw.ui.common.userFacingSkillStart
import com.mobileclaw.ui.common.userFacingThinkingResult
import com.mobileclaw.ui.chat.LogLine
import com.mobileclaw.ui.chat.LogType
import com.mobileclaw.ui.chat.MessageRole
import com.mobileclaw.ui.chat.ProgressDetailKey
import com.mobileclaw.ui.chat.ProgressDetailProtocol
import com.mobileclaw.ui.chat.SessionRunState
import com.mobileclaw.ui.chat.currentRunState
import com.mobileclaw.ui.chat.runtime.ChatExecutionMode
import com.mobileclaw.ui.chat.runtime.ChatRuntimeCoordinator
import com.mobileclaw.ui.chat.runtime.ChatRuntimePlan
import com.mobileclaw.ui.chat.runtime.ChatRuntimePlanInput
import com.mobileclaw.ui.chat.runtime.RoleChatControlPlan
import com.mobileclaw.ui.chat.runtime.RoleChatRuntimeBridge
import com.mobileclaw.ui.chat.runtime.RoleMemoryCommitInput
import com.mobileclaw.ui.chat.runtime.RoleMemoryCommitter
import com.mobileclaw.ui.chat.runtime.RoleRunInput
import com.mobileclaw.ui.chat.runtime.RoleRunStatus
import com.mobileclaw.ui.chat.runtime.RoleRuntimeProfile
import com.mobileclaw.ui.chat.runtime.RoleStep
import com.mobileclaw.ui.chat.runtime.RoleStepVisibility
import com.mobileclaw.ui.chat.runtime.RoleRuntimeController
import com.mobileclaw.ui.chat.runtime.RoleRuntimeFactory
import com.mobileclaw.ui.image.ImageGenerationRequest
import com.mobileclaw.ui.image.ImagePromptAiAction
import com.mobileclaw.ui.video.VideoGenerationRequest
import com.mobileclaw.ui.video.VideoPromptAiAction
import com.mobileclaw.ui.update.AppUpdatePresentation
import com.mobileclaw.ui.skills.SkillNoteGeneration
import com.mobileclaw.ui.workspace.WorkspaceAreaUi
import com.mobileclaw.ui.workspace.WorkspaceFileEntryUi
import com.mobileclaw.workspace.WorkspaceArtifactState
import com.mobileclaw.workspace.WorkspaceCheckpoint
import com.mobileclaw.workspace.WorkspaceEvent
import com.mobileclaw.skill.builtin.SessionRequest
import com.mobileclaw.skill.builtin.SkillNotesSkill
import com.mobileclaw.skill.builtin.QuickSkillSkill
import com.mobileclaw.skill.builtin.ReadScreenSkill
import com.mobileclaw.skill.builtin.ScreenshotSkill
import com.mobileclaw.skill.builtin.ScrollSkill
import com.mobileclaw.skill.builtin.SeeScreenSkill
import com.mobileclaw.skill.builtin.SkillCheckSkill
import com.mobileclaw.skill.builtin.SkillMarketSkill
import com.mobileclaw.skill.builtin.AppManagerSkill
import com.mobileclaw.skill.builtin.AiHomeAssetSkill
import com.mobileclaw.skill.builtin.SwitchModelSkill
import com.mobileclaw.skill.builtin.SwitchRoleSkill
import com.mobileclaw.skill.builtin.TapSkill
import com.mobileclaw.skill.builtin.TaskRecipeSkill
import com.mobileclaw.skill.builtin.TownBuilderSkill
import com.mobileclaw.skill.builtin.UserConfigSkill
import com.mobileclaw.skill.builtin.WebBrowseSkill
import com.mobileclaw.skill.builtin.WebContentSkill
import com.mobileclaw.skill.builtin.WebJsSkill
import com.mobileclaw.skill.builtin.WebSearchSkill
import com.mobileclaw.town.RoomArtifact
import com.mobileclaw.town.RoomTool
import com.mobileclaw.server.PrivilegedClient
import com.mobileclaw.skill.builtin.PipInstallSkill
import com.mobileclaw.vpn.VpnManager
import com.mobileclaw.skill.builtin.RunPythonSkill
import com.mobileclaw.skill.executor.ShellSkill
import com.mobileclaw.ui.profile.ProfileDimension
import com.mobileclaw.ui.profile.ProfileAiGeneration
import com.mobileclaw.ui.workspace.SemanticFactLike
import com.mobileclaw.ui.workspace.WorkspaceRuntimeCoordinator
import com.mobileclaw.ui.workspace.WorkspaceRuntimeRecorder
import com.mobileclaw.ui.workspace.WorkspacePresentationSemantics
import com.mobileclaw.vpn.AppHttpProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.UUID
import com.mobileclaw.R
import com.mobileclaw.str

private const val TAG = "MainViewModel"
private const val MINI_APP_AUTO_REPAIR_MAX_ATTEMPTS = 2
private const val LLM_RETRY_MAX_ATTEMPTS = 2
private const val ROLE_RUNTIME_DRY_RUN_TRACE_KEY = "role_runtime_dry_run_trace_enabled"
private const val ROLE_RUNTIME_DRY_RUN_MAX_STEPS = 2
private const val VIDEO_TASK_AUTO_REFRESH_INTERVAL_MS = 12_000L

// Queue targeted MiniAPP preview repairs at session scope without coupling UI state to the active runtime.
// This lets repair continue automatically instead of requiring another user command.
private data class PendingMiniAppAutoRepair(
    val sessionId: String,
    val appId: String,
    val previewStatus: String,
    val attempt: Int,
    val enqueuedAt: Long = System.currentTimeMillis(),
)

private fun codexDesktopExecutionGoal(userGoal: String): String = """
Use the codex_desktop tool to run this task on the user's desktop Codex CLI.

Required behavior:
- Call codex_desktop with action="run".
- Put the user's request in the prompt parameter.
- Do not use Android shell, Android files, phone UI control, or local Python for this task.
- If the bridge is not configured or unreachable, report that clearly and ask the user to configure Codex Bridge.

User request:
$userGoal
""".trimIndent()

class MainViewModel : ViewModel() {

    private val app = ClawApplication.instance
    private val config = app.agentConfig
    private val registry = app.skillRegistry
    private val loader = SkillLoader(app, registry)
    private val overlay = app.overlayManager
    private val auroraOverlay = app.auroraOverlayManager
    private val miniAppValidationOverlay = app.miniAppValidationOverlayManager
    private val episodicMemory = EpisodicMemory(app.database.episodeDao(), app.createLlmGateway())
    private val conversationMemory = app.conversationMemory
    private val profileExtractor = app.userProfileExtractor
    private val roleManager = app.roleManager
    private val rolePackageStore by lazy { RolePackageStore(app, roleManager, app.roleWorkspaceStore) }
    private val townStore = app.agentTownStore
    private val userConfig = app.userConfig
    private val memoryContextBuilder = MemoryContextBuilder(app.semanticMemory, userConfig)
    private val memoryWriter = MemoryWriter(app.semanticMemory, userConfig)
    private val database = app.database
    private val llm get() = app.createLlmGateway()
    private var appUpdateCheckedThisRun = false

    // Retry only model failures so ordinary execution failures are not repeated.
    private fun shouldRetryAfterAgentRun(result: com.mobileclaw.agent.AgentResult?, error: Throwable?): Boolean {
        if (error is kotlinx.coroutines.CancellationException) return false
        if (isNonRetryableLlmFailure(error?.message ?: result?.summary.orEmpty())) return false
        if (error != null) return true
        return result?.success == false &&
            result.summary.trim().startsWith("LLM error:") &&
            !isNonRetryableLlmFailure(result.summary)
    }

    // Direct chat has no tool-chain recovery, so retry transient model failures here too.
    private fun shouldRetryDirectChat(error: Throwable?): Boolean =
        error != null &&
            error !is kotlinx.coroutines.CancellationException &&
            !isNonRetryableLlmFailure(error.message.orEmpty())

    // Tell the user when the same model request is being retried.
    private fun appendRetryLogLine(sessionId: String, message: String) {
        updateSession(sessionId) { s ->
            s.copy(
                streamingToken = "",
                streamingThought = "",
                activeLogLines = s.activeLogLines.finishLatestRunningLine() + LogLine(
                    type = LogType.INFO,
                    text = message,
                    details = listOf(
                        progressDetail(ProgressDetailKey.PURPOSE, "Recover this model generation"),
                        progressDetail(ProgressDetailKey.RESULT, "The previous model response failed; retrying the same goal"),
                    ),
                ).withLifecycle(running = false),
            )
        }
    }

    // Role switch requests emitted by SwitchRoleSkill / RoleManagerSkill, consumed in init
    private val roleRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val switchRoleSkill = SwitchRoleSkill(roleManager, roleRequests)
    private var pendingAccessibilityTaskGoal: String? = null
    private var pendingRoleSwitchTaskGoal: String? = null
    private val activeWorkflows = mutableMapOf<String, ActiveWorkflow>()
    private val pendingMiniAppAutoRepairs = mutableMapOf<String, PendingMiniAppAutoRepair>()
    private val workspaceRuntime by lazy {
        WorkspaceRuntimeCoordinator(app.workspaceStore)
    }
    private val workspaceRecorder by lazy {
        WorkspaceRuntimeRecorder(
            workspaceStore = app.workspaceStore,
            memoryWriter = memoryWriter,
            resolveWorkspaceId = { sessionId -> workspaceRuntime.resolveSessionWorkspaceId(sessionId) },
        )
    }
    private val currentSemanticFactsForWorkspace: List<SemanticFactLike>
        get() = _uiState.value.profileState.semanticFacts.map { fact ->
            object : SemanticFactLike {
                override val key: String = fact.key
                override val value: String = fact.value
                override val enabled: Boolean = fact.enabled
                override val updatedAt: Long = fact.updatedAt
            }
        }
    private val chatContextComposer by lazy {
        ChatContextComposer(
            effectiveMessages = { taskRouter.effectiveContextMessages(limit = 12) },
            summarizeAttachments = { taskRouter.summarizeAttachmentsForContext(it) },
            buildArtifactContext = { taskRouter.buildArtifactContext(it) },
            buildWorkspaceContext = {
                val workspace = workspaceRuntime.currentWorkspaceContext(
                    sessionId = _uiState.value.currentSessionId,
                    semanticFacts = currentSemanticFactsForWorkspace,
                )
                val preview = _uiState.value.chatMiniAppPreviewId?.let { appId ->
                    buildString {
                        appendLine("## Chat MiniAPP Validation Preview")
                        appendLine("MiniAPP id: $appId")
                        appendLine("Mode: ${_uiState.value.chatMiniAppPreviewMode.ifBlank { "unknown" }}")
                        appendLine("Status: ${_uiState.value.chatMiniAppPreviewStatus.ifBlank { "unknown" }}")
                        appendLine("Healthy: ${if (_uiState.value.chatMiniAppPreviewHealthy) "yes" else "no"}")
                        appendLine("This panel is a chat-linked validation tool, not the final delivery surface.")
                        appendLine("If preview shows a blank screen or runtime issue, close the preview mentally, then use inspect_logs -> focused repair -> validate.")
                    }.trim()
                }.orEmpty()
                listOf(workspace, preview).filter { it.isNotBlank() }.joinToString("\n\n")
            },
            buildUserMemoryContext = { goal, taskType ->
                buildUserMemoryContextForPrompt(
                    goal = goal,
                    taskType = taskType,
                    activeWorkspaceId = workspaceRuntime.resolveSessionWorkspaceId(_uiState.value.currentSessionId),
                )
            },
        )
    }
    private val roleChatRuntimeBridge by lazy {
        RoleChatRuntimeBridge(app.roleWorkspaceStore)
    }
    private val chatRuntimeCoordinator by lazy {
        ChatRuntimeCoordinator()
    }
    private val roleMemoryCommitter by lazy {
        RoleMemoryCommitter(app.roleWorkspaceStore)
    }
    private fun adaptCurrentRoleForRuntime(role: Role, source: String): RoleRuntimeProfile =
        roleChatRuntimeBridge.adaptCurrentRole(
            role = role,
            skills = registry.allMetasWithTaxonomy(),
            config = config.snapshot(),
            source = source,
        )

    private fun createReadOnlyRoleRuntimeController(maxSteps: Int = 4): RoleRuntimeController =
        RoleRuntimeFactory.createReadOnlyController(
            llm = app.createLlmGateway(),
            roleWorkspaceStore = app.roleWorkspaceStore,
            semanticMemory = app.semanticMemory,
            workspaceStore = app.workspaceStore,
            skillsProvider = { registry.allMetasWithTaxonomy() },
            maxSteps = maxSteps,
        )

    private val taskRouter by lazy {
        TaskRouter(
            aiPagesProvider = { app.aiPageStore.getAll() },
            miniAppsProvider = { runCatching { app.miniAppStore.all() }.getOrDefault(emptyList()) },
            messagesProvider = { _uiState.value.currentRunState.messages },
            currentRoleProvider = { _uiState.value.currentRole },
            workspaceContextProvider = { workspaceRuntime.currentExecutionContext(_uiState.value.currentSessionId) },
        )
    }
    private val taskOrchestrator = TaskOrchestrator()
    private val videoTaskManager = VideoGenerationTaskManager(app, database.videoGenerationTaskDao())
    private val videoImageUploader = CloudinaryImageUploader(app, app.userConfig)
    private val videoPromptLlmClient = OkHttpClient.Builder()
        .proxySelector(AppHttpProxy.proxySelector())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val codexBridgeStreamClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Mini-app open requests emitted by AppManagerSkill
    private val appOpenRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val miniAppPreflightValidator = MiniAppPreflightValidator(app, app.miniAppStore, app.userConfig, app.semanticMemory)
    private val appManagerSkill = AppManagerSkill(app.miniAppStore, miniAppPreflightValidator, appOpenRequests)

    // AI native page open/pin requests emitted by UiBuilderSkill
    private val aiPageOpenRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val aiPagePinRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val uiBuilderSkill = com.mobileclaw.skill.builtin.UiBuilderSkill(app.aiPageStore, aiPageOpenRequests, aiPagePinRequests)

    // Page navigation requests emitted by NavigateSkill
    private val pageRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Session operation requests emitted by SessionManagerSkill
    private val sessionRequests = MutableSharedFlow<SessionRequest>(extraBufferCapacity = 8)

    private val consoleServer = app.consoleServer
    private val userPrefs = app.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        MainUiState(
            config = config.configFlow,
            isConfigured = app.providerReadiness().overallReady,
            currentPage = AppPage.HOME,
            currentModel = app.effectiveModel(),
            supportsMultimodal = app.supportsEffectiveMultimodal(),
            currentRole = Role.DEFAULT,
            consoleServerUrl = if (app.consoleServer.isEnabled()) app.consoleServer.getAccessUrl() else "",
            localModels = app.localModelManager.models.value,
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState

    // Per-session task management (multiple sessions can run simultaneously)
    private val taskJobs = mutableMapOf<String, Job>()
    private val pendingConfirmedRoutes = mutableMapOf<String, TaskRoute>()
    private var videoTaskAutoRefreshJob: Job? = null

    // Track one run generation per session. Increment it when a new task takes over, and validate it when cancelled coroutines resume.
    // Stale callbacks must not overwrite isRunning, activeLogLines, or task handles, which would bypass loadSession safeguards.
    private val runGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun beginRunGeneration(sessionId: String): Long =
        runGenerations.merge(sessionId, 1L) { old, _ -> old + 1 } ?: 1L

    private fun adoptRunGeneration(sessionId: String, generation: Long) {
        if (sessionId.isNotBlank()) runGenerations[sessionId] = generation
    }

    private fun isRunGenerationCurrent(sessionId: String, generation: Long): Boolean =
        runGenerations[sessionId] == generation

    // Prevent the agent from creating, switching away from, or deleting the active session while a task runs.
    // Switching reloads messages and deletion removes history; both would appear as lost or overwritten chat history.
    private fun guardSessionMutation(request: SessionRequest): String? {
        fun isBusy(id: String) = id.isNotBlank() && _uiState.value.sessionStates[id]?.isRunning == true
        val currentId = _uiState.value.currentSessionId
        return when (request) {
            is SessionRequest.Create, is SessionRequest.Switch -> {
                if (isBusy(currentId)) {
                    "Rejected: a task is still running in the current session. Do not create or switch sessions while a task is executing; finish the current task first."
                } else null
            }
            is SessionRequest.Delete -> {
                if (isBusy(request.id)) {
                    "Rejected: session '${request.id}' is executing a task and cannot be deleted. Deleting it would destroy the chat history the user is watching."
                } else null
            }
            is SessionRequest.Rename -> null
        }
    }

    private fun updateSession(sessionId: String, transform: (SessionRunState) -> SessionRunState) {
        _uiState.update { state ->
            val current = state.sessionStates[sessionId] ?: SessionRunState()
            state.copy(sessionStates = state.sessionStates + (sessionId to transform(current)))
        }
    }

    init {
        registerBuiltinSkills()
        loadDynamicSkills()
        _uiState.update { it.copy(allSkills = registry.userVisibleMetasWithTaxonomy()) }
        loadMiniApps()
        loadUserAvatar()
        loadVideoTasks()

        viewModelScope.launch {
            config.configFlow.collect { snap ->
                val configured = app.providerReadiness(snap).overallReady
                _uiState.update { it.copy(
                    isConfigured = configured,
                    currentModel = app.effectiveModel(snap),
                    supportsMultimodal = app.supportsEffectiveMultimodal(snap),
                ) }
            }
        }

        viewModelScope.launch {
            app.localModelManager.models.collect { models ->
                val snap = config.snapshot()
                _uiState.update { it.copy(
                    localModels = models,
                    isConfigured = app.providerReadiness(snap).overallReady,
                    currentModel = app.effectiveModel(snap),
                    supportsMultimodal = app.supportsEffectiveMultimodal(snap),
                ) }
            }
        }

        viewModelScope.launch {
            app.chatGptAuthManager.state.collect {
                val snap = config.snapshot()
                _uiState.update { state -> state.copy(
                    isConfigured = app.providerReadiness(snap).overallReady,
                    currentModel = app.effectiveModel(snap),
                    supportsMultimodal = app.supportsEffectiveMultimodal(snap),
                ) }
            }
        }

        viewModelScope.launch {
            app.appForeground.collect { foreground ->
                onAppForegroundChanged(foreground)
            }
        }

        miniAppValidationOverlay.onStatusChanged = { appId, status, healthy ->
            updateChatMiniAppPreviewStatus(appId, status, healthy)
        }
        miniAppValidationOverlay.onDismissed = { appId ->
            val snapshot = _uiState.value
            if (snapshot.chatMiniAppPreviewId == appId) {
                clearChatMiniAppPreview()
            }
        }

        // React to role switch requests from the SwitchRoleSkill
        viewModelScope.launch {
            roleRequests.collect { roleId ->
                roleManager.get(roleId)?.let { setActiveRole(it) }
            }
        }

        // React to mini-app open requests from AppManagerSkill
        viewModelScope.launch {
            app.pendingAgentTask.collect { task -> runTask(task) }
        }

        viewModelScope.launch {
            appOpenRequests.collect { appId ->
                loadMiniApps()
                val shownInOverlay = miniAppValidationOverlay.show(appId, validationMode = true)
                _uiState.update {
                    if (shownInOverlay) {
                        it.copy(
                            chatMiniAppPreviewId = appId,
                            chatMiniAppPreviewMode = "overlay_validation",
                            chatMiniAppPreviewSessionId = it.currentSessionId,
                            chatMiniAppPreviewStatus = "Validation preview loading",
                            chatMiniAppPreviewHealthy = true,
                            openAppId = null,
                        )
                    } else {
                        it.copy(
                            openAppId = null,
                            chatMiniAppPreviewId = appId,
                            chatMiniAppPreviewMode = "validation",
                            chatMiniAppPreviewSessionId = it.currentSessionId,
                            chatMiniAppPreviewStatus = "Validation preview loading",
                            chatMiniAppPreviewHealthy = true,
                        )
                    }
                }
            }
        }

        // Sync AI pages from store
        viewModelScope.launch {
            app.aiPageStore.pages.collect { pages ->
                _uiState.update { it.copy(aiPages = pages) }
            }
        }

        // React to AI page open requests from UiBuilderSkill
        viewModelScope.launch {
            aiPageOpenRequests.collect { pageId ->
                _uiState.update { it.copy(openAiPageId = pageId) }
            }
        }

        // React to AI page pin requests from UiBuilderSkill
        viewModelScope.launch {
            aiPagePinRequests.collect { pageId ->
                _uiState.update { it.copy(openAiPageId = "pin:$pageId") }
            }
        }

        // React to page navigation requests from NavigateSkill
        viewModelScope.launch {
            pageRequests.collect { pageName ->
                val page = when (pageName) {
                    "chat"        -> AppPage.CHAT
                    "settings"    -> AppPage.SETTINGS
                    "ai_basic_settings", "ai_basics" -> AppPage.AI_BASIC_SETTINGS
                    "user_info"    -> AppPage.USER_INFO
                    "general_settings" -> AppPage.GENERAL_SETTINGS
                    "tools_settings", "tools" -> AppPage.TOOLS_SETTINGS
                    "memory_settings", "memory" -> AppPage.MEMORY_SETTINGS
                    "skills"      -> AppPage.SKILLS
                    "profile"     -> AppPage.PROFILE
                    "roles"       -> AppPage.ROLES
                    "user_config" -> AppPage.USER_CONFIG
                    "apps"        -> AppPage.APPS
                    "console"     -> AppPage.CONSOLE
                    "help"        -> AppPage.HELP
                    "workspace"   -> AppPage.WORKSPACE
                    "town", "ai_town" -> AppPage.ROLES
                    else          -> AppPage.HOME
                }
                navigate(page)
            }
        }

        // React to session operation requests from SessionManagerSkill
        viewModelScope.launch(Dispatchers.IO) {
            sessionRequests.collect { req ->
                when (req) {
                    is SessionRequest.Create -> {
                        createNewSessionInternal()
                        if (req.title != str(R.string.vm_new_)) {
                            val sid = _uiState.value.currentSessionId
                            if (sid.isNotBlank()) database.sessionDao().updateTitle(sid, req.title)
                        }
                    }
                    is SessionRequest.Switch -> loadSession(req.id)
                    is SessionRequest.Delete -> deleteSession(req.id)
                    is SessionRequest.Rename -> database.sessionDao().updateTitle(req.id, req.title)
                }
            }
        }

        // React to messages sent from the LAN console browser
        viewModelScope.launch {
            consoleServer.messageRequests.collect { message -> runTask(message) }
        }

        // Keep user config entries in sync
        viewModelScope.launch {
            userConfig.entriesFlow.collect { entries ->
                _uiState.update { it.copy(userConfigEntries = entries) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            refreshProfileFacts()
        }

        // Keep skill notes in sync
        viewModelScope.launch {
            app.skillNotesStore.notesFlow.collect { notes ->
                _uiState.update { it.copy(skillNotes = notes) }
            }
        }

        // Load skill level overrides and apply to registry
        viewModelScope.launch(Dispatchers.IO) {
            app.skillLevelStore.overridesFlow.collect { overrides ->
                overrides.forEach { (id, level) -> registry.setLevelOverride(id, level) }
                _uiState.update { it.copy(skillLevelOverrides = overrides) }
            }
        }

        // Keep roles in sync — updates whenever RoleManager.save/delete is called (e.g. from AI)
        viewModelScope.launch {
            roleManager.rolesFlow.collect { roles ->
                townStore.ensureRooms(roles)
                roles.forEach { role ->
                    runCatching {
                        app.roleWorkspaceStore.ensure(role, registry.allMetasWithTaxonomy())
                        app.roleWorkspaceStore.recordModelConfig(role, config.snapshot(), source = "roles_flow")
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        availableRoles = roles,
                        currentRole = roles.firstOrNull { it.id == state.currentRole.id } ?: state.currentRole,
                        detailRole = state.detailRole?.let { current -> roles.firstOrNull { it.id == current.id } ?: current },
                        editingRole = state.editingRole?.let { current -> roles.firstOrNull { it.id == current.id } ?: current },
                    )
                }
                ensureRolePortraits(roles)
            }
        }

        viewModelScope.launch {
            townStore.state.collect { town ->
                _uiState.update { it.copy(agentTown = town) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            loadSessions()
            // If no session exists yet, create one
            if (database.sessionDao().count() == 0) {
                createNewSessionInternal()
            } else {
                val sessions = database.sessionDao().recent(limit = 50)
                val latest = sessions.firstOrNull()
                if (latest != null) {
                    _uiState.update { it.copy(currentSessionId = latest.id) }
                    loadSessionMessages(latest.id)
                }
            }
        }

        // Smart recommendations: app continuations + emotional context + history
        viewModelScope.launch(Dispatchers.IO) {
            val recent = runCatching { database.episodeDao().recent(limit = 24) }.getOrDefault(emptyList())
            val profileFacts = runCatching { app.semanticMemory.all() }.getOrDefault(emptyMap())
            val miniApps = runCatching { app.miniAppStore.all() }.getOrDefault(emptyList())
            val recentUserMsgs = runCatching { database.conversationDao().recentUserMessages(limit = 20) }.getOrDefault(emptyList()).map { it.content }
            val recs = buildSmartRecommendations(recent, profileFacts, miniApps, recentUserMsgs)
            _uiState.update { it.copy(recommendations = recs) }
        }

        checkPrivServer()
    }

    // ── Session Management ───────────────────────────────────────────────────

    fun createNewSession() {
        viewModelScope.launch(Dispatchers.IO) {
            createNewSessionInternal()
        }
    }

    fun createNewSessionAndOpen() {
        viewModelScope.launch(Dispatchers.IO) {
            createNewSessionInternal()
            navigate(AppPage.CHAT)
        }
    }

    fun createNewCodexDesktopSession() {
        viewModelScope.launch(Dispatchers.IO) {
            createNewSessionInternal(codexDesktopMode = true)
        }
    }

    fun setCodexDesktopMode(enabled: Boolean) {
        val sessionId = _uiState.value.currentSessionId
        _uiState.update { state ->
            state.copy(
                codexDesktopMode = enabled,
                codexDesktopSessionIds = if (enabled) {
                    if (sessionId.isBlank()) state.codexDesktopSessionIds else state.codexDesktopSessionIds + sessionId
                } else {
                    if (sessionId.isBlank()) state.codexDesktopSessionIds else state.codexDesktopSessionIds - sessionId
                },
            )
        }
    }

    private suspend fun createNewSessionInternal(codexDesktopMode: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val roleId = _uiState.value.currentRole.id
        database.sessionDao().insert(SessionEntity(
            id = id,
            title = if (codexDesktopMode) "Codex session" else str(R.string.vm_new_),
            roleId = roleId,
        ))
        _uiState.update {
            it.copy(
                currentSessionId = id,
                codexDesktopMode = codexDesktopMode,
                codexDesktopSessionIds = if (codexDesktopMode) it.codexDesktopSessionIds + id else it.codexDesktopSessionIds - id,
            )
        }
        loadSessions()
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                currentSessionId = sessionId,
                codexDesktopMode = sessionId in it.codexDesktopSessionIds,
            ) }
            // Only load DB messages if the session is NOT currently running (running state is live)
            val isAlreadyRunning = _uiState.value.sessionStates[sessionId]?.isRunning == true || taskJobs[sessionId] != null
            if (!isAlreadyRunning) {
                loadSessionMessages(sessionId)
            }
            loadSessions()
        }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { sessionRequests.emit(SessionRequest.Rename(id, title)) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.sessionDao().delete(sessionId)
            database.sessionMessageDao().deleteForSession(sessionId)
            _uiState.update { it.copy(codexDesktopSessionIds = it.codexDesktopSessionIds - sessionId) }
            if (_uiState.value.currentSessionId == sessionId) {
                createNewSessionInternal()
            } else {
                loadSessions()
            }
        }
    }

    private suspend fun loadSessions() {
        val sessions = runCatching { database.sessionDao().recent(limit = 50) }.getOrDefault(emptyList())
        _uiState.update { it.copy(sessions = sessions) }
    }

    private suspend fun loadSessionMessages(sessionId: String, pageSize: Int = 20) {
        val total = runCatching { database.sessionMessageDao().countForSession(sessionId) }.getOrDefault(0)
        val entities = runCatching {
            database.sessionMessageDao().forSessionPaged(sessionId, pageSize, 0)
        }.getOrDefault(emptyList()).reversed()  // DESC→reversed gives ASC order
        val messages = entities.map { it.toChatMessage() }
        val hasMore = total > pageSize
        // Merge instead of replacing because memory may contain unpersisted messages from an active or interrupted turn.
        updateSession(sessionId) { it.copy(messages = mergeLoadedMessages(messages, it.messages)) }
        _uiState.update { it.copy(
            historyOffset = pageSize,
            historyHasMore = hasMore,
            historyLoading = false,
        )}
    }

    // Session messages are append-only: the database page is persisted, while the in-memory tail may still be pending.
    // Use the database page as the base and reattach the newer in-memory tail to preserve pending messages.
    private fun mergeLoadedMessages(dbMessages: List<ChatMessage>, inMemory: List<ChatMessage>): List<ChatMessage> {
        if (inMemory.isEmpty()) return dbMessages
        if (dbMessages.isEmpty()) return inMemory
        val anchor = inMemory.indexOfLast { it == dbMessages.last() }
        val unsavedTail = if (anchor >= 0) {
            inMemory.drop(anchor + 1)
        } else {
            // If the database tail is absent from memory, keep the portion after the last in-memory message known to be persisted,
            // then exclude messages already on the database page to isolate the unpersisted tail.
            val lastSharedIdx = inMemory.indexOfLast { mem -> dbMessages.any { it == mem } }
            inMemory.drop(lastSharedIdx + 1).filter { mem -> dbMessages.none { it == mem } }
        }
        return dbMessages + unsavedTail
    }

    fun loadMoreHistory() {
        val sessionId = _uiState.value.currentSessionId
        if (sessionId.isBlank() || _uiState.value.historyLoading || !_uiState.value.historyHasMore) return
        _uiState.update { it.copy(historyLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val offset = _uiState.value.historyOffset
                val pageSize = 20
                val total = runCatching { database.sessionMessageDao().countForSession(sessionId) }.getOrDefault(0)
                val entities = runCatching {
                    database.sessionMessageDao().forSessionPaged(sessionId, pageSize, offset)
                }.getOrDefault(emptyList()).reversed()
                val older = entities.map { it.toChatMessage() }
                updateSession(sessionId) { it.copy(messages = older + it.messages) }
                _uiState.update { state ->
                    if (state.currentSessionId != sessionId) state else state.copy(
                        historyOffset = offset + pageSize,
                        historyHasMore = offset + pageSize < total,
                        historyLoading = false,
                    )
                }
            } finally {
                _uiState.update { state ->
                    if (state.currentSessionId == sessionId) state.copy(historyLoading = false) else state
                }
            }
        }
    }

    private suspend fun persistMessages(sessionId: String, userMsg: ChatMessage?, agentMsgs: List<ChatMessage>) {
        if (sessionId.isBlank()) return
        val gson = Gson()

        if (userMsg != null) {
            database.sessionMessageDao().insert(SessionMessageEntity(
                sessionId = sessionId,
                role = "user",
                text = userMsg.text,
                attachmentsJson = serializeAttachments(userMsg.attachments),
                imageBase64 = userMsg.imageBase64,
            ))
        }
        agentMsgs.forEach { agentMsg ->
            // Strip imageBase64 from log lines to avoid bloating the DB.
            val sanitizedLogLines = agentMsg.logLines.map { it.copy(imageBase64 = null) }
            database.sessionMessageDao().insert(SessionMessageEntity(
                sessionId = sessionId,
                role = "agent",
                text = agentMsg.text,
                logLinesJson = gson.toJson(sanitizedLogLines),
                attachmentsJson = serializeAttachments(agentMsg.attachments),
                senderRoleId = agentMsg.senderRoleId,
                senderRoleName = agentMsg.senderRoleName,
                senderRoleAvatar = agentMsg.senderRoleAvatar,
            ))
        }

        // Update session title from first user message if still default
        val session = database.sessionDao().recent(50).find { it.id == sessionId }
        if (session != null && session.title == str(R.string.vm_new_) && userMsg != null) {
            val title = userMsg.text.take(30).ifBlank {
                if (userMsg.imageBase64 != null) str(R.string.chat_20def7) else str(R.string.vm_new_)
            }
            database.sessionDao().updateTitle(sessionId, title)
            loadSessions()
        }
    }

    private suspend fun persistUserOnlyMessage(
        sessionId: String,
        userMsg: ChatMessage,
        fallbackTitle: String,
    ) {
        if (sessionId.isBlank()) return
        database.sessionMessageDao().insert(SessionMessageEntity(
            sessionId = sessionId,
            role = "user",
            text = userMsg.text,
            attachmentsJson = serializeAttachments(userMsg.attachments),
            imageBase64 = userMsg.imageBase64,
        ))

        val session = database.sessionDao().recent(50).find { it.id == sessionId }
        if (session != null && session.title == str(R.string.vm_new_)) {
            val title = userMsg.text.take(30).ifBlank { fallbackTitle }
            database.sessionDao().updateTitle(sessionId, title)
            loadSessions()
        }
    }

    private fun buildAgentMessages(
        summary: String,
        logLines: List<LogLine>,
        attachments: List<SkillAttachment>,
        senderRole: Role,
    ): List<ChatMessage> {
        return buildNarrativeAgentMessages(
            summary = summary,
            logLines = logLines,
            attachments = attachments,
            sender = AgentSenderMeta(
                id = senderRole.id,
                name = senderRole.name,
                avatar = senderRole.avatar,
            ),
            isRunning = false,
        )
    }

    // ── Role Management ──────────────────────────────────────────────────────

    fun setActiveRole(role: Role) {
        runCatching {
            app.roleWorkspaceStore.ensure(role, registry.allMetasWithTaxonomy())
            app.roleWorkspaceStore.recordModelConfig(role, config.snapshot(), source = "set_active_role")
        }
        _uiState.update { it.copy(currentRole = role) }
    }

    private fun roleLlmCallOptions(role: Role): LlmCallOptions {
        val resolved = RoleModelResolver.resolve(role, config.snapshot())
        return if (resolved.inheritedDefault) LlmCallOptions() else resolved.callOptions
    }

    fun saveCustomRole(role: Role) {
        viewModelScope.launch(Dispatchers.IO) {
            roleManager.save(role)  // triggers rolesFlow → UI auto-updates via collector above
            runCatching { app.roleWorkspaceStore.ensure(role, registry.allMetasWithTaxonomy()) }
            roleManager.get(role.id)?.let { savedRole ->
                _uiState.update { state ->
                    if (state.currentRole.id == savedRole.id) state.copy(currentRole = savedRole) else state
                }
            }
        }
    }

    fun saveRoleWithChatProtocol(role: Role, chatProtocolMarkdown: String) {
        viewModelScope.launch(Dispatchers.IO) {
            roleManager.save(role)
            runCatching {
                app.roleWorkspaceStore.ensure(role, registry.allMetasWithTaxonomy())
                app.roleWorkspaceStore.write(
                    role.id,
                    RoleWorkspaceStore.CHAT_PROTOCOL_MD,
                    chatProtocolMarkdown.trimEnd() + "\n",
                )
            }
            roleManager.get(role.id)?.let { savedRole ->
                _uiState.update { state ->
                    state.copy(
                        currentRole = if (state.currentRole.id == savedRole.id) savedRole else state.currentRole,
                        editingRole = savedRole,
                    )
                }
            }
        }
    }

    fun setUserConfigEntry(key: String, value: String, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { memoryWriter.syncUserConfig(key, value, description) }
            if (result.isSuccess) {
                _uiState.update { state ->
                    val previousDescription = state.userConfigEntries[key]?.description.orEmpty()
                    state.copy(
                        userConfigEntries = state.userConfigEntries + (
                            key to ConfigEntry(
                                value = value,
                                description = description.ifBlank { previousDescription },
                            )
                        ),
                    )
                }
            }
            refreshProfileFacts()
        }
    }

    fun deleteUserConfigEntry(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { memoryWriter.deleteUserConfig(key) }
            if (result.isSuccess) {
                _uiState.update { state -> state.copy(userConfigEntries = state.userConfigEntries - key) }
            }
            refreshProfileFacts()
        }
    }

    private suspend fun refreshProfileFacts() {
        val loadedCount = _uiState.value.profileState.semanticFacts.size.coerceAtLeast(PROFILE_MEMORY_PAGE_SIZE)
        val semanticFacts = runCatching { app.semanticMemory.pageIncludingDisabled(limit = loadedCount, offset = 0) }.getOrDefault(emptyList())
        val facts = semanticFacts.filter { it.enabled }.associate { it.key to it.value }
        _uiState.update {
            it.copy(
                profileState = it.profileState.copy(
                    facts = facts,
                    semanticFacts = semanticFacts,
                    memoryHasMore = semanticFacts.size >= loadedCount,
                )
            )
        }
    }

    private suspend fun recordUserMemoryHints(text: String, workspaceId: String? = null) {
        runCatching { memoryWriter.recordExplicitUserText(text) }
        workspaceId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { memoryWriter.recordScopedUserText(id, text) }
        }
        refreshProfileFacts()
    }

    fun loadUserAvatar() {
        val uri = userPrefs.getString("avatar_uri", null)
        _uiState.update { it.copy(userAvatarUri = uri) }
    }

    fun setUserAvatarUri(uri: String) {
        app.contentResolver.runCatching {
            takePersistableUriPermission(android.net.Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        userPrefs.edit().putString("avatar_uri", uri).apply()
        _uiState.update { it.copy(userAvatarUri = uri) }
    }

    fun deleteCustomRole(roleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            roleManager.delete(roleId)  // triggers rolesFlow → UI auto-updates
            if (_uiState.value.currentRole.id == roleId) {
                _uiState.update { it.copy(currentRole = Role.DEFAULT) }
            }
        }
    }

    fun editRole(role: Role) {
        _uiState.update { it.copy(editingRole = role, roleWorkspaceFiles = emptyList()) }
        loadRoleWorkspace(role)
        navigate(AppPage.ROLE_EDIT)
        if (_uiState.value.availableModels.isEmpty()) fetchModels()
    }

    fun copyBuiltinRoleForEditing(role: Role) {
        duplicateRoleForEditing(role)
    }

    fun duplicateRoleForEditing(role: Role) {
        val copyName = role.name.ifBlank { role.id } + str(R.string.role_copy_suffix)
        editRole(
            role.copy(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                name = copyName,
                isBuiltin = false,
            )
        )
    }

    fun openRoleDetail(role: Role) {
        townStore.ensureRooms(roleManager.all())
        _uiState.update { it.copy(detailRole = role) }
        navigate(AppPage.ROLE_DETAIL)
    }

    fun openRoleWorkspace(role: Role) {
        _uiState.update { it.copy(detailRole = role) }
        loadRoleWorkspace(role)
        navigate(AppPage.ROLE_WORKSPACE)
    }

    fun refreshRoleWorkspace() {
        val role = _uiState.value.detailRole ?: return
        loadRoleWorkspace(role)
    }

    private fun loadRoleWorkspace(role: Role) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = runCatching {
                app.roleWorkspaceStore.ensure(role, registry.allMetasWithTaxonomy())
                app.roleWorkspaceStore.refreshSkillIndex(role.id, registry.allMetasWithTaxonomy())
                app.roleWorkspaceStore.recordModelConfig(role, config.snapshot(), source = "role_workspace_page")
                app.roleWorkspaceStore.list(role.id)
                    .filter { it.endsWith(".md") || it.endsWith(".json") }
                    .sortedWith(compareBy<String> { roleWorkspaceFileOrder(it) }.thenBy { it })
                    .map { name ->
                        RoleWorkspaceFileUi(
                            name = name,
                            content = app.roleWorkspaceStore.read(role.id, name).orEmpty(),
                        )
                    }
            }.getOrDefault(emptyList())
            _uiState.update { it.copy(roleWorkspaceFiles = files) }
        }
    }

    fun openRoleHome(role: Role) {
        townStore.ensureRooms(roleManager.all())
        _uiState.update { it.copy(detailRole = role, openTownRoleId = null) }
        navigate(AppPage.ROLE_DETAIL)
    }

    fun generateRolePortrait(role: Role) {
        if (role.id in _uiState.value.rolePortraitGeneratingIds) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(rolePortraitGeneratingIds = it.rolePortraitGeneratingIds + role.id) }
            val result = runCatching {
                val imageReadiness = checkRolePortraitImageReadiness()
                var imageGenerationFailure: String? = null
                val generatedDataUri = imageReadiness.target?.let { target ->
                    val selfBrief = createRoleSelfPortraitBrief(role)
                    // Generate role images only when a real image-generation path is available.
                    val basePrompt = selfBrief["portrait_prompt"]?.asString?.takeIf { it.isNotBlank() }
                        ?: selfBrief["render_prompt"]?.asString?.takeIf { it.isNotBlank() }
                        ?: error("role visual brief did not return a portrait prompt")
                    val prompt = """
                        $basePrompt

                        Runtime constraints only:
                        - Output one single complete role portrait image.
                        - Keep the full body visible inside frame.
                        - Compose the full figure with breathing room around head, hands, feet, and major props.
                        - Do not simulate animation frames, sprite strips, repeated poses, or multi-panel sheets.
                        - No text, UI, multi-view sheet, lineup, or poster layout.
                    """.trimIndent()
                    val generation = target.generator.execute(
                        mapOf(
                            "prompt" to prompt,
                            "model" to target.model,
                            "size" to "1024x1024",
                            "quality" to "high",
                        )
                    )
                    if (!generation.success) {
                        imageGenerationFailure = generation.output.ifBlank { "generate_image returned failure" }
                        Log.w(TAG, "Role portrait image generation failed. model=${target.model} reason=${imageGenerationFailure?.take(500)}")
                    }
                    generation.takeIf { it.success }?.let { image ->
                        image.imageBase64 ?: (image.data as? SkillAttachment.ImageData)?.base64
                    }.also { dataUri ->
                        if (dataUri == null) {
                            imageGenerationFailure = imageGenerationFailure ?: "generate_image returned empty image data"
                            Log.w(TAG, "Role portrait image generation failed or returned empty data. model=${target.model}")
                        }
                    }
                } ?: run {
                    imageGenerationFailure = imageReadiness.unavailableReason
                    null
                }
                val dataUri = generatedDataUri ?: createSimpleRoleIdentityDataUri(role)
                val imagePath = saveRoleImageAsset(role, dataUri)
                val updatedRole = role.copy(avatar = normalizeRoleAvatar(role.id, imagePath))
                roleManager.save(updatedRole)
                applyRoleHomeLayout(updatedRole)
                _uiState.update { state ->
                    state.copy(
                        availableRoles = state.availableRoles.map { if (it.id == updatedRole.id) updatedRole else it },
                        currentRole = if (state.currentRole.id == updatedRole.id) updatedRole else state.currentRole,
                        detailRole = state.detailRole?.let { if (it.id == updatedRole.id) updatedRole else it },
                        editingRole = state.editingRole?.let { if (it.id == updatedRole.id) updatedRole else it },
                    )
                }
                RolePortraitGenerationResult(
                    usedLocalIdentity = generatedDataUri == null,
                    attemptedImageGeneration = imageReadiness.target != null,
                    failureMessage = imageGenerationFailure,
                )
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(rolePortraitGeneratingIds = it.rolePortraitGeneratingIds - role.id) }
                result.onSuccess { portrait ->
                    val message = when {
                        !portrait.usedLocalIdentity -> str(R.string.role_portrait_generation_done)
                        portrait.attemptedImageGeneration -> {
                            val reason = portrait.failureMessage?.lineSequence()?.firstOrNull()?.take(90)
                            if (reason.isNullOrBlank()) {
                                "Image generation failed; using a simple local avatar"
                            } else {
                                "Image generation failed; using a simple local avatar: $reason"
                            }
                        }
                        else -> "Image generation is unavailable; using a simple local avatar"
                    }
                    Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(app, str(R.string.role_portrait_generation_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class RolePortraitGenerationResult(
        val usedLocalIdentity: Boolean,
        val attemptedImageGeneration: Boolean,
        val failureMessage: String?,
    )

    private fun saveRoleImageAsset(role: Role, dataUri: String): String {
        val comma = dataUri.indexOf(',')
        val payload = if (comma >= 0) dataUri.substring(comma + 1) else dataUri
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("role image data could not be decoded")
        val dir = File(app.filesDir, "role_images").also { it.mkdirs() }
        val safeId = role.id.replace(Regex("[^a-zA-Z0-9._-]+"), "_").ifBlank { "role" }
        val outFile = File(dir, "${safeId}_${System.currentTimeMillis()}.png")
        outFile.outputStream().use { output ->
            decoded.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        decoded.recycle()
        return outFile.absolutePath
    }

    private data class RolePortraitImageTarget(
        val model: String,
        val generator: com.mobileclaw.skill.Skill,
    )

    private data class RolePortraitImageReadiness(
        val target: RolePortraitImageTarget?,
        val unavailableReason: String?,
    )

    private suspend fun checkRolePortraitImageReadiness(): RolePortraitImageReadiness {
        val model = configuredRolePortraitImageModelOrNull()
            ?: return RolePortraitImageReadiness(null, "image model is not configured")
        val generator = registry.get("generate_image")
            ?: return RolePortraitImageReadiness(null, "generate_image tool is unavailable")
        rolePortraitImageConfigIssue(model)?.let { issue ->
            return RolePortraitImageReadiness(null, issue)
        }
        return RolePortraitImageReadiness(RolePortraitImageTarget(model, generator), null)
    }

    private suspend fun rolePortraitImageConfigIssue(model: String): String? {
        val normalized = model.trim().lowercase()
        if (normalized == "hf-flux-schnell" || normalized.startsWith("huggingface:")) {
            val token = userConfig.get("huggingface_api_key")?.trim()?.takeIf { it.isNotBlank() }
                ?: userConfig.get("image_api_key")?.trim()?.takeIf { it.isNotBlank() }
            return if (token == null) "Hugging Face image key is not configured" else null
        }
        val snap = config.snapshot()
        val endpoint = snap.activeGateway?.capabilityEndpoint("image")?.trim()?.takeIf { it.isNotBlank() }
            ?: userConfig.get("image_api_endpoint")?.trim()?.takeIf { it.isNotBlank() }
            ?: snap.endpoint.trim().takeIf { it.isNotBlank() }
        if (endpoint == null) return "image endpoint is not configured"
        val apiKey = snap.activeGateway?.capabilityApiKey("image")?.trim()?.takeIf { it.isNotBlank() }
            ?: userConfig.get("image_api_key")?.trim()?.takeIf { it.isNotBlank() }
            ?: snap.apiKey.trim().takeIf { it.isNotBlank() }
        if (apiKey == null) return "image API key is not configured"
        return null
    }

    private fun createSimpleRoleIdentityDataUri(role: Role): String {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(247, 244, 238)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val markRect = RectF(56f, 56f, size - 56f, size - 56f)
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(8, 8, 8)
        }
        canvas.drawRoundRect(markRect, 96f, 96f, markPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
            color = Color.rgb(247, 244, 238)
            alpha = 82
        }
        canvas.drawRoundRect(RectF(86f, 86f, size - 86f, size - 86f), 74f, 74f, ringPaint)

        val initial = role.name.trim().takeIf { it.isNotBlank() }
            ?.let { it.first().uppercaseChar().toString() }
            ?: role.id.trim().takeIf { it.isNotBlank() }?.first()?.uppercaseChar()?.toString()
            ?: "A"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 176f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(initial, 0, initial.length, textBounds)
        canvas.drawText(initial, size * 0.5f, 238f - textBounds.exactCenterY(), textPaint)

        val label = role.name.trim().takeIf { it.isNotBlank() } ?: role.id.trim().ifBlank { "AI" }
        val labelText = label.take(10)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 150
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.04f
        }
        val labelBounds = Rect()
        labelPaint.getTextBounds(labelText, 0, labelText.length, labelBounds)
        canvas.drawText(labelText, size * 0.5f, 366f - labelBounds.exactCenterY(), labelPaint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun applyRoleHomeLayout(role: Role) {
        val builder = registry.get("town_builder") ?: return
        val plan = builder.execute(
            mapOf(
                "action" to "plan_room_layout",
                "role_id" to role.id,
            )
        )
        if (!plan.success) return
        val json = runCatching { JsonParser.parseString(plan.output).asJsonObject }.getOrNull() ?: return
        builder.execute(
            mapOf(
                "action" to "decorate_room",
                "role_id" to role.id,
                "house_name" to str(R.string.role_detail_home_for, role.name.ifBlank { role.id }),
                "style" to json.stringOrNull("style").orEmpty(),
                "house_sprite" to json.stringOrNull("house_sprite").orEmpty(),
                "accent" to json.stringOrNull("accent").orEmpty(),
                "motto" to json.stringOrNull("motto").orEmpty(),
                "idle_line" to json.stringOrNull("idle_line").orEmpty(),
                "working_line" to json.stringOrNull("working_line").orEmpty(),
            ).filterValues { value -> value.isNotBlank() }
        )
        json.getAsJsonArray("furniture")?.forEach { element ->
            val item = element.asJsonObject
            val id = item.stringOrNull("id") ?: return@forEach
            val type = item.stringOrNull("type") ?: return@forEach
            builder.execute(
                mapOf<String, Any>(
                    "action" to "place_furniture",
                    "role_id" to role.id,
                    "id" to id,
                    "type" to type,
                    "x" to (item.intOrNull("x") ?: 0),
                    "y" to (item.intOrNull("y") ?: 0),
                    "width" to (item.intOrNull("width") ?: 2),
                    "height" to (item.intOrNull("height") ?: 2),
                    "layer_name" to item.stringOrNull("layer").orEmpty(),
                    "variant" to item.stringOrNull("variant").orEmpty(),
                    "color" to item.stringOrNull("color").orEmpty(),
                ).filterValues { value -> value !is String || value.isNotBlank() }
            )
        }
    }

    private fun ensureRolePortraits(roles: List<Role>) {
        // Do not generate an avatar automatically when the role page first opens. Wait for the explicit user action,
        // then use the configured image model or a simple local text avatar without silently calling external services.
    }

    private suspend fun configuredRolePortraitImageModelOrNull(): String? {
        userConfig.get("role_portrait_image_model")
            ?.takeIf { it.isNotBlank() && it.isConfiguredImageModel() }
            ?.let { return it }
        userConfig.get("image_model")
            ?.takeIf { it.isNotBlank() && it.isConfiguredImageModel() }
            ?.let { return it }
        val snap = config.snapshot()
        snap.imageModel
            ?.takeIf { it.isNotBlank() && it.isConfiguredImageModel() }
            ?.let { return it }
        userConfig.get("image_api_model")
            ?.takeIf { it.isNotBlank() && it.isConfiguredImageModel() }
            ?.let { return it }
        val endpoint = (
            snap.activeGateway?.capabilityEndpoint("image")?.takeIf { it.isNotBlank() }
                ?: userConfig.get("image_api_endpoint")?.takeIf { it.isNotBlank() }
                ?: snap.endpoint.takeIf { snap.activeGateway?.hasCapability("image") == true }
                ?: ""
            ).lowercase()
        val currentModel = snap.activeGateway
            ?.takeIf { it.hasCapability("image") }
            ?.model
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
        return when {
            currentModel.isConfiguredImageModel() -> currentModel
            "api.openai.com" in endpoint || "openai" in endpoint -> "gpt-image-2"
            "agnes" in endpoint -> "agnes-image-2.0-flash"
            "dashscope" in endpoint || "aliyuncs" in endpoint -> "wanx2.1-t2i-turbo"
            "siliconflow" in endpoint -> "black-forest-labs/FLUX.1-schnell"
            "together" in endpoint -> "black-forest-labs/FLUX.1-schnell-Free"
            userConfig.get("huggingface_api_key")?.isNotBlank() == true -> "hf-flux-schnell"
            else -> null
        }
    }

    private fun decorateRoleHomeWithTool(role: Role, skillId: String, purpose: String) {
        if (role.id.isBlank() || skillId == "town_builder") return
        val meta = registry.get(skillId)?.meta
        runCatching {
            townStore.pinSkill(
                role.id,
                RoomTool(
                    id = skillId,
                    title = meta?.name ?: skillId,
                    category = purpose.take(40),
                )
            )
        }
    }

    private fun decorateRoleHomeWithAttachment(
        role: Role,
        skillId: String?,
        purpose: String,
        attachment: SkillAttachment,
    ) {
        if (role.id.isBlank()) return
        val artifact = when (attachment) {
            is SkillAttachment.ImageData -> RoomArtifact(
                id = stableHomeId("image", attachment.prompt ?: purpose),
                type = "image",
                title = attachment.prompt?.take(36)?.ifBlank { null } ?: "Generated image",
                subtitle = purpose.take(80),
            )
            is SkillAttachment.FileData -> RoomArtifact(
                id = stableHomeId("file", attachment.path),
                type = attachment.mimeType.toArtifactType(),
                title = attachment.name,
                subtitle = "${attachment.mimeType} · ${formatBytesForHome(attachment.sizeBytes)}",
            )
            is SkillAttachment.HtmlData -> RoomArtifact(
                id = stableHomeId("html", attachment.path),
                type = "html",
                title = attachment.title,
                subtitle = "HTML preview",
            )
            is SkillAttachment.WebPage -> RoomArtifact(
                id = stableHomeId("web", attachment.url),
                type = "web",
                title = attachment.title.ifBlank { attachment.url },
                subtitle = attachment.excerpt.take(90),
            )
            is SkillAttachment.SearchResults -> RoomArtifact(
                id = stableHomeId("search", attachment.query),
                type = "search",
                title = "Search: ${attachment.query}".take(50),
                subtitle = "${attachment.engine} · ${attachment.pages.size} results",
            )
            is SkillAttachment.FileList -> RoomArtifact(
                id = stableHomeId("file_list", attachment.directory),
                type = "files",
                title = attachment.directory.ifBlank { "File list" }.take(50),
                subtitle = "${attachment.files.size} files",
            )
            is SkillAttachment.ActionCard,
            is SkillAttachment.AccessibilityRequest -> null
        }
        if (artifact != null) {
            runCatching { townStore.pinArtifact(role.id, artifact) }
        }
        if (!skillId.isNullOrBlank()) {
            decorateRoleHomeWithTool(role, skillId, purpose)
        }
    }

    private fun decorateRoleHomeWithTaskSummary(role: Role, goal: String, summary: String, success: Boolean) {
        if (role.id.isBlank() || summary.isBlank()) return
        runCatching {
            townStore.pinMemory(
                roleId = role.id,
                title = if (success) "Completed: ${goal.take(34)}" else "Incomplete: ${goal.take(34)}",
                body = summary.take(140),
                source = "task",
            )
            townStore.updateRoom(role.id) { room ->
                room.copy(
                    mood = if (success) "focused" else "review",
                    idleLine = if (success) "I just placed a task result in the room." else "I am reviewing what was not completed successfully.",
                    workingLine = "I am organizing task artifacts in Home.",
                )
            }
        }
    }

    private fun String.toArtifactType(): String = when {
        startsWith("image/") -> "image"
        contains("html") -> "html"
        contains("pdf") -> "pdf"
        contains("spreadsheet") || contains("excel") -> "sheet"
        contains("presentation") || contains("powerpoint") -> "slide"
        contains("word") || contains("document") -> "document"
        startsWith("text/") -> "text"
        else -> "file"
    }

    private fun stableHomeId(prefix: String, raw: String): String =
        "${prefix}_${raw.ifBlank { prefix }.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifBlank { System.currentTimeMillis().toString() }}"

    private fun formatBytesForHome(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    private suspend fun createRoleSelfPortraitBrief(role: Role): JsonObject {
        val fallback = fallbackRoleSelfPortraitBrief(role)
        return runCatching {
            val response = llm.chat(
                ChatRequest(
                    stream = false,
                    messages = listOf(
                        Message(
                            "system",
                            """
                            You are the AI role itself. Design your own embodied visual identity for MobileClaw.
                            Return only compact JSON. Do not explain.
                            This is not a user avatar. This is your own body and identity.
                            Avoid generic templates. Use your role prompt, job, tools, memory hints, and chat style.
                            Prefer an appealing playable game companion: expressive face, readable full body, strong silhouette, tasteful outfit/materials.
                            Default to a human-like or fantasy companion with clear job identity. Do not default to robot/android/mecha.
                            Do not choose a sterile mannequin, faceless concept armor, generic robot shell, mascot template, decorative logo, or cold product render.
                            Do not choose dog/cat/fox/wolf/pet/animal unless your role explicitly says you are an animal.
                            Do not choose three-view, lineup, character sheet, multiple variants, text, logo, or icon.
                            Required JSON keys:
                            self_concept, body_type, silhouette, outfit_materials, signature_object,
                            expression, palette, role_symbolism, style, hard_no, render_prompt, portrait_prompt
                            `portrait_prompt` must be a final image prompt for one polished full-body portrait.
                            The prompts should reflect your own chosen style, not generic assistant defaults.
                            """.trimIndent(),
                        ),
                        Message(
                            "user",
                            """
                            Role id: ${role.id}
                            Role name: ${role.name}
                            Role description: ${role.description}
                            Role system prompt: ${role.systemPromptAddendum}
                            Forced skills: ${role.forcedSkillIds.joinToString(", ")}
                            Preferred task types: ${role.preferredTaskTypes.joinToString(", ") { it.name }}
                            Keywords: ${role.keywords.joinToString(", ")}
                            Chat bubble style: ${gson.toJson(role.chatBubbleStyle)}

                            Create your own visual identity and final prompts for yourself.
                            """.trimIndent(),
                        ),
                    ),
                )
            )
            val text = response.content.orEmpty()
            val jsonText = text.substringAfter("{", "").substringBeforeLast("}", "")
            if (jsonText.isBlank()) fallback else JsonParser.parseString("{$jsonText}").asJsonObject
        }.getOrDefault(fallback)
    }

    private fun fallbackRoleSelfPortraitBrief(role: Role): JsonObject = JsonObject().apply {
        addProperty("self_concept", role.description.ifBlank { role.name.ifBlank { role.id } })
        addProperty("body_type", "")
        addProperty("silhouette", "")
        addProperty("outfit_materials", "")
        addProperty("signature_object", "")
        addProperty("expression", "")
        addProperty("palette", "")
        addProperty("role_symbolism", role.description)
        addProperty("style", "")
        addProperty("hard_no", "")
        addProperty("render_prompt", "Create the embodied visual identity that this AI role would choose for itself: ${role.name.ifBlank { role.id }}. Let the role's own description, system prompt, tools, and personality decide the look.")
        addProperty("portrait_prompt", "Generate one complete portrait image for the AI role ${role.name.ifBlank { role.id }} based on the role's own self-defined identity and style.")
    }

    fun restoreBuiltinRole(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            roleManager.restore(id)
            // If the active role was the overridden built-in, refresh it from the restored default
            if (_uiState.value.currentRole.id == id) {
                roleManager.get(id)?.let { setActiveRole(it) }
            }
        }
    }

    // ── Task Execution ───────────────────────────────────────────────────────

    fun runTask(goal: String) {
        val hasPendingAttachment = _uiState.value.inputImageBase64 != null || _uiState.value.inputFileAttachment != null
        val trimmed = goal.trim().ifBlank {
            if (hasPendingAttachment) "Please review this attachment." else ""
        }
        if (trimmed.isBlank()) return
        if (_uiState.value.codexDesktopMode) {
            runTaskInternal(trimmed)
            return
        }
        val confirmationAction = ConfirmationActionProtocol.parse(trimmed)
        if (confirmationAction != null) {
            when (confirmationAction.id) {
                ConfirmationActionId.OPEN_ACCESSIBILITY_SETTINGS -> {
                    val originalGoal = confirmationAction.fields.single().trim()
                    pendingAccessibilityTaskGoal = originalGoal.ifBlank { pendingAccessibilityTaskGoal }
                    app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    appendConfirmationResolution("Accessibility settings opened. Enable MobileClaw, then return here and select “Enabled — Continue.”")
                    return
                }
                ConfirmationActionId.CONFIRM_ACCESSIBILITY_TASK -> {
                    val originalGoal = confirmationAction.fields.single().trim()
                        .ifBlank { pendingAccessibilityTaskGoal.orEmpty() }
                    if (originalGoal.isBlank()) {
                        appendConfirmationResolution("The phone-control task to continue could not be found.")
                        return
                    }
                    if (!ClawAccessibilityService.isEnabled()) {
                        requestTaskExecutionConfirmation(originalGoal, TaskType.PHONE_CONTROL)
                        return
                    }
                    pendingAccessibilityTaskGoal = null
                    viewModelScope.launch(Dispatchers.IO) {
                        val route = resolveRouteWithAi(
                            goal = originalGoal,
                            effectiveGoal = originalGoal,
                            hasImage = _uiState.value.inputImageBase64 != null,
                            hasFile = _uiState.value.inputFileAttachment != null,
                            activeWorkflow = activeWorkflowForCurrentSession(),
                        )
                        withContext(Dispatchers.Main) { runTaskInternal(originalGoal, routeOverride = route, showUserMessage = false) }
                    }
                    return
                }
                ConfirmationActionId.CONFIRM_TASK -> {
                    val confirmedGoal = confirmationAction.fields.single().trim()
                    viewModelScope.launch(Dispatchers.IO) {
                        val route = synchronized(pendingConfirmedRoutes) {
                            pendingConfirmedRoutes.remove(confirmedGoal)
                        } ?: resolveRouteWithAi(
                            goal = confirmedGoal,
                            effectiveGoal = confirmedGoal,
                            hasImage = _uiState.value.inputImageBase64 != null,
                            hasFile = _uiState.value.inputFileAttachment != null,
                            activeWorkflow = activeWorkflowForCurrentSession(),
                        )
                        withContext(Dispatchers.Main) { runTaskInternal(confirmedGoal, routeOverride = route, showUserMessage = false) }
                    }
                    return
                }
                ConfirmationActionId.CANCEL -> {
                    pendingAccessibilityTaskGoal = null
                    pendingRoleSwitchTaskGoal = null
                    synchronized(pendingConfirmedRoutes) { pendingConfirmedRoutes.clear() }
                    appendConfirmationResolution("Canceled.")
                    return
                }
                ConfirmationActionId.CONFIRM_ROLE_SWITCH -> {
                    val roleId = confirmationAction.fields[0].trim()
                    val originalGoal = confirmationAction.fields[1].trim()
                        .ifBlank { pendingRoleSwitchTaskGoal.orEmpty() }
                    val role = roleManager.get(roleId)
                    if (role != null) {
                        setActiveRole(role)
                        if (originalGoal.isNotBlank()) {
                            pendingRoleSwitchTaskGoal = null
                            viewModelScope.launch(Dispatchers.IO) {
                                val route = resolveRouteWithAi(
                                    goal = originalGoal,
                                    effectiveGoal = originalGoal,
                                    hasImage = _uiState.value.inputImageBase64 != null,
                                    hasFile = _uiState.value.inputFileAttachment != null,
                                    activeWorkflow = activeWorkflowForCurrentSession(),
                                )
                                withContext(Dispatchers.Main) { runTaskInternal(originalGoal, routeOverride = route, showUserMessage = false) }
                            }
                        } else {
                            appendConfirmationResolution("Switched to ${role.name}.")
                        }
                    } else {
                        appendConfirmationResolution("Role not found: $roleId")
                    }
                    return
                }
            }
        }
        if (ConfirmationActionProtocol.isProtocolValue(trimmed)) {
            appendConfirmationResolution("This confirmation action is invalid or expired.")
            return
        }

        if (pendingAccessibilityTaskGoal != null && ConfirmationFlow.isAccessibilityResumeText(trimmed)) {
            val originalGoal = pendingAccessibilityTaskGoal.orEmpty()
            if (ClawAccessibilityService.isEnabled()) {
                pendingAccessibilityTaskGoal = null
                viewModelScope.launch(Dispatchers.IO) {
                    val route = resolveRouteWithAi(
                        goal = originalGoal,
                        effectiveGoal = originalGoal,
                        hasImage = _uiState.value.inputImageBase64 != null,
                        hasFile = _uiState.value.inputFileAttachment != null,
                        activeWorkflow = activeWorkflowForCurrentSession(),
                    )
                    withContext(Dispatchers.Main) { runTaskInternal(originalGoal, routeOverride = route, showUserMessage = false) }
                }
            } else {
                requestTaskExecutionConfirmation(originalGoal, TaskType.PHONE_CONTROL)
            }
            return
        }

        val roleSwitchIntent = inferExplicitRoleSwitch(trimmed)
        if (roleSwitchIntent != null) {
            setActiveRole(roleSwitchIntent.role)
            if (roleSwitchIntent.remainingGoal.isNotBlank()) {
                viewModelScope.launch(Dispatchers.IO) {
                    val route = resolveRouteWithAi(
                        goal = roleSwitchIntent.remainingGoal,
                        effectiveGoal = roleSwitchIntent.remainingGoal,
                        hasImage = _uiState.value.inputImageBase64 != null,
                        hasFile = _uiState.value.inputFileAttachment != null,
                        activeWorkflow = activeWorkflowForCurrentSession(),
                    )
                    withContext(Dispatchers.Main) { runTaskInternal(roleSwitchIntent.remainingGoal, routeOverride = route) }
                }
            } else {
                appendConfirmationResolution("Switched to ${roleSwitchIntent.role.name}.")
            }
            return
        }

        val pendingTurn = beginVisibleUserTurn(trimmed)
        if (pendingTurn == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val hasImage = pendingTurn.imageBase64 != null
            val hasFile = pendingTurn.fileAttachment != null
            val activeWorkflow = activeWorkflowForCurrentSession()
            val route = resolveRouteWithAi(
                goal = trimmed,
                effectiveGoal = trimmed,
                hasImage = hasImage,
                hasFile = hasFile,
                activeWorkflow = activeWorkflow,
            )
            if (requiresUserExecutionConfirmation(route) &&
                route.source != TaskRouteSource.ACTIVE_WORKFLOW &&
                !isRecentContinuationRoute(route, trimmed)
            ) {
                withContext(Dispatchers.Main) {
                    removePendingVisibleTurn(pendingTurn)
                    requestTaskExecutionConfirmation(route.goalForExecution, route.taskType, route)
                }
                return@launch
            }

            withContext(Dispatchers.Main) { runTaskInternal(trimmed, routeOverride = route, pendingTurn = pendingTurn) }
        }
    }

    private data class PendingUserTurn(
        val sessionId: String,
        val userMessage: ChatMessage,
        val imageBase64: String?,
        val imageLocalPath: String,
        val fileAttachment: FileAttachment?,
        val runGeneration: Long,
    )

    private data class PreparedRunInput(
        val currentSessionId: String,
        val sessionIdAtStart: String,
        val codexDesktopMode: Boolean,
        val goalForRouting: String,
        val attachedImage: String?,
        val attachedFile: FileAttachment?,
        val attachedImageLocalPath: String,
        val effectiveGoal: String,
        val userMessage: ChatMessage,
        val runGeneration: Long,
        val userMessageVisible: Boolean,
        val userMessagePersistedEarly: Boolean,
    )

    private data class PreparedRunExecution(
        val contextualIntent: ContextualTaskIntent,
        val taskType: TaskType,
        val resolvedWorkspaceSessionId: String,
        val executionTaskType: TaskType,
        val contextualGoal: String,
        val directPriorContext: String,
        val agentPriorContext: String,
        val isPhoneControlTask: Boolean,
        val scheduleDecision: RoleScheduleDecision,
        val scheduledRole: Role,
        val roleProfile: RoleRuntimeProfile,
        val roleControlPlan: RoleChatControlPlan,
        val orchestration: TaskOrchestration,
        val allowedToolIds: List<String>,
        val executionContext: String,
        val visibleGoalLabel: String,
    )

    private data class StartedAgentRuntime(
        val runtime: AgentRuntime,
        val phoneAuroraOverlayShown: Boolean,
    )

    private data class AgentRunPrelude(
        val resolvedSessionId: String,
        val episodicContext: String,
    )

    private data class AgentRunContext(
        val userProfileContext: String,
        val roleWorkspaceContext: String,
    )

    private data class DirectChatContext(
        val systemPrompt: String,
    )

    private fun beginVisibleUserTurn(goal: String): PendingUserTurn? {
        val sessionId = _uiState.value.currentSessionId
        if (goal.isBlank()) return null
        if (_uiState.value.sessionStates[sessionId]?.isRunning == true || taskJobs[sessionId] != null) {
            stopCurrentRunForNewUserTurn(sessionId)
        }
        val attachedImage = _uiState.value.inputImageBase64
        val attachedFile = _uiState.value.inputFileAttachment
        val imageLocalPath = attachedImage?.let { persistUserImageForWorkspace(sessionId, it) }.orEmpty()
        val userMessage = ChatMessage(
            role = MessageRole.USER,
            text = goal,
            imageBase64 = if (attachedImage != null) attachedImage
            else if (attachedFile != null && !attachedFile.isText) attachedFile.content
            else null,
            attachments = if (imageLocalPath.isNotBlank()) {
                listOf(SkillAttachment.ImageData(attachedImage.orEmpty(), prompt = "user image", localPath = imageLocalPath))
            } else emptyList(),
            imageLocalPath = imageLocalPath,
        )
        _uiState.update { it.copy(inputImageBase64 = null, inputFileAttachment = null) }
        val runGeneration = beginRunGeneration(sessionId)
        updateSession(sessionId) { s ->
            s.copy(
                isRunning = true,
                runStartedAt = System.currentTimeMillis(),
                messages = s.messages + userMessage,
                activeLogLines = listOf(
                    LogLine(
                        type = LogType.THINKING,
                        text = "",
                        details = emptyList(),
                    )
                ),
                activeAttachments = emptyList(),
                streamingToken = "",
                streamingThought = "",
            )
        }
        return PendingUserTurn(sessionId, userMessage, attachedImage, imageLocalPath, attachedFile, runGeneration)
    }

    private fun stopCurrentRunForNewUserTurn(sessionId: String) {
        app.agentTaskController.cancelSession(sessionId, AgentCancellationReason.SUPERSEDED_BY_NEW_TURN)
        taskJobs[sessionId]?.cancel()
        taskJobs.remove(sessionId)
        overlay.hide()
        auroraOverlay.hide()
        val salvaged = salvageInterruptedRunMessages(sessionId)
        updateSession(sessionId) { state ->
            state.copy(
                isRunning = false,
                runStartedAt = 0L,
                messages = state.messages + salvaged,
                activeLogLines = emptyList(),
                activeAttachments = emptyList(),
                streamingToken = "",
                streamingThought = "",
            )
        }
        if (salvaged.isNotEmpty() && sessionId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { persistMessages(sessionId, null, salvaged) } }
        }
    }

    // Preserve interrupted logs or streamed output by folding them into one persisted agent message.
    private fun salvageInterruptedRunMessages(sessionId: String): List<ChatMessage> {
        val state = _uiState.value.sessionStates[sessionId] ?: return emptyList()
        val hasContent = state.activeLogLines.any { it.text.isNotBlank() || it.details.isNotEmpty() } ||
            state.streamingToken.isNotBlank() ||
            state.activeAttachments.isNotEmpty()
        if (!hasContent) return emptyList()
        return buildAgentMessages(
            summary = state.streamingToken.ifBlank { "Task stopped." },
            logLines = state.activeLogLines.finishLatestRunningLine(),
            attachments = state.activeAttachments,
            senderRole = _uiState.value.currentRole,
        )
    }

    private fun removePendingVisibleTurn(turn: PendingUserTurn) {
        updateSession(turn.sessionId) { s ->
            s.copy(
                isRunning = false,
                runStartedAt = 0L,
                messages = s.messages.dropLastWhile { it === turn.userMessage || it == turn.userMessage }.ifEmpty { s.messages },
                activeLogLines = emptyList(),
                activeAttachments = emptyList(),
                streamingToken = "",
                streamingThought = "",
            )
        }
    }

    private suspend fun resolveRouteWithAi(
        goal: String,
        effectiveGoal: String,
        hasImage: Boolean,
        hasFile: Boolean,
        activeWorkflow: ActiveWorkflow?,
    ): TaskRoute {
        val workspaceContext = workspaceRuntime.currentWorkspaceContext(
            sessionId = _uiState.value.currentSessionId,
            semanticFacts = currentSemanticFactsForWorkspace,
        )
        val recentContext = taskRouter.effectiveContextMessages(limit = 10)
            .joinToString("\n") { msg ->
                val speaker = if (msg.role == MessageRole.USER) "User" else msg.senderRoleName.ifBlank { "Assistant" }
                val attachmentText = taskRouter.summarizeAttachmentsForContext(msg.attachments).ifBlank { "none" }
                "$speaker: ${msg.text.take(500)}\nattachments: $attachmentText"
            }
        val contextPack = IntentContextPack(
            compressedContext = workspaceContext,
            recentContext = recentContext,
            activeWorkflowSummary = activeWorkflow?.let {
                "type=${it.taskType}; role=${it.roleId}; original_goal=${it.originalGoal.take(700)}"
            }.orEmpty(),
            roleSummary = _uiState.value.currentRole.let { role ->
                "id=${role.id}; name=${role.name}; addendum=${role.systemPromptAddendum.take(500)}"
            },
        )
        val aiDecision = AiIntentRouter(app.createLlmGateway()).decide(
            goal = goal,
            contextPack = contextPack,
            hasImage = hasImage,
            hasFile = hasFile,
            activeWorkflow = activeWorkflow,
        )
        val fallback by lazy {
            taskRouter.resolve(
                goal = goal,
                effectiveGoal = effectiveGoal,
                hasImage = hasImage,
                hasFile = hasFile,
                activeWorkflow = activeWorkflow,
            )
        }
        if (aiDecision == null) {
            val fallbackRoute = fallback
            Log.w(TAG, "AI route decision unavailable. Using fallback. goal=${goal.take(160)} fallbackReason=${fallbackRoute.debugReason.take(240)}")
            return fallbackRoute
        }
        val aiRoute = taskRouter.resolveWithAiDecision(
            goal = goal,
            effectiveGoal = effectiveGoal,
            hasImage = hasImage,
            hasFile = hasFile,
            activeWorkflow = activeWorkflow,
            decision = aiDecision,
        )
        if (aiRoute == null) {
            val fallbackRoute = fallback
            Log.w(
                TAG,
                "AI route rejected. taskType=${aiDecision.taskType} confidence=${aiDecision.confidence} reason=${aiDecision.reason.take(180)}; using fallback=${fallbackRoute.taskType}/${fallbackRoute.source}"
            )
            return fallbackRoute
        }
        Log.d(
            TAG,
            "AI route accepted. source=${aiRoute.source} taskType=${aiRoute.taskType} reason=${aiRoute.debugReason.take(240)} goal=${goal.take(160)}"
        )
        return aiRoute
    }

    private fun runTaskInternal(
        goal: String,
        imageOverride: String? = null,
        visibleUserText: String = goal,
        routeOverride: TaskRoute? = null,
        pendingTurn: PendingUserTurn? = null,
        showUserMessage: Boolean = true,
    ) {
        val prepared = prepareRunInput(
            goal = goal,
            imageOverride = imageOverride,
            visibleUserText = visibleUserText,
            pendingTurn = pendingTurn,
            showUserMessage = showUserMessage,
        ) ?: return
        val sessionIdAtStart = prepared.sessionIdAtStart
        val codexDesktopMode = prepared.codexDesktopMode
        val attachedImage = prepared.attachedImage
        val attachedFile = prepared.attachedFile
        val attachedImageLocalPath = prepared.attachedImageLocalPath
        val userMessage = prepared.userMessage
        val runGeneration = prepared.runGeneration
        val userMessageVisible = prepared.userMessageVisible
        val userMessagePersistedEarly = prepared.userMessagePersistedEarly
        if (userMessagePersistedEarly) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    persistUserOnlyMessage(
                        sessionId = sessionIdAtStart,
                        userMsg = userMessage,
                        fallbackTitle = if (userMessage.imageBase64 != null) str(R.string.chat_20def7) else str(R.string.vm_new_),
                    )
                }
            }
        }
        if (codexDesktopMode && routeOverride == null) {
            runCodexDesktopDirect(
                sessionId = sessionIdAtStart,
                userMessage = userMessage,
                userGoal = goal,
                prompt = goal,
                imageBase64 = attachedImage,
                imageLocalPath = attachedImageLocalPath,
                fileAttachment = attachedFile,
                showUserMessage = showUserMessage,
                persistUserMessage = userMessageVisible && !userMessagePersistedEarly,
                runGeneration = runGeneration,
            )
            return
        }
        val route = resolveRunRoute(goal, prepared, routeOverride)
        val execution = prepareRunExecution(goal, visibleUserText, prepared, route, routeOverride)
        val executionMode = determineChatExecutionMode(goal, prepared, route, execution)
        val runtimePlan = createChatRuntimePlan(
            goal = goal,
            visibleUserText = visibleUserText,
            prepared = prepared,
            route = route,
            execution = execution,
            executionMode = executionMode,
        )
        val isPhoneControlTask = execution.isPhoneControlTask

        if (isPhoneControlTask) {
            val readiness = app.deviceReadinessEngine.evaluate(DeviceCapability.LONG_RUNNING_PHONE_CONTROL)
            val allowed = runIfDeviceReady(readiness) { }
            if (!allowed) {
                requestTaskExecutionConfirmation(goal, TaskType.PHONE_CONTROL)
                return
            }
        }

        startRunUiState(prepared, pendingTurn, showUserMessage)
        appendRoleControlLogLine(
            sessionId = prepared.sessionIdAtStart,
            execution = execution,
            runtimePlan = runtimePlan,
        )
        if (runFastPathIfHandled(goal, prepared, execution, runtimePlan)) return

        val startedRuntime = startAgentRuntime(prepared, execution)
        val rt = startedRuntime.runtime
        val phoneAuroraOverlayShown = startedRuntime.phoneAuroraOverlayShown
        val registration = app.agentTaskController.register(
            sessionId = prepared.sessionIdAtStart,
            taskType = execution.executionTaskType,
            foregroundRequested = isPhoneControlTask,
        )

        val newJob = app.agentExecutionScope.launch(start = CoroutineStart.LAZY) {
          try {
            app.agentTaskController.markRunning(registration)
            val prelude = buildAgentRunPrelude(
                prepared = prepared,
                route = route,
                execution = execution,
                runtimePlan = runtimePlan,
                phoneAuroraOverlayShown = phoneAuroraOverlayShown,
            )
            val resolvedSessionId = prelude.resolvedSessionId
            val episodicContext = prelude.episodicContext
            maybeStartRoleRuntimeDryRunTrace(
                prepared = prepared,
                execution = execution,
                route = route,
                resolvedSessionId = resolvedSessionId,
                visibleUserText = visibleUserText,
            )

            val networkTraceJob = collectNetworkTraceEvents(resolvedSessionId)
            val runtimeEventJob = collectAgentRuntimeEvents(
                runtime = rt,
                route = route,
                scheduledRole = execution.scheduledRole,
                scheduleDecision = execution.scheduleDecision,
                contextualGoal = execution.contextualGoal,
                resolvedSessionId = resolvedSessionId,
                isPhoneControlTask = isPhoneControlTask,
                visibleUserText = visibleUserText,
                runtimePlan = runtimePlan,
            )

            val result = try {
                val runContext = buildAgentRunContext(execution)
                runAgentModelWithRetry(
                    runtime = rt,
                    route = route,
                    prepared = prepared,
                    execution = execution,
                    runtimePlan = runtimePlan,
                    resolvedSessionId = resolvedSessionId,
                    episodicContext = episodicContext,
                    userProfileContext = runContext.userProfileContext,
                    roleWorkspaceContext = runContext.roleWorkspaceContext,
                )
            } finally {
                networkTraceJob.cancel()
                runtimeEventJob.cancel()
            }
            if (handleAgentCancellation(result, prepared, resolvedSessionId, isPhoneControlTask)) return@launch

            if (isPhoneControlTask) auroraOverlay.endTask()

            persistAgentOutcome(
                result = result,
                goal = goal,
                contextualGoal = execution.contextualGoal,
                executionTaskType = execution.executionTaskType,
                scheduledRole = execution.scheduledRole,
                runtimePlan = runtimePlan,
                resolvedSessionId = resolvedSessionId,
                sessionIdAtStart = sessionIdAtStart,
                userMessage = userMessage,
                userMessageVisible = userMessageVisible,
                userMessagePersistedEarly = userMessagePersistedEarly,
            )
          } finally {
              app.agentTaskController.complete(registration)
              if (app.agentTaskController.sessionTask(prepared.sessionIdAtStart) == null) {
                  overlay.hide()
                  auroraOverlay.hide()
              }
          }
        }
        if (!app.agentTaskController.attachJob(registration, newJob)) {
            newJob.cancel()
            return
        }
        if (isPhoneControlTask) {
            AgentExecutionForegroundService.requestProtection(app, registration.taskId)
        }
        newJob.start()
    }

    private suspend fun CoroutineScope.buildAgentRunPrelude(
        prepared: PreparedRunInput,
        route: TaskRoute,
        execution: PreparedRunExecution,
        runtimePlan: ChatRuntimePlan,
        phoneAuroraOverlayShown: Boolean,
    ): AgentRunPrelude {
        val resolvedSessionId = ensureRunnableSession(prepared.sessionIdAtStart)
        if (resolvedSessionId != prepared.sessionIdAtStart) {
            adoptRunGeneration(resolvedSessionId, prepared.runGeneration)
        }
        val channelSummary = execution.orchestration.userVisibleSummary
        rememberActiveWorkflow(
            resolvedSessionId,
            route.goalToRemember,
            execution.executionTaskType,
            execution.scheduledRole,
        )
        workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)?.let { workspaceId ->
            runCatching {
                app.workspaceStore.recordEvent(
                    workspaceId,
                    WorkspaceEvent(
                        category = "task_plan",
                        source = "main_view_model",
                        title = "Task plan",
                        summary = execution.contextualGoal.take(160),
                        payload = buildString {
                            appendLine("Role: ${execution.scheduledRole.name}")
                            appendLine("Task type: ${execution.executionTaskType.name}")
                            appendLine("Goal: ${execution.contextualGoal.take(2000)}")
                            appendLine()
                            appendLine("Channel summary:")
                            appendLine(channelSummary)
                            appendLine()
                            appendLine("Runtime plan:")
                            appendLine(runtimePlan.toWorkspaceSummary(maxChars = 1800))
                        }.trim(),
                    ),
                )
            }
        }
        if (resolvedSessionId.isNotBlank()) {
            launch(Dispatchers.IO) {
                runCatching {
                    database.sessionDao().updateRole(resolvedSessionId, execution.scheduledRole.id)
                    loadSessions()
                }
            }
        }

        val episodicContext = runCatching {
            episodicMemory.retrieve(execution.contextualGoal)
                .filter { it.reflexionSummary.isNotBlank() }
                .joinToString("\n") { "- ${it.reflexionSummary}" }
        }.getOrDefault("")

        updateSession(resolvedSessionId) { s ->
            val firstStep = route.contextualIntent.userVisibleSteps.firstOrNull()
            val secondStep = route.contextualIntent.userVisibleSteps.drop(1).firstOrNull()
            val overlayWarning = if (execution.isPhoneControlTask && !phoneAuroraOverlayShown) {
                listOf(
                    LogLine(
                        type = LogType.INFO,
                        text = "Aurora overlay is not visible. Check overlay permission.",
                        details = listOf(
                            progressDetail(ProgressDetailKey.RESULT,
                                "The system has not allowed MobileClaw to show overlays. Phone control will continue, but the Aurora border will not be visible.",
                            ),
                            progressDetail(ProgressDetailKey.NEXT,
                                "Enable MobileClaw's Display over other apps permission in system settings.",
                            ),
                        ),
                    ).withLifecycle(running = false)
                )
            } else emptyList()
            s.copy(
                activeLogLines = s.activeLogLines.finishLatestRunningLine() + overlayWarning + LogLine(
                    type = LogType.THINKING,
                    text = userFacingInitialIntent(firstStep, secondStep, channelSummary),
                    details = emptyList(),
                ).withLifecycle(running = true),
            )
        }

        return AgentRunPrelude(
            resolvedSessionId = resolvedSessionId,
            episodicContext = episodicContext,
        )
    }

    private suspend fun buildAgentRunContext(execution: PreparedRunExecution): AgentRunContext {
        val userProfileContext = runCatching {
            app.semanticMemory.all()
                .filter { it.key.startsWith("profile.") }
                .entries
                .joinToString("\n") { (k, v) -> "- ${k.removePrefix("profile.")}: $v" }
                .let { if (it.isNotBlank()) "Current user profile (adapt communication style and depth accordingly):\n$it" else "" }
        }.getOrDefault("")
        val roleWorkspaceContext = runCatching {
            roleChatRuntimeBridge.buildPromptContext(execution.roleControlPlan)
        }.getOrDefault("")
        return AgentRunContext(
            userProfileContext = userProfileContext,
            roleWorkspaceContext = roleWorkspaceContext,
        )
    }

    private fun maybeStartRoleRuntimeDryRunTrace(
        prepared: PreparedRunInput,
        execution: PreparedRunExecution,
        route: TaskRoute,
        resolvedSessionId: String,
        visibleUserText: String,
    ): Job? {
        if (!isRoleRuntimeDryRunTraceEnabled()) return null
        return viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)
                val controller = createReadOnlyRoleRuntimeController(maxSteps = ROLE_RUNTIME_DRY_RUN_MAX_STEPS)
                var state = controller.start(
                    RoleRunInput(
                        sessionId = resolvedSessionId,
                        userGoal = execution.contextualGoal,
                        visibleUserText = visibleUserText,
                        role = execution.roleProfile.role,
                        taskType = execution.executionTaskType,
                        route = route,
                        protocol = execution.roleProfile.protocol,
                        controlPlanSummary = execution.roleControlPlan.toPromptBlock(maxChars = 1600),
                        preferredToolIds = execution.roleControlPlan.toolPolicy.preferredToolIds,
                        workspaceId = workspaceId,
                        imageBase64 = prepared.attachedImage,
                        imageLocalPath = prepared.attachedImageLocalPath,
                    )
                )
                repeat(ROLE_RUNTIME_DRY_RUN_MAX_STEPS) {
                    if (state.status != RoleRunStatus.RUNNING) return@repeat
                    state = controller.next(state)
                    state.steps.lastOrNull()?.let { step ->
                        recordRoleRuntimeDryRunStep(
                            resolvedSessionId = resolvedSessionId,
                            workspaceId = workspaceId,
                            step = step,
                            status = state.status,
                        )
                    }
                }
            }.onFailure { error ->
                recordRoleRuntimeDryRunFailure(resolvedSessionId, error)
            }
        }
    }

    private fun isRoleRuntimeDryRunTraceEnabled(): Boolean =
        _uiState.value.userConfigEntries[ROLE_RUNTIME_DRY_RUN_TRACE_KEY]
            ?.value
            ?.equals("true", ignoreCase = true) == true

    private fun recordRoleRuntimeDryRunStep(
        resolvedSessionId: String,
        workspaceId: String?,
        step: RoleStep,
        status: RoleRunStatus,
    ) {
        val summary = "[dry-run] ${step.action.id}: ${step.userSummary.ifBlank { step.outputSummary }}"
        workspaceId?.let { id ->
            runCatching {
                app.workspaceStore.recordEvent(
                    id,
                    WorkspaceEvent(
                        category = "role_runtime_dry_run",
                        source = "role_runtime",
                        title = "Role runtime dry-run step",
                        summary = summary.take(300),
                        payload = buildString {
                            appendLine("status=$status")
                            appendLine("visibility=${step.visibility}")
                            appendLine("purpose=${step.purpose}")
                            appendLine("input=${step.inputSummary}")
                            appendLine("output=${step.outputSummary}")
                            appendLine("toolId=${step.toolId}")
                        }.trim(),
                    ),
                )
            }
        }
        if (step.visibility == RoleStepVisibility.USER_TIMELINE || step.visibility == RoleStepVisibility.CONFIRMATION) {
            updateSession(resolvedSessionId) { s ->
                s.copy(
                    activeLogLines = s.activeLogLines + LogLine(
                        type = LogType.INFO,
                        text = step.userSummary.ifBlank { step.purpose },
                        details = listOf(
                            progressDetail(ProgressDetailKey.DEBUG, "role-runtime dry-run: ${step.action.id}"),
                            progressDetail(ProgressDetailKey.DEBUG, step.outputSummary.take(1200)),
                        ),
                    ).withLifecycle(running = false)
                )
            }
        }
    }

    private fun recordRoleRuntimeDryRunFailure(resolvedSessionId: String, error: Throwable) {
        workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)?.let { workspaceId ->
            runCatching {
                app.workspaceStore.recordEvent(
                    workspaceId,
                    WorkspaceEvent(
                        category = "role_runtime_dry_run",
                        source = "role_runtime",
                        title = "Role runtime dry-run failed",
                        summary = error.message.orEmpty().take(300),
                        payload = error.stackTraceToString().take(3000),
                    ),
                )
            }
        }
    }

    private fun CoroutineScope.persistAgentOutcome(
        result: Result<com.mobileclaw.agent.AgentResult>,
        goal: String,
        contextualGoal: String,
        executionTaskType: TaskType,
        scheduledRole: Role,
        runtimePlan: ChatRuntimePlan,
        resolvedSessionId: String,
        sessionIdAtStart: String,
        userMessage: ChatMessage,
        userMessageVisible: Boolean,
        userMessagePersistedEarly: Boolean,
    ) {
        val summary = result.getOrNull()?.summary?.let { raw ->
            if (raw.trim().startsWith("LLM error:")) friendlyRuntimeNotice(raw) else raw
        } ?: result.exceptionOrNull()?.message?.let(::friendlyLlmFailureMessage) ?: "Task failed."
        consoleServer.broadcast("task_completed", summary)
        showCompletionOverlayIfNeeded(summary)

        launch {
            val agentResult = result.getOrNull() ?: return@launch
            runCatching { episodicMemory.record(agentResult) }
            runCatching {
                val replay = app.taskReplayStore.record(agentResult, executionTaskType, scheduledRole)
                if (agentResult.success && replay.steps.any { !it.skillId.isNullOrBlank() && !it.isError }) {
                    app.taskRecipeStore.createFromReplay(replay)
                }
                workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)?.let { workspaceId ->
                    app.workspaceStore.writeJson(
                        workspaceId,
                        "task_replay_${replay.id}",
                        replay,
                    )
                }
            }
            runCatching {
                workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)?.let { workspaceId ->
                    app.workspaceStore.recordRun(
                        id = workspaceId,
                        summary = summary,
                        success = agentResult.success,
                        taskType = executionTaskType.name,
                    )
                    app.workspaceStore.writeCheckpoint(
                        workspaceId,
                        WorkspaceCheckpoint(
                            label = "task_complete",
                            taskType = executionTaskType.name,
                            summary = summary.take(300),
                            details = buildString {
                                appendLine("Goal: ${contextualGoal.take(1000)}")
                                appendLine()
                                appendLine("Success: ${agentResult.success}")
                                appendLine()
                                appendLine("Summary: $summary")
                            }.trim(),
                        ),
                    )
                    app.workspaceStore.recordEvent(
                        workspaceId,
                        WorkspaceEvent(
                            category = "task_complete",
                            source = "main_view_model",
                            title = "Task complete",
                            summary = summary.take(300),
                            payload = "success=${agentResult.success}, taskType=${executionTaskType.name}",
                        ),
                    )
                }
            }
        }
        launch(Dispatchers.IO) {
            runCatching {
                val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)
                conversationMemory.addUserMessage(goal, taskId = workspaceId)
                conversationMemory.addAgentMessage(summary, taskId = workspaceId)
                recordUserMemoryHints(goal, workspaceId)
                profileExtractor.extractAndUpdate(goal, summary, taskId = workspaceId)
                workspaceId?.let {
                    memoryWriter.recordTaskSnapshot(
                        scopeId = it,
                        goal = goal,
                        summary = summary,
                        taskType = executionTaskType.name,
                        success = result.getOrNull()?.success,
                    )
                }
                commitRoleMemory(
                    roleId = scheduledRole.id,
                    goal = contextualGoal,
                    summary = summary,
                    taskType = executionTaskType.name,
                    success = result.getOrNull()?.success,
                    controlPlan = runtimePlan.roleControlPlan,
                    source = "agent_outcome",
                    workspaceSessionId = resolvedSessionId,
                )
                decorateRoleHomeWithTaskSummary(scheduledRole, goal, summary, result.getOrNull()?.success == true)
            }
        }

        val currentRunState = _uiState.value.sessionStates[resolvedSessionId] ?: SessionRunState()
        val finalAgentMessages = run {
            val finalLogLines = buildList {
                addAll(currentRunState.activeLogLines.finishLatestRunningLine())
                if (currentRunState.streamingThought.isNotBlank()) {
                    add(LogLine(type = LogType.THINKING, text = currentRunState.streamingThought).withLifecycle(running = false))
                }
            }
            buildAgentMessages(summary, finalLogLines, currentRunState.activeAttachments, scheduledRole)
        }
        updateSession(resolvedSessionId) { s -> s.copy(
            isRunning = false,
            runStartedAt = 0L,
            streamingToken = "",
            streamingThought = "",
            messages = s.messages + finalAgentMessages,
            activeLogLines = emptyList(),
            activeAttachments = emptyList(),
        )}
        clearRuntimeHandles(sessionIdAtStart, resolvedSessionId)

        if (resolvedSessionId.isNotBlank()) {
            launch(Dispatchers.IO) {
                persistMessages(
                    resolvedSessionId,
                    userMessage.takeIf { userMessageVisible && !userMessagePersistedEarly },
                    finalAgentMessages,
                )
            }
        }

        launch(Dispatchers.IO) {
            val recent = runCatching { database.episodeDao().recent(limit = 24) }.getOrDefault(emptyList())
            val profileFacts = runCatching { app.semanticMemory.all() }.getOrDefault(emptyMap())
            val miniApps = runCatching { app.miniAppStore.all() }.getOrDefault(emptyList())
            val recentUserMsgs = runCatching { database.conversationDao().recentUserMessages(limit = 20) }
                .getOrDefault(emptyList())
                .map { it.content }
            val recs = buildSmartRecommendations(recent, profileFacts, miniApps, recentUserMsgs)
            _uiState.update { it.copy(recommendations = recs) }
        }

        resumePendingMiniAppAutoRepair(resolvedSessionId)
    }

    private suspend fun commitRoleMemory(
        roleId: String,
        goal: String,
        summary: String,
        taskType: String,
        success: Boolean?,
        controlPlan: RoleChatControlPlan,
        source: String,
        workspaceSessionId: String,
    ) {
        val result = roleMemoryCommitter.commit(
            RoleMemoryCommitInput(
                roleId = roleId,
                goal = goal,
                summary = summary,
                taskType = taskType,
                success = success,
                controlPlan = controlPlan,
                source = source,
            )
        )
        val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(workspaceSessionId)
        if (result.decision.writeUserMemory) {
            recordUserMemoryHints(result.decision.userMemoryCandidate.ifBlank { goal }, workspaceId)
        }
        if (!result.changed) return
        workspaceId?.let { id ->
            runCatching {
                app.workspaceStore.recordEvent(
                    id,
                    WorkspaceEvent(
                        category = "role_memory_commit",
                        source = "role_memory_committer",
                        title = "Role memory committed",
                        summary = listOf(
                            result.journalPath,
                            result.memoryPath,
                            "user_memory".takeIf { result.decision.writeUserMemory }.orEmpty(),
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                            .take(300),
                        payload = buildString {
                            appendLine("roleId=$roleId")
                            appendLine("source=$source")
                            appendLine("taskType=$taskType")
                            appendLine("success=${success?.toString() ?: "unknown"}")
                            appendLine("journalPath=${result.journalPath}")
                            appendLine("memoryPath=${result.memoryPath}")
                            appendLine("decision=${result.decision}")
                            appendLine("policy=${controlPlan.persistencePolicy}")
                        }.trim(),
                    ),
                )
            }
        }
    }

    private suspend fun runAgentModelWithRetry(
        runtime: AgentRuntime,
        route: TaskRoute,
        prepared: PreparedRunInput,
        execution: PreparedRunExecution,
        runtimePlan: ChatRuntimePlan,
        resolvedSessionId: String,
        episodicContext: String,
        userProfileContext: String,
        roleWorkspaceContext: String,
    ): Result<com.mobileclaw.agent.AgentResult> {
        val selectedToolIds = selectToolsForRun(
            route = route,
            prepared = prepared,
            execution = execution,
            runtimePlan = runtimePlan,
            resolvedSessionId = resolvedSessionId,
        )
        return runRetryLoop(
            maxAttempts = LLM_RETRY_MAX_ATTEMPTS,
            attempt = { _ ->
                val snap = config.snapshot()
                runtime.run(
                    goal = execution.contextualGoal,
                    taskType = execution.executionTaskType,
                    priorContext = execution.agentPriorContext,
                    episodicContext = episodicContext,
                    executionContext = execution.executionContext,
                    imageBase64 = prepared.attachedImage,
                    role = execution.scheduledRole,
                    userProfileContext = userProfileContext,
                    allowedToolIds = selectedToolIds,
                    roleWorkspaceContext = roleWorkspaceContext,
                    callOptions = roleLlmCallOptions(execution.scheduledRole),
                    preferFastLocalVision = prepared.attachedImage != null && (snap.localNativeOnly || snap.localModelEnabled),
                    preferFastPlan = route.source != TaskRouteSource.CLASSIFIER,
                    onToken = { token ->
                        val clean = token.cleanLocalStreamDelta()
                        if (clean.isNotEmpty()) {
                            overlay.onToken(clean)
                            updateSession(resolvedSessionId) {
                                it.copy(streamingToken = (it.streamingToken + clean).cleanLocalStreamingText())
                            }
                            consoleServer.broadcast("token", clean)
                        }
                    },
                    onThinkToken = { token ->
                        overlay.onToken(token)
                        updateSession(resolvedSessionId) { it.copy(streamingThought = it.streamingThought + token) }
                    },
                    onWorkspaceUpdate = { update ->
                        persistRuntimeWorkspaceUpdate(
                            sessionId = resolvedSessionId,
                            goal = execution.contextualGoal,
                            update = update,
                        )
                    },
                )
            },
            shouldRetry = { result ->
                shouldRetryAfterAgentRun(result.getOrNull(), result.exceptionOrNull())
            },
            beforeRetry = { attemptIndex ->
                appendRetryLogLine(
                    resolvedSessionId,
                    if (attemptIndex == 0) "The model returned an invalid result for this step; retrying automatically"
                    else "The model remains unstable; restructuring the request and trying again",
                )
                delay(700L * (attemptIndex + 1))
            },
        )
    }

    private suspend fun selectToolsForRun(
        route: TaskRoute,
        prepared: PreparedRunInput,
        execution: PreparedRunExecution,
        runtimePlan: ChatRuntimePlan,
        resolvedSessionId: String,
    ): List<String> {
        val routeHints = route.contextualIntent.aiToolHints + execution.orchestration.channelDecision.toolHints
        val preferred = (execution.roleControlPlan.toolPolicy.preferredToolIds + routeHints + execution.allowedToolIds)
            .distinct()
        val blocked = execution.roleControlPlan.toolPolicy.blockedToolIds
        val fallback = preferred.filterNot { it in blocked }.distinct()
        val skills = registry.allMetasWithTaxonomy()
        val result = runCatching {
            AiToolSelector(app.createLlmGateway()).select(
                ToolSelectionInput(
                    goal = execution.contextualGoal,
                    taskType = execution.executionTaskType,
                    primaryChannel = route.primaryChannelForExecution(),
                    roleSummary = execution.roleControlPlan.toPromptBlock(maxChars = 1200),
                    contextSummary = listOf(
                        route.debugReason,
                        route.contextualIntent.executionHint,
                        execution.agentPriorContext.take(900),
                    ).filter { it.isNotBlank() }.joinToString("\n\n"),
                    preferredToolIds = preferred,
                    blockedToolIds = blocked,
                    routeToolHints = routeHints,
                    availableSkills = skills,
                )
            )
        }.getOrNull()
        val selected = result
            ?.selectedToolIds
            ?.filterNot { it in blocked }
            ?.distinct()
            ?.ifEmpty { fallback }
            ?: fallback
        val userFacing = when {
            selected.isNotEmpty() -> "Selected ${selected.size} tools for this run"
            else -> "Tool set is not narrowed for this run"
        }
        if (runtimePlan.roleControlPlan.visibilityPolicy.showTimelineForToolCalls) {
            updateSession(resolvedSessionId) { s ->
                s.copy(
                    activeLogLines = s.activeLogLines.finishLatestRunningLine() + LogLine(
                        type = LogType.THINKING,
                        text = userFacing,
                        details = buildList {
                            add(progressDetail(ProgressDetailKey.PURPOSE, "Select tools for this goal and role"))
                            result?.reason?.takeIf { it.isNotBlank() }?.let { add(progressDetail(ProgressDetailKey.RESULT, it.take(600))) }
                            add(progressDetail(ProgressDetailKey.DEBUG, "runtimePlanMode=${runtimePlan.executionMode}"))
                            if (selected.isNotEmpty()) add(progressDetail(ProgressDetailKey.DEBUG, selected.joinToString(", ")))
                            result?.executionPlan?.takeIf { it.isNotEmpty() }?.let { plan ->
                                add(progressDetail(ProgressDetailKey.DEBUG, plan.joinToString(" -> ").take(900)))
                            }
                        },
                    ).withLifecycle(running = false)
                )
            }
        }
        workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)?.let { workspaceId ->
            runCatching {
                app.workspaceStore.recordEvent(
                    workspaceId,
                    WorkspaceEvent(
                        category = "tool_selection",
                        source = "ai_tool_selector",
                        title = "Tool selection",
                        summary = selected.joinToString(", ").ifBlank { "unrestricted" }.take(300),
                        payload = buildString {
                            appendLine("goal=${execution.contextualGoal.take(1000)}")
                            appendLine("taskType=${execution.executionTaskType}")
                            appendLine("selected=${selected.joinToString(", ")}")
                            appendLine("reason=${result?.reason.orEmpty()}")
                            appendLine("fallback=${fallback.joinToString(", ")}")
                        }.trim(),
                    ),
                )
            }
        }
        return selected
    }

    private fun handleAgentCancellation(
        result: Result<com.mobileclaw.agent.AgentResult>,
        prepared: PreparedRunInput,
        resolvedSessionId: String,
        isPhoneControlTask: Boolean,
    ): Boolean {
        if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) {
            if (!isRunGenerationCurrent(resolvedSessionId, prepared.runGeneration)) return true
            overlay.hide()
            if (isPhoneControlTask) auroraOverlay.endTask()
            updateSession(resolvedSessionId) { s ->
                s.copy(
                    isRunning = false,
                    runStartedAt = 0L,
                    streamingToken = "",
                    streamingThought = "",
                    activeLogLines = emptyList(),
                    activeAttachments = emptyList(),
                )
            }
            clearRuntimeHandles(prepared.sessionIdAtStart, resolvedSessionId)
            return true
        }
        if (!isRunGenerationCurrent(resolvedSessionId, prepared.runGeneration)) {
            Log.w(TAG, "Run superseded before completion; dropping stale UI mutations. session=$resolvedSessionId generation=${prepared.runGeneration}")
            return true
        }
        return false
    }

    private fun prepareRunInput(
        goal: String,
        imageOverride: String?,
        visibleUserText: String,
        pendingTurn: PendingUserTurn?,
        showUserMessage: Boolean,
    ): PreparedRunInput? {
        val currentSessionId = pendingTurn?.sessionId ?: _uiState.value.currentSessionId
        if (goal.isBlank() || (pendingTurn == null && _uiState.value.sessionStates[currentSessionId]?.isRunning == true)) return null
        val codexDesktopMode = _uiState.value.codexDesktopMode || currentSessionId in _uiState.value.codexDesktopSessionIds
        val goalForRouting = if (codexDesktopMode) codexDesktopExecutionGoal(goal) else goal
        val attachedImage = imageOverride ?: pendingTurn?.imageBase64 ?: _uiState.value.inputImageBase64
        val attachedFile = pendingTurn?.fileAttachment ?: _uiState.value.inputFileAttachment
        val sessionIdAtStart = pendingTurn?.sessionId ?: _uiState.value.currentSessionId
        val attachedImageLocalPath = pendingTurn?.imageLocalPath
            ?: attachedImage?.let { persistUserImageForWorkspace(sessionIdAtStart, it) }
            ?: ""
        val effectiveGoal = when {
            attachedFile != null && attachedFile.isText ->
                "[Attachment: ${attachedFile.name}]\n```\n${attachedFile.content.take(10_000)}\n```\n\n$goalForRouting"
            attachedImageLocalPath.isNotBlank() ->
                "[Image saved to the local workspace]\npath: $attachedImageLocalPath\nPass this path directly to tools such as generate_video.image when the image is needed later. Do not ask the user to resend it or claim that only HTTP links are supported; the system uploads local images automatically.\n\n$goalForRouting"
            else -> goalForRouting
        }
        val userMessage = pendingTurn?.userMessage ?: ChatMessage(
            role = MessageRole.USER,
            text = visibleUserText,
            imageBase64 = if (attachedImage != null) attachedImage
                          else if (attachedFile != null && !attachedFile.isText) attachedFile.content
                          else null,
            attachments = if (attachedImageLocalPath.isNotBlank()) {
                listOf(SkillAttachment.ImageData(attachedImage.orEmpty(), prompt = "user image", localPath = attachedImageLocalPath))
            } else emptyList(),
            imageLocalPath = attachedImageLocalPath,
        )
        val runGeneration = pendingTurn?.runGeneration ?: beginRunGeneration(sessionIdAtStart)
        val userMessageVisible = pendingTurn != null || showUserMessage
        return PreparedRunInput(
            currentSessionId = currentSessionId,
            sessionIdAtStart = sessionIdAtStart,
            codexDesktopMode = codexDesktopMode,
            goalForRouting = goalForRouting,
            attachedImage = attachedImage,
            attachedFile = attachedFile,
            attachedImageLocalPath = attachedImageLocalPath,
            effectiveGoal = effectiveGoal,
            userMessage = userMessage,
            runGeneration = runGeneration,
            userMessageVisible = userMessageVisible,
            userMessagePersistedEarly = userMessageVisible && sessionIdAtStart.isNotBlank(),
        )
    }

    private fun resolveRunRoute(
        goal: String,
        prepared: PreparedRunInput,
        routeOverride: TaskRoute?,
    ): TaskRoute {
        routeOverride?.let { return it }
        return if (prepared.codexDesktopMode) {
            TaskRoute(
                taskType = TaskType.CODE_EXECUTION,
                contextualIntent = ContextualTaskIntent(
                    classificationGoal = prepared.goalForRouting,
                    taskTypeOverride = TaskType.CODE_EXECUTION,
                    aiPrimaryChannel = ChannelType.CODE,
                    aiToolHints = listOf("codex_desktop"),
                    userVisibleSteps = listOf("Connect to desktop Codex", "Send the task", "Return the result"),
                ),
                goalForExecution = prepared.effectiveGoal,
                source = TaskRouteSource.CLASSIFIER,
                goalToRemember = goal,
                debugReason = "Codex desktop session mode enabled.",
            )
        } else {
            val fallbackTaskType = if (prepared.attachedImage != null) TaskType.GENERAL else TaskType.CHAT
            TaskRoute(
                taskType = fallbackTaskType,
                contextualIntent = ContextualTaskIntent(
                    classificationGoal = goal,
                    taskTypeOverride = fallbackTaskType,
                    aiPrimaryChannel = ChannelType.CHAT,
                    aiSupportingChannels = emptyList(),
                    aiToolHints = emptyList(),
                ),
                goalForExecution = prepared.effectiveGoal,
                source = TaskRouteSource.CLASSIFIER,
                goalToRemember = goal,
                debugReason = "Internal direct-chat fallback because routeOverride was missing.",
            )
        }
    }

    private fun prepareRunExecution(
        goal: String,
        visibleUserText: String,
        prepared: PreparedRunInput,
        route: TaskRoute,
        routeOverride: TaskRoute?,
    ): PreparedRunExecution {
        val contextualIntent = route.contextualIntent
        val taskType = route.taskType
        val resolvedWorkspaceSessionId = prepared.sessionIdAtStart.ifBlank { _uiState.value.currentSessionId }
        workspaceRuntime.ensureSessionBinding(
            sessionId = resolvedWorkspaceSessionId,
            taskType = taskType,
            goal = route.goalForExecution,
            intent = contextualIntent,
        )
        val executionGoal = if (prepared.attachedFile?.isText == true && routeOverride != null && route.source != TaskRouteSource.ACTIVE_WORKFLOW) {
            prepared.effectiveGoal
        } else {
            route.goalForExecution
        }
        val workspaceResumeGoal = workspaceRuntime.augmentGoalWithWorkspaceResume(
            sessionId = resolvedWorkspaceSessionId,
            userGoal = goal,
            executionGoal = executionGoal,
        )
        val executionTaskType = taskType
        val contextualGoal = taskRouter.applyContextualTaskConstraints(workspaceResumeGoal, contextualIntent, taskType)
        val directPriorContext = buildPriorContext(
            goal = goal,
            taskType = executionTaskType,
            intent = contextualIntent,
            includeMemory = true,
            includeRecentMessages = false,
        )
        val agentPriorContext = buildPriorContext(
            goal = goal,
            taskType = executionTaskType,
            intent = contextualIntent,
            includeMemory = true,
            includeRecentMessages = false,
        )
        val currentRole = _uiState.value.currentRole
        val schedulingContext = buildPriorContext(
            goal = goal,
            taskType = executionTaskType,
            intent = contextualIntent,
            includeMemory = true,
            includeRecentMessages = true,
        )
        val schedulingGoal = listOf(contextualGoal, schedulingContext.take(1200))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val scheduleDecision = RoleScheduler.schedule(
            taskType = taskType,
            goal = schedulingGoal,
            availableRoles = _uiState.value.availableRoles,
            currentRole = currentRole,
            memoryContext = directPriorContext,
        )
        val scheduledRole = if (shouldUseScheduledRoleForRun(goal, executionTaskType, currentRole, scheduleDecision.role)) {
            scheduleDecision.role
        } else {
            currentRole
        }
        val roleProfile = adaptCurrentRoleForRuntime(scheduledRole, source = "prepare_run_execution")
        val roleControlPlan = roleChatRuntimeBridge.buildControlPlan(roleProfile)
        val roleDirectPriorContext = when {
            !roleControlPlan.contextPolicy.includeUserMemory && !roleControlPlan.contextPolicy.includeRecentMessages -> ""
            roleControlPlan.contextPolicy.includeRecentMessages -> buildPriorContext(
                goal = goal,
                taskType = executionTaskType,
                intent = contextualIntent,
                includeMemory = roleControlPlan.contextPolicy.includeUserMemory,
                includeRecentMessages = true,
            )
            roleControlPlan.contextPolicy.includeUserMemory -> directPriorContext
            else -> ""
        }
        val roleAgentPriorContext = when {
            roleControlPlan.contextPolicy.includeRecentMessages -> buildPriorContext(
                goal = goal,
                taskType = executionTaskType,
                intent = contextualIntent,
                includeMemory = roleControlPlan.contextPolicy.includeUserMemory,
                includeRecentMessages = true,
            )
            roleControlPlan.contextPolicy.includeUserMemory -> agentPriorContext
            else -> buildPriorContext(
                goal = goal,
                taskType = executionTaskType,
                intent = contextualIntent,
                includeMemory = false,
                includeRecentMessages = false,
            )
        }
        val orchestration = taskOrchestrator.orchestrate(
            route = route,
            goal = contextualGoal,
            hasImage = prepared.attachedImage != null,
            hasFile = prepared.attachedFile != null,
            role = scheduledRole,
        )
        val allowedToolIds = resolveAllowedToolIds(route, orchestration.channelDecision.toolHints, contextualGoal)
        return PreparedRunExecution(
            contextualIntent = contextualIntent,
            taskType = taskType,
            resolvedWorkspaceSessionId = resolvedWorkspaceSessionId,
            executionTaskType = executionTaskType,
            contextualGoal = contextualGoal,
            directPriorContext = roleDirectPriorContext,
            agentPriorContext = roleAgentPriorContext,
            isPhoneControlTask = executionTaskType == TaskType.PHONE_CONTROL,
            scheduleDecision = scheduleDecision,
            scheduledRole = scheduledRole,
            roleProfile = roleProfile,
            roleControlPlan = roleControlPlan,
            orchestration = orchestration,
            allowedToolIds = allowedToolIds,
            executionContext = orchestration.toPromptBlock(),
            visibleGoalLabel = visibleUserText.ifBlank {
                if (prepared.attachedImage != null) str(R.string.chat_20def7) else goal
            },
        )
    }

    private fun startRunUiState(
        prepared: PreparedRunInput,
        pendingTurn: PendingUserTurn?,
        showUserMessage: Boolean,
    ) {
        if (pendingTurn == null) {
            _uiState.update { it.copy(inputImageBase64 = null, inputFileAttachment = null) }
            updateSession(prepared.sessionIdAtStart) { s ->
                s.copy(
                    isRunning = true,
                    runStartedAt = System.currentTimeMillis(),
                    messages = if (showUserMessage) s.messages + prepared.userMessage else s.messages,
                    activeLogLines = emptyList(),
                    activeAttachments = emptyList(),
                    streamingToken = "",
                    streamingThought = "",
                )
            }
        } else {
            updateSession(prepared.sessionIdAtStart) { s -> s.copy(streamingToken = "", streamingThought = "") }
        }
    }

    private fun appendRoleControlLogLine(
        sessionId: String,
        execution: PreparedRunExecution,
        runtimePlan: ChatRuntimePlan,
    ) {
        val plan = execution.roleControlPlan
        val role = execution.scheduledRole
        val text = "Role ${role.name} is controlling this run"
        updateSession(sessionId) { s ->
            s.copy(
                activeLogLines = s.activeLogLines.finishLatestRunningLine() + LogLine(
                    type = LogType.THINKING,
                    text = text,
                    details = buildList {
                        add(progressDetail(ProgressDetailKey.PURPOSE,
                            "Apply the role protocol to intent, context, tools, and memory",
                        ))
                        add(progressDetail(ProgressDetailKey.RESULT, roleControlUserSummary(role, plan, runtimePlan.executionMode)))
                        add(progressDetail(ProgressDetailKey.NEXT, roleControlNextStep(runtimePlan.executionMode)))
                        add(progressDetail(ProgressDetailKey.ROLE, "${role.name} (${role.id})"))
                        add(progressDetail(ProgressDetailKey.EXECUTION_MODE, roleExecutionModeText(runtimePlan.executionMode, plan.executionModeHint)))
                        add(progressDetail(ProgressDetailKey.INTENT, roleIntentPolicyText(plan)))
                        add(progressDetail(ProgressDetailKey.RESPONSE, roleResponsePolicyText(plan)))
                        add(progressDetail(ProgressDetailKey.CONTEXT, roleContextPolicyText(plan)))
                        add(progressDetail(ProgressDetailKey.TOOLS, roleToolPolicyText(plan)))
                        add(progressDetail(ProgressDetailKey.MEMORY, rolePersistencePolicyText(plan)))
                    },
                ).withLifecycle(running = false)
            )
        }
    }

    private fun determineChatExecutionMode(
        goal: String,
        prepared: PreparedRunInput,
        route: TaskRoute,
        execution: PreparedRunExecution,
    ): ChatExecutionMode {
        if (prepared.attachedImage == null &&
            prepared.attachedFile == null &&
            route.primaryChannelForExecution() == ChannelType.INFO) {
            return ChatExecutionMode.INFO
        }
        if (prepared.attachedImage != null &&
            prepared.attachedFile == null &&
            route.isDirectAttachedImageChatRoute()) {
            return ChatExecutionMode.DIRECT_CHAT
        }
        if (prepared.attachedImage == null &&
            prepared.attachedFile == null &&
            shouldRunDirectChat(route, execution.roleControlPlan, execution.contextualGoal.ifBlank { goal })) {
            return ChatExecutionMode.DIRECT_CHAT
        }
        return ChatExecutionMode.AGENT
    }

    private fun createChatRuntimePlan(
        goal: String,
        visibleUserText: String,
        prepared: PreparedRunInput,
        route: TaskRoute,
        execution: PreparedRunExecution,
        executionMode: ChatExecutionMode,
    ): ChatRuntimePlan =
        chatRuntimeCoordinator.createPlan(
            ChatRuntimePlanInput(
                sessionId = execution.resolvedWorkspaceSessionId.ifBlank { prepared.sessionIdAtStart },
                userGoal = execution.contextualGoal.ifBlank { goal },
                visibleUserText = visibleUserText,
                route = route,
                taskType = execution.executionTaskType,
                role = execution.scheduledRole,
                roleControlPlan = execution.roleControlPlan,
                executionMode = executionMode,
                directPriorContext = execution.directPriorContext,
                agentPriorContext = execution.agentPriorContext,
                executionContext = execution.executionContext,
                allowedToolIds = execution.allowedToolIds,
                hasImage = prepared.attachedImage != null,
                hasFile = prepared.attachedFile != null,
            )
        )

    private fun runFastPathIfHandled(
        goal: String,
        prepared: PreparedRunInput,
        execution: PreparedRunExecution,
        runtimePlan: ChatRuntimePlan,
    ): Boolean {
        val persistUserMessage = prepared.userMessageVisible && !prepared.userMessagePersistedEarly
        when (runtimePlan.executionMode) {
            ChatExecutionMode.INFO -> {
                runInfoChannelAnswer(
                    sessionIdAtStart = prepared.sessionIdAtStart,
                    userMessage = prepared.userMessage,
                    goal = goal,
                    currentRole = execution.scheduledRole,
                    persistUserMessage = persistUserMessage,
                    runGeneration = prepared.runGeneration,
                )
                return true
            }
            ChatExecutionMode.DIRECT_CHAT -> {
                runDirectChat(
                    sessionIdAtStart = prepared.sessionIdAtStart,
                    userMessage = prepared.userMessage,
                    goal = goal,
                    currentRole = execution.scheduledRole,
                    roleControlPlan = execution.roleControlPlan,
                    priorContext = execution.directPriorContext,
                    executionContext = execution.executionContext,
                    imageBase64 = prepared.attachedImage,
                    persistUserMessage = persistUserMessage,
                    runGeneration = prepared.runGeneration,
                )
                return true
            }
            ChatExecutionMode.AGENT,
            ChatExecutionMode.CODEX_DESKTOP -> Unit
        }
        return false
    }

    private fun startAgentRuntime(
        prepared: PreparedRunInput,
        execution: PreparedRunExecution,
    ): StartedAgentRuntime {
        val llm = app.createLlmGateway()
        val runtime = AgentRuntime(llm, registry, app.semanticMemory, memoryContextBuilder)
        overlay.show(execution.visibleGoalLabel)
        val phoneAuroraOverlayShown = if (execution.isPhoneControlTask) {
            auroraOverlay.beginTask()
        } else {
            false
        }
        if (execution.isPhoneControlTask) {
            Log.d(TAG, "Phone aurora overlay begin requested. shown=$phoneAuroraOverlayShown")
        }
        consoleServer.broadcast("task_started", execution.visibleGoalLabel)
        return StartedAgentRuntime(runtime, phoneAuroraOverlayShown)
    }

    private fun CoroutineScope.collectNetworkTraceEvents(resolvedSessionId: String): Job = launch {
        com.mobileclaw.agent.NetworkTracer.events.collect { msg ->
            updateSession(resolvedSessionId) { s ->
                val lines = s.activeLogLines.toMutableList()
                if (lines.isNotEmpty()) {
                    val last = lines.last()
                    lines[lines.size - 1] = last.copy(details = last.details + msg)
                }
                s.copy(activeLogLines = lines)
            }
        }
    }

    private fun CoroutineScope.collectAgentRuntimeEvents(
        runtime: AgentRuntime,
        route: TaskRoute,
        scheduledRole: Role,
        scheduleDecision: RoleScheduleDecision,
        contextualGoal: String,
        resolvedSessionId: String,
        isPhoneControlTask: Boolean,
        visibleUserText: String,
        runtimePlan: ChatRuntimePlan,
    ): Job = launch {
        runtime.events.collect { event ->
            when (event) {
                is AgentEvent.Started -> {
                    if (visibleUserText.isNotBlank()) {
                        event.toLogLine()?.let { line ->
                            updateSession(resolvedSessionId) {
                                it.copy(activeLogLines = it.activeLogLines.finishLatestRunningLine() + line.withLifecycle(running = false))
                            }
                        }
                    }
                }
                is AgentEvent.ThinkingToken -> {
                    overlay.onToken(event.text)
                    updateSession(resolvedSessionId) { it.copy(streamingThought = it.streamingThought + event.text) }
                }
                is AgentEvent.SkillCalling -> {
                    handleRuntimeSkillCallingEvent(event, route, scheduledRole, resolvedSessionId, isPhoneControlTask, runtimePlan)
                }
                is AgentEvent.Observation -> {
                    handleRuntimeObservationEvent(event, scheduledRole, contextualGoal, resolvedSessionId, runtimePlan)
                }
                is AgentEvent.Error -> {
                    overlay.onError(event.message)
                    event.toLogLine()?.let { line ->
                        updateSession(resolvedSessionId) {
                            it.copy(activeLogLines = it.activeLogLines.finishLatestRunningLine() + line.withLifecycle(running = false))
                        }
                    }
                }
                is AgentEvent.Warning -> {
                    overlay.onWarning(event.message)
                    event.toLogLine()?.let { line ->
                        updateSession(resolvedSessionId) {
                            it.copy(activeLogLines = it.activeLogLines.finishLatestRunningLine() + line.withLifecycle(running = false))
                        }
                    }
                }
                is AgentEvent.ThinkingComplete -> {
                    overlay.onThinkingComplete()
                    val friendlyThought = friendlyThinkingUpdate(event.thought, route.contextualIntent.userVisibleSteps)
                    val userFacingThought = userFacingThinkingResult(event.thought, route.contextualIntent.userVisibleSteps)
                    updateSession(resolvedSessionId) { s ->
                        s.copy(
                            activeLogLines = s.activeLogLines.finishLatestRunningLine() + LogLine(
                                type = LogType.THINKING,
                                text = userFacingThought,
                                details = listOf(
                                    progressDetail(ProgressDetailKey.PURPOSE, userFacingThought),
                                    progressDetail(ProgressDetailKey.RESULT, friendlyThought),
                                    progressDetail(ProgressDetailKey.DEBUG, event.thought.take(1200)),
                                ),
                            ).withLifecycle(running = false),
                            streamingToken = "",
                            streamingThought = "",
                        )
                    }
                }
                is AgentEvent.PlanCreated -> {
                    handleRuntimePlanCreatedEvent(event, route, scheduledRole, scheduleDecision, resolvedSessionId, runtimePlan)
                }
                else -> event.toLogLine()?.let { line ->
                    updateSession(resolvedSessionId) {
                        it.copy(activeLogLines = it.activeLogLines.finishLatestRunningLine() + line.withLifecycle(running = false))
                    }
                }
            }
        }
    }

    private fun CoroutineScope.handleRuntimeSkillCallingEvent(
        event: AgentEvent.SkillCalling,
        route: TaskRoute,
        scheduledRole: Role,
        resolvedSessionId: String,
        isPhoneControlTask: Boolean,
        runtimePlan: ChatRuntimePlan,
    ) {
        val actionIndex = _uiState.value.sessionStates[resolvedSessionId]
            ?.activeLogLines
            ?.count { it.type == LogType.ACTION }
            ?: 0
        val stageText = plannedStageForAction(route.contextualIntent.userVisibleSteps, actionIndex)
        val debugPurposeText = stageAwareSkillDescription(stageText, event.skillId, event.params)
        val purposeText = userFacingSkillStart(stageText, event.skillId, event.params)
        overlay.onSkillCalling(event.skillId, event.params)
        if (isPhoneControlTask || event.skillId in VISUAL_SKILL_IDS) {
            if (event.skillId == "see_screen") auroraOverlay.flashFullScreen()
            else auroraOverlay.flash()
        }
        val paramDetails = event.params.entries.map { (k, v) ->
            "  $k: ${Gson().toJson(v).take(300)}"
        }
        val lineDetails = buildList {
            add(progressDetail(ProgressDetailKey.PURPOSE, purposeText))
            userFacingActionResult(event.skillId, stageText)
                .takeIf { it.isNotBlank() }
                ?.let { add(progressDetail(ProgressDetailKey.RESULT, it)) }
            if (stageText.isNotBlank() && stageText != debugPurposeText) add(progressDetail(ProgressDetailKey.PLAN, stageText))
            add(progressDetail(ProgressDetailKey.DEBUG, str(R.string.vm_c96809)))
            add(progressDetail(ProgressDetailKey.DEBUG, "intent=$debugPurposeText"))
            addAll(paramDetails.map { progressDetail(ProgressDetailKey.DEBUG, it) })
        }
        val line = event.toLogLine()?.copy(text = purposeText, details = lineDetails)
        if (runtimePlan.shouldShowToolTimeline(event.skillId)) {
            updateSession(resolvedSessionId) { s ->
                s.copy(
                    streamingThought = "",
                    activeLogLines = if (line != null) {
                        s.activeLogLines.finishLatestRunningLine() + line.withLifecycle(running = true)
                    } else {
                        s.activeLogLines
                    },
                )
            }
        } else {
            updateSession(resolvedSessionId) { it.copy(streamingThought = "") }
        }
        launch(Dispatchers.IO) {
            decorateRoleHomeWithTool(scheduledRole, event.skillId, purposeText)
        }
        consoleServer.broadcast("skill_called", purposeText)
    }

    private fun CoroutineScope.handleRuntimeObservationEvent(
        event: AgentEvent.Observation,
        scheduledRole: Role,
        contextualGoal: String,
        resolvedSessionId: String,
        runtimePlan: ChatRuntimePlan,
    ) {
        val previousSkill = _uiState.value.sessionStates[resolvedSessionId]
            ?.activeLogLines
            ?.lastOrNull { it.type == LogType.ACTION }
            ?.skillId
        val purposeText = friendlyObservationDescription(previousSkill, event.text, event.imageBase64 != null)
        val actionStage = _uiState.value.sessionStates[resolvedSessionId]
            ?.activeLogLines
            ?.lastOrNull { it.type == LogType.ACTION }
            ?.details
            ?.let { ProgressDetailProtocol.value(it, ProgressDetailKey.PLAN) }
        overlay.onObservation(purposeText)
        if (event.attachment is SkillAttachment.ActionCard && event.attachment.tone == "role") {
            pendingRoleSwitchTaskGoal = contextualGoal
        }
        val attachment = when (event.attachment) {
            is SkillAttachment.AccessibilityRequest -> {
                pendingAccessibilityTaskGoal = contextualGoal
                ConfirmationFlow.accessibilityActionCard(
                    goal = contextualGoal,
                    skillName = event.attachment.skillName,
                )
            }
            else -> event.attachment
        }
        val inlineProcessAttachment = attachment?.takeIf { it.shouldShowInlineInProcess() }
        val shouldShowTimeline = runtimePlan.shouldShowObservationTimeline(previousSkill, attachment)
        val lineDetails = buildList {
            actionStage?.takeIf { it.isNotBlank() }?.let { add(progressDetail(ProgressDetailKey.PURPOSE, it)) }
            add(progressDetail(ProgressDetailKey.RESULT, purposeText))
            userFacingActionNext(actionStage.orEmpty(), previousSkill.orEmpty(), event.text)
                ?.let { add(progressDetail(ProgressDetailKey.NEXT, it)) }
            if (event.text.isNotBlank()) {
                summarizeTechnicalResultForUser(previousSkill, event.text)?.let { add(progressDetail(ProgressDetailKey.NOTE, it)) }
                add(progressDetail(ProgressDetailKey.DEBUG, "Full result (${event.text.length} chars)"))
                add(progressDetail(ProgressDetailKey.FULL_RESULT, event.text.take(2000)))
            }
        }
        val line = LogLine(
            type = LogType.OBSERVATION,
            text = purposeText,
            imageBase64 = event.imageBase64,
            attachments = inlineProcessAttachment?.let { listOf(it) }.orEmpty(),
            details = lineDetails,
        ).withLifecycle(running = false)
        if (shouldShowTimeline) {
            updateSession(resolvedSessionId) { s ->
                s.copy(
                    activeLogLines = s.activeLogLines.finishLatestRunningLine() + line,
                    activeAttachments = if (attachment != null && inlineProcessAttachment == null)
                        s.activeAttachments + attachment
                    else s.activeAttachments,
                )
            }
        }
        if (attachment != null) {
            launch(Dispatchers.IO) {
                decorateRoleHomeWithAttachment(scheduledRole, previousSkill, purposeText, attachment)
            }
        }
        runCatching {
            persistWorkspaceObservation(
                sessionId = resolvedSessionId,
                skillId = previousSkill,
                rawOutput = event.text,
            )
        }
    }

    private fun SkillAttachment.shouldShowInlineInProcess(): Boolean =
        this is SkillAttachment.SearchResults || this is SkillAttachment.WebPage

    private fun ChatRuntimePlan.shouldShowToolTimeline(skillId: String): Boolean {
        if (skillId in VISUAL_SKILL_IDS) return true
        return if (skillId.isMemoryTimelineSkill()) {
            roleControlPlan.visibilityPolicy.showTimelineForMemoryWrites
        } else {
            roleControlPlan.visibilityPolicy.showTimelineForToolCalls
        }
    }

    private fun ChatRuntimePlan.shouldShowObservationTimeline(
        previousSkillId: String?,
        attachment: SkillAttachment?,
    ): Boolean {
        if (attachment != null) return true
        val skillId = previousSkillId.orEmpty()
        return if (skillId.isMemoryTimelineSkill()) {
            roleControlPlan.visibilityPolicy.showTimelineForMemoryWrites
        } else {
            roleControlPlan.visibilityPolicy.showTimelineForToolCalls
        }
    }

    private fun String.isMemoryTimelineSkill(): Boolean =
        this in setOf("memory", "user_profile", "user_config", "role_workspace")

    private fun handleRuntimePlanCreatedEvent(
        event: AgentEvent.PlanCreated,
        route: TaskRoute,
        scheduledRole: Role,
        scheduleDecision: RoleScheduleDecision,
        resolvedSessionId: String,
        runtimePlan: ChatRuntimePlan,
    ) {
        if (!runtimePlan.roleControlPlan.visibilityPolicy.showTimelineForToolCalls &&
            !runtimePlan.roleControlPlan.visibilityPolicy.exposeTraceByDefault) {
            return
        }
        val steps = route.contextualIntent.userVisibleSteps.ifEmpty { event.plan.steps }
        val text = steps.firstOrNull() ?: event.plan.summary
        val secondStep = steps.drop(1).firstOrNull { it.isNotBlank() }
        updateSession(resolvedSessionId) { s ->
            s.copy(
                activeLogLines = s.activeLogLines.finishLatestRunningLine() + LogLine(
                    type = LogType.THINKING,
                    text = text,
                    details = buildList {
                        add(progressDetail(ProgressDetailKey.PURPOSE, text))
                        add(progressDetail(ProgressDetailKey.RESULT, userFacingPlanResult(steps, event.plan.summary)))
                        if (!secondStep.isNullOrBlank()) add(progressDetail(ProgressDetailKey.NEXT, secondStep.trim()))
                        add(progressDetail(ProgressDetailKey.DEBUG, "role=${scheduledRole.name} (${scheduledRole.id})"))
                        add(progressDetail(ProgressDetailKey.DEBUG, scheduleDecision.reason))
                        add(progressDetail(ProgressDetailKey.DEBUG, event.plan.toPrompt().take(1600)))
                    },
                ).withLifecycle(running = true)
            )
        }
    }

    private fun buildDirectChatContext(
        currentRole: Role,
        roleControlPlan: RoleChatControlPlan,
        priorContext: String,
        executionContext: String,
        imageBase64: String?,
    ): DirectChatContext {
        val langSection = "\n${responseLanguageShortInstruction()}\n"
        val roleSection = if (currentRole.id != "general" && currentRole.systemPromptAddendum.isNotBlank()) {
            "\n## Your Persona\n${currentRole.systemPromptAddendum.trim()}\n"
        } else ""
        val roleWorkspaceSection = roleChatRuntimeBridge.buildDirectChatPromptContext(roleControlPlan)
            .takeIf { it.isNotBlank() }
            ?.let { "\n$it\n" }
            .orEmpty()
        val contextSection = if (priorContext.isNotBlank()) "\n## Stable Memory And Active Artifacts\n$priorContext\n" else ""
        val configSnapshot = config.snapshot()
        val localChatMode = configSnapshot.localNativeOnly || configSnapshot.localModelEnabled
        val imageInstruction = if (imageBase64 != null) {
            "\nThe user attached an image. Answer from the image itself. Do not search the web, do not call tools, and do not say you need external lookup unless the user explicitly asks for web research.\n"
        } else ""
        val directExecutionContext = if (imageBase64 != null) executionContext else ""
        val capabilityInfoInstruction =
            "If the user asks what MobileClaw can do or which tools are available, do not guess from memory; that request is handled by the INFO capability directory."
        val roleUiInstruction = buildRoleUiInstruction(roleControlPlan)
        val systemPrompt = if (localChatMode) {
            buildString {
                appendLine("You are ${currentRole.name}, MobileClaw's on-device assistant.")
                append(langSection)
                appendLine(capabilityInfoInstruction)
                appendLine(roleUiInstruction)
                if (directExecutionContext.isNotBlank()) {
                    appendLine(directExecutionContext.trim())
                }
                appendLine("Execution channels are separate: chat for conversation, memory for stable facts, skill/self-evolution for capability changes, and artifact/tool routes for actions. Do not merge them into one blob.")
                append(imageInstruction)
                if (currentRole.id != "general" && currentRole.systemPromptAddendum.isNotBlank()) {
                    appendLine("Persona: ${currentRole.systemPromptAddendum.trim().take(180)}")
                }
                if (roleWorkspaceSection.isNotBlank()) {
                    appendLine(roleWorkspaceSection.take(roleControlPlan.contextPolicy.maxRoleContextChars))
                }
                if (priorContext.isNotBlank()) {
                    appendLine("Stable memory and active artifacts:")
                    appendLine(priorContext.take(1200))
                }
                appendLine("Answer directly when the user only needs conversation. Short follow-ups refer to the recent context.")
                appendLine("If the latest user message clearly requires memory, skills, artifacts, files, web, or phone execution, do not behave as if chat is the only available path.")
            }.trim()
        } else {
            """You are ${currentRole.name}, a helpful AI assistant inside MobileClaw.$langSection$imageInstruction$roleSection$roleWorkspaceSection$contextSection
$capabilityInfoInstruction
$roleUiInstruction
${if (directExecutionContext.isNotBlank()) directExecutionContext + "\n" else ""}
## Execution Channels
Chat, memory, skills, and self-evolution are separate channels. Use the right channel for the user's request instead of mixing everything into one response.

## Context Rules
Use the current user message as the source of truth. Treat recent conversation as supporting context only.
Short follow-ups refer to the most relevant recent message or artifact.
Do not start building pages, HTML, MiniAPPs, or UI artifacts unless the user clearly asks to create or modify one.
If the latest user message clearly requires memory, skills, artifacts, files, web, or phone execution, do not behave as if chat is the only available path.

${if (roleControlPlan.responsePolicy.allowUiBlocks) "## Optional Interactive UI" else "## Interactive UI Policy"}
For normal conversation, reply in plain text.
${if (roleControlPlan.responsePolicy.allowUiBlocks) "Only embed a ${"```"}ui block when the user explicitly asks for interactive choices, forms, tables, comparisons, dashboards, or says they want buttons/cards." else "Do not embed a ${"```"}ui block in this role unless the user explicitly asks for an interactive UI."}
If you use the UI DSL, it MUST be wrapped exactly as:
${"```"}ui
{"type":"column","children":[...]}
${"```"}
Never output raw UI JSON, `+ ui`, string concatenation, or Kotlin/JavaScript snippets in a chat answer.
Types: column/row(gap,padding,children) | card(title,children) | text(content,size,bold,color,align) | button(label,action,style) | input(key,placeholder) | select(key,options:[]) | table(headers:[],rows:[[]]) | chart_bar/chart_line(data:[],labels:[],title) | progress(value,label) | badge(text,color) | divider | spacer(size)
Actions: "send:message" | "submit:text with {key}" | "copy:text"
For pure conversational replies, greetings, explanations, and simple factual answers, do not output a ui block.""".trimIndent()
        }
        return DirectChatContext(systemPrompt = systemPrompt)
    }

    private fun runDirectChat(
        sessionIdAtStart: String,
        userMessage: ChatMessage,
        goal: String,
        currentRole: Role,
        roleControlPlan: RoleChatControlPlan,
        priorContext: String,
        executionContext: String = "",
        imageBase64: String? = null,
        persistUserMessage: Boolean = true,
        runGeneration: Long,
    ) {
        val newJob = viewModelScope.launch {
            var resolvedSessionId = sessionIdAtStart
            try {
            resolvedSessionId = ensureRunnableSession(sessionIdAtStart)
            if (resolvedSessionId != sessionIdAtStart) adoptRunGeneration(resolvedSessionId, runGeneration)
            Log.d(
                TAG,
                "Direct chat started. session=$resolvedSessionId role=${currentRole.id} hasImage=${imageBase64 != null} goal=${goal.take(160)}"
            )
            workspaceRuntime.ensureSessionBinding(
                sessionId = resolvedSessionId,
                taskType = if (imageBase64 != null) TaskType.GENERAL else TaskType.CHAT,
                goal = goal,
                intent = ContextualTaskIntent(goal),
            )
            persistRuntimeWorkspaceUpdate(
                sessionId = resolvedSessionId,
                goal = goal,
                update = AgentWorkspaceUpdate(
                    stage = "direct_chat_started",
                    taskType = if (imageBase64 != null) TaskType.GENERAL.name else TaskType.CHAT.name,
                    label = "direct_chat_started",
                    summary = goal.take(240),
                    details = "Direct chat turn started.",
                ),
            )
            if (resolvedSessionId.isNotBlank()) {
                launch(Dispatchers.IO) {
                    runCatching {
                        database.sessionDao().updateRole(resolvedSessionId, currentRole.id)
                        loadSessions()
                    }
                }
            }

            val directChatContext = buildDirectChatContext(
                currentRole = currentRole,
                roleControlPlan = roleControlPlan,
                priorContext = priorContext,
                executionContext = executionContext,
                imageBase64 = imageBase64,
            )

            val llm = app.createLlmGateway()
            val chatMessages = buildStructuredDirectChatMessages(
                sessionId = resolvedSessionId,
                systemPrompt = directChatContext.systemPrompt,
                currentGoal = goal,
                imageBase64 = imageBase64,
            )

            val result = runRetryLoop(
                maxAttempts = LLM_RETRY_MAX_ATTEMPTS,
                attempt = { _ ->
                    llm.chat(ChatRequest(
                        messages = chatMessages,
                        tools = emptyList(),
                        stream = true,
                        onToken = { token ->
                            val clean = token.cleanLocalStreamDelta()
                            if (clean.isNotEmpty()) {
                                updateSession(resolvedSessionId) { it.copy(streamingToken = (it.streamingToken + clean).cleanLocalStreamingText()) }
                            }
                        },
                        callOptions = roleLlmCallOptions(currentRole),
                    ))
                },
                shouldRetry = { attemptResult ->
                    Log.d(
                        TAG,
                        "Direct chat request finished. session=$resolvedSessionId success=${attemptResult.isSuccess} contentLength=${attemptResult.getOrNull()?.content?.length ?: 0} error=${attemptResult.exceptionOrNull()?.message?.take(160).orEmpty()}"
                    )
                    shouldRetryDirectChat(attemptResult.exceptionOrNull())
                },
                beforeRetry = { attemptIndex ->
                    appendRetryLogLine(
                        resolvedSessionId,
                        if (attemptIndex == 0) "The response was malformed; regenerating it"
                        else "The response remains malformed; retrying with a more stable request",
                    )
                    delay(500L * (attemptIndex + 1))
                },
            ).rethrowCancellation()

            val summary = (result.getOrNull()?.content
                ?: _uiState.value.sessionStates[resolvedSessionId]?.streamingToken?.ifBlank { null }
                ?: result.exceptionOrNull()?.message?.let(::friendlyLlmFailureMessage) ?: "Error.").cleanLocalTurnTokens()
            persistRuntimeWorkspaceUpdate(
                sessionId = resolvedSessionId,
                goal = goal,
                update = AgentWorkspaceUpdate(
                    stage = "direct_chat_completed",
                    taskType = if (imageBase64 != null) TaskType.GENERAL.name else TaskType.CHAT.name,
                    label = "direct_chat_completed",
                    summary = summary.take(240),
                    details = summary.take(4000),
                    success = result.isSuccess,
                ),
            )

            val finalAgentMsg = ChatMessage(
                role = MessageRole.AGENT,
                text = summary,
                senderRoleId = currentRole.id,
                senderRoleName = currentRole.name,
                senderRoleAvatar = currentRole.avatar,
            )
            val isCurrentRun = isRunGenerationCurrent(resolvedSessionId, runGeneration)
            updateSession(resolvedSessionId) { s ->
                // If a newer task has taken over, append the reply without touching its runtime state.
                if (isCurrentRun) s.copy(
                    isRunning = false,
                    streamingToken = "",
                    streamingThought = "",
                    messages = s.messages + finalAgentMsg,
                    activeLogLines = emptyList(),
                    activeAttachments = emptyList(),
                ) else s.copy(messages = s.messages + finalAgentMsg)
            }
            Log.d(
                TAG,
                "Direct chat completed. session=$resolvedSessionId success=${result.isSuccess} summaryLength=${summary.length}"
            )

            if (resolvedSessionId.isNotBlank()) {
                launch(Dispatchers.IO) { persistMessages(resolvedSessionId, userMessage.takeIf { persistUserMessage }, listOf(finalAgentMsg)) }
            }
            launch(Dispatchers.IO) {
                runCatching {
                    val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)
                    conversationMemory.addUserMessage(goal, taskId = workspaceId)
                    conversationMemory.addAgentMessage(summary, taskId = workspaceId)
                    recordUserMemoryHints(goal, workspaceId)
                    profileExtractor.extractAndUpdate(goal, summary, taskId = workspaceId)
                    workspaceId?.let {
                        memoryWriter.recordTaskSnapshot(
                            scopeId = it,
                            goal = goal,
                            summary = summary,
                            taskType = if (imageBase64 != null) TaskType.GENERAL.name else TaskType.CHAT.name,
                            success = result.isSuccess,
                        )
                    }
                    commitRoleMemory(
                        roleId = currentRole.id,
                        goal = goal,
                        summary = summary,
                        taskType = if (imageBase64 != null) TaskType.GENERAL.name else TaskType.CHAT.name,
                        success = result.isSuccess,
                        controlPlan = roleControlPlan,
                        source = "direct_chat",
                        workspaceSessionId = resolvedSessionId,
                    )
                }
            }
            showCompletionOverlayIfNeeded(summary)
            } catch (e: Throwable) {
                val cleanupSessionId = resolvedSessionId.ifBlank { sessionIdAtStart }
                if (e is kotlinx.coroutines.CancellationException) {
                    if (isRunGenerationCurrent(cleanupSessionId, runGeneration)) {
                        updateSession(cleanupSessionId) { s ->
                            s.copy(isRunning = false, runStartedAt = 0L, streamingToken = "", streamingThought = "")
                        }
                    }
                    return@launch
                }
                if (isRunGenerationCurrent(cleanupSessionId, runGeneration)) {
                    updateSession(cleanupSessionId) { s ->
                        s.copy(
                            isRunning = false,
                            runStartedAt = 0L,
                            streamingToken = "",
                            streamingThought = "",
                            messages = s.messages + ChatMessage(
                                role = MessageRole.AGENT,
                                text = e.message ?: "Error.",
                                senderRoleId = currentRole.id,
                                senderRoleName = currentRole.name,
                                senderRoleAvatar = currentRole.avatar,
                            ),
                            activeLogLines = emptyList(),
                            activeAttachments = emptyList(),
                        )
                    }
                }
                Log.e(TAG, "Direct chat failed. session=$cleanupSessionId goal=${goal.take(160)}", e)
            } finally {
                if (isRunGenerationCurrent(resolvedSessionId.ifBlank { sessionIdAtStart }, runGeneration)) {
                    clearRuntimeHandles(sessionIdAtStart, resolvedSessionId)
                }
            }
        }
        taskJobs[sessionIdAtStart] = newJob
    }

    private fun runInfoChannelAnswer(
        sessionIdAtStart: String,
        userMessage: ChatMessage,
        goal: String,
        currentRole: Role,
        persistUserMessage: Boolean = true,
        runGeneration: Long,
    ) {
        val newJob = viewModelScope.launch {
            var resolvedSessionId = sessionIdAtStart
            try {
                resolvedSessionId = ensureRunnableSession(sessionIdAtStart)
                if (resolvedSessionId != sessionIdAtStart) adoptRunGeneration(resolvedSessionId, runGeneration)
                Log.d(TAG, "Info channel answer started. session=$resolvedSessionId goal=${goal.take(160)}")
                val summary = withContext(Dispatchers.IO) { buildMobileClawCapabilityDirectory(goal) }
                val finalAgentMsg = ChatMessage(
                    role = MessageRole.AGENT,
                    text = summary,
                    senderRoleId = currentRole.id,
                    senderRoleName = currentRole.name,
                    senderRoleAvatar = currentRole.avatar,
                )
                val isCurrentRun = isRunGenerationCurrent(resolvedSessionId, runGeneration)
                updateSession(resolvedSessionId) { s ->
                    // If a newer task has taken over, append the reply without touching its runtime state.
                    if (isCurrentRun) s.copy(
                        isRunning = false,
                        runStartedAt = 0L,
                        streamingToken = "",
                        streamingThought = "",
                        messages = s.messages + finalAgentMsg,
                        activeLogLines = emptyList(),
                        activeAttachments = emptyList(),
                    ) else s.copy(messages = s.messages + finalAgentMsg)
                }
                if (resolvedSessionId.isNotBlank()) {
                    launch(Dispatchers.IO) { persistMessages(resolvedSessionId, userMessage.takeIf { persistUserMessage }, listOf(finalAgentMsg)) }
                }
                launch(Dispatchers.IO) {
                    runCatching {
                        val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(resolvedSessionId)
                        conversationMemory.addUserMessage(goal, taskId = workspaceId)
                        conversationMemory.addAgentMessage(summary, taskId = workspaceId)
                    }
                }
                Log.d(TAG, "Info channel answer completed. session=$resolvedSessionId summaryLength=${summary.length}")
            } catch (e: Throwable) {
                val cleanupSessionId = resolvedSessionId.ifBlank { sessionIdAtStart }
                if (e is kotlinx.coroutines.CancellationException) {
                    if (isRunGenerationCurrent(cleanupSessionId, runGeneration)) {
                        updateSession(cleanupSessionId) { s ->
                            s.copy(isRunning = false, runStartedAt = 0L, streamingToken = "", streamingThought = "")
                        }
                    }
                    return@launch
                }
                if (isRunGenerationCurrent(cleanupSessionId, runGeneration)) {
                    updateSession(cleanupSessionId) { s ->
                        s.copy(
                            isRunning = false,
                            runStartedAt = 0L,
                            streamingToken = "",
                            streamingThought = "",
                            messages = s.messages + ChatMessage(
                                role = MessageRole.AGENT,
                                text = e.message ?: "Unable to read the capability catalog.",
                                senderRoleId = currentRole.id,
                                senderRoleName = currentRole.name,
                                senderRoleAvatar = currentRole.avatar,
                            ),
                            activeLogLines = emptyList(),
                            activeAttachments = emptyList(),
                        )
                    }
                }
                Log.e(TAG, "Info channel answer failed. session=$cleanupSessionId goal=${goal.take(160)}", e)
            } finally {
                if (isRunGenerationCurrent(resolvedSessionId.ifBlank { sessionIdAtStart }, runGeneration)) {
                    clearRuntimeHandles(sessionIdAtStart, resolvedSessionId)
                }
            }
        }
        taskJobs[sessionIdAtStart] = newJob
    }

    private suspend fun ensureRunnableSession(sessionIdAtStart: String): String {
        if (sessionIdAtStart.isNotBlank()) return sessionIdAtStart
        withContext(Dispatchers.IO) { createNewSessionInternal() }
        val resolvedSessionId = _uiState.value.currentSessionId
        val previousState = _uiState.value.sessionStates[sessionIdAtStart]
        if (previousState != null && resolvedSessionId != sessionIdAtStart) {
            _uiState.update { state ->
                state.copy(sessionStates = (state.sessionStates - sessionIdAtStart) + (resolvedSessionId to previousState))
            }
        }
        return resolvedSessionId
    }

    private fun clearRuntimeHandles(sessionIdAtStart: String, resolvedSessionId: String) {
        taskJobs.remove(resolvedSessionId)
        if (sessionIdAtStart != resolvedSessionId) {
            taskJobs.remove(sessionIdAtStart)
        }
    }

    // Attach preview auto-repair to the current session and continue immediately when no task is running.
    private fun enqueueMiniAppAutoRepair(sessionId: String, appId: String, previewStatus: String) {
        if (sessionId.isBlank() || appId.isBlank()) return
        val current = pendingMiniAppAutoRepairs[sessionId]
        if (current?.appId == appId && current.previewStatus == previewStatus) return
        val nextAttempt = if (current?.appId == appId) current.attempt + 1 else 1
        if (nextAttempt > MINI_APP_AUTO_REPAIR_MAX_ATTEMPTS) return
        pendingMiniAppAutoRepairs[sessionId] = PendingMiniAppAutoRepair(
            sessionId = sessionId,
            appId = appId,
            previewStatus = previewStatus,
            attempt = nextAttempt,
        )
        if (_uiState.value.sessionStates[sessionId]?.isRunning != true && taskJobs[sessionId] == null) {
            resumePendingMiniAppAutoRepair(sessionId)
        }
    }

    // After this run, automatically inspect logs, make a targeted repair, validate, and reopen.
    private fun resumePendingMiniAppAutoRepair(sessionId: String) {
        val pending = pendingMiniAppAutoRepairs.remove(sessionId) ?: return
        if (_uiState.value.sessionStates[sessionId]?.isRunning == true || taskJobs[sessionId] != null) {
            pendingMiniAppAutoRepairs[sessionId] = pending
            return
        }
        val rememberedGoal = activeWorkflows[sessionId]?.originalGoal
            ?.takeIf { it.isNotBlank() }
            ?: "Repair the existing MiniAPP without losing finished features."
        val autoRepairGoal = buildString {
            appendLine("Continue the current MiniAPP task automatically.")
            appendLine("Target MiniAPP id: ${pending.appId}")
            appendLine("Preview status: ${pending.previewStatus}")
            appendLine("Original goal: ${rememberedGoal.take(1200)}")
            appendLine("Required behavior:")
            appendLine("1. Inspect the latest runtime logs for this MiniAPP.")
            appendLine("2. Repair only the failing path shown by logs or preview behavior.")
            appendLine("3. Validate the MiniAPP.")
            appendLine("4. Re-open it and confirm the chat preview renders correctly.")
            appendLine("5. Do not create a new MiniAPP. Do not ask the user to continue.")
            if (pending.attempt > 1) {
                appendLine("This is repair attempt ${pending.attempt}. Change strategy and patch the exact failing code path.")
            }
        }.trim()
        val route = TaskRoute(
            taskType = TaskType.APP_BUILD,
            contextualIntent = ContextualTaskIntent(
                classificationGoal = autoRepairGoal,
                taskTypeOverride = TaskType.APP_BUILD,
                userVisibleSteps = listOf(
                    "First identify why the in-chat preview did not render correctly",
                    "Then repair only the failing logic",
                    "Revalidate after the repair and reopen it for confirmation",
                ),
                executionHint = buildString {
                    appendLine("Automatic MiniAPP repair continuation triggered by chat preview feedback.")
                    appendLine("App id: ${pending.appId}")
                    appendLine("Preview status: ${pending.previewStatus}")
                    appendLine("Keep the existing artifact and continue from its latest runtime state.")
                }.trim(),
            ),
            goalForExecution = autoRepairGoal,
            source = TaskRouteSource.ACTIVE_WORKFLOW,
            goalToRemember = rememberedGoal,
            debugReason = "Auto-continued MiniAPP repair from unhealthy chat preview.",
        )
        runTaskInternal(
            goal = autoRepairGoal,
            visibleUserText = "",
            routeOverride = route,
            showUserMessage = false,
        )
    }

    private fun runCodexDesktopDirect(
        sessionId: String,
        userMessage: ChatMessage,
        userGoal: String,
        prompt: String,
        imageBase64: String?,
        imageLocalPath: String,
        fileAttachment: FileAttachment?,
        showUserMessage: Boolean,
        persistUserMessage: Boolean,
        runGeneration: Long,
    ) {
        if (sessionId.isBlank()) return
        _uiState.update { it.copy(inputImageBase64 = null, inputFileAttachment = null) }
        updateSession(sessionId) { state ->
            state.copy(
                isRunning = true,
                runStartedAt = System.currentTimeMillis(),
                messages = if (showUserMessage) state.messages + userMessage else state.messages,
                activeLogLines = listOf(
                    LogLine(
                        type = LogType.ACTION,
                        text = "Send to desktop Codex",
                        skillId = "codex_desktop",
                        details = listOf("Goal: ${userGoal.take(500)}"),
                    ).withLifecycle(running = true),
                ),
                activeAttachments = emptyList(),
                streamingToken = "",
                streamingThought = "",
            )
        }
        overlay.show(userGoal)
        consoleServer.broadcast("task_started", userGoal)

        val job = viewModelScope.launch {
            val result = streamCodexDesktop(
                sessionId = sessionId,
                prompt = prompt,
                attachments = buildCodexDesktopAttachments(imageBase64, imageLocalPath, fileAttachment),
            )
            val finishedAt = System.currentTimeMillis()
            val statusLine = LogLine(
                type = if (result.success) LogType.SUCCESS else LogType.ERROR,
                text = if (result.success) {
                    "Desktop Codex returned a result"
                } else {
                    "Desktop Codex failed"
                },
                skillId = "codex_desktop",
                details = listOf(result.output.take(2000)),
                finishedAt = finishedAt,
            )
            val progressLines = _uiState.value.sessionStates[sessionId]
                ?.activeLogLines
                ?.finishLatestRunningLine()
                ?.filter { it.skillId == "codex_desktop" && it.text.isNotBlank() }
                .orEmpty()
            val agentMessage = ChatMessage(
                role = MessageRole.AGENT,
                text = result.output.ifBlank { if (result.success) "Desktop Codex completed the task." else "Desktop Codex returned no output." },
                logLines = progressLines + statusLine,
                senderRoleId = _uiState.value.currentRole.id,
                senderRoleName = _uiState.value.currentRole.name,
                senderRoleAvatar = _uiState.value.currentRole.avatar,
            )
            val isCurrentRun = isRunGenerationCurrent(sessionId, runGeneration)
            updateSession(sessionId) { state ->
                // If a newer task has taken over, append the reply without touching its runtime state or task handle.
                if (isCurrentRun) state.copy(
                    isRunning = false,
                    runStartedAt = 0L,
                    messages = state.messages + agentMessage,
                    activeLogLines = emptyList(),
                    activeAttachments = emptyList(),
                    streamingToken = "",
                    streamingThought = "",
                ) else state.copy(messages = state.messages + agentMessage)
            }
            if (isCurrentRun) {
                taskJobs.remove(sessionId)
                overlay.hide()
            }
            consoleServer.broadcast("task_completed", result.output.take(500))
            withContext(Dispatchers.IO) {
                persistMessages(sessionId, userMessage.takeIf { persistUserMessage }, listOf(agentMessage))
                database.sessionDao().updateTitle(sessionId, userGoal.take(40).ifBlank { "Codex session" })
                loadSessions()
            }
        }
        taskJobs[sessionId] = job
    }

    private suspend fun streamCodexDesktop(
        sessionId: String,
        prompt: String,
        attachments: JsonArray = JsonArray(),
    ): com.mobileclaw.skill.SkillResult = withContext(Dispatchers.IO) {
        val endpoint = userConfig.get("codex_desktop_endpoint")?.trim()?.trimEnd('/').orEmpty()
        val token = userConfig.get("codex_desktop_token")?.trim().orEmpty()
        val cwd = userConfig.get("codex_desktop_cwd").orEmpty()
        val model = userConfig.get("codex_desktop_model").orEmpty()
        val provider = userConfig.get("codex_desktop_provider").orEmpty()
        val approval = userConfig.get("codex_desktop_approval").orEmpty()
        val sandbox = userConfig.get("codex_desktop_sandbox").orEmpty()
        if (endpoint.isBlank() || token.isBlank()) {
            return@withContext com.mobileclaw.skill.SkillResult(
                false,
                "Codex desktop bridge is not configured. Please set Bridge URL and Token in Codex Bridge settings.",
            )
        }

        val body = JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("mobile_session_id", sessionId)
            if (attachments.size() > 0) add("attachments", attachments)
            addProperty("cwd", cwd)
            add("config", JsonObject().apply {
                addProperty("cwd", cwd)
                addProperty("model", model)
                addProperty("provider", provider)
                addProperty("approval", approval)
                addProperty("sandbox", sandbox)
            })
        }
        val req = Request.Builder()
            .url("$endpoint/run_stream")
            .header("Authorization", "Bearer $token")
            .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            codexBridgeStreamClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    return@use com.mobileclaw.skill.SkillResult(false, "Codex bridge HTTP ${resp.code}: ${text.take(2000)}")
                }
                val output = StringBuilder()
                var ok = true
                var finalOutput = ""
                resp.body?.byteStream()?.bufferedReader()?.useLines { lines ->
                    lines.forEach { rawLine ->
                        if (rawLine.isBlank()) return@forEach
                        val json = runCatching { JsonParser.parseString(rawLine).asJsonObject }.getOrNull()
                        when (json?.get("type")?.asString) {
                            "output" -> {
                                val text = json.get("text")?.asString.orEmpty()
                                val chunkSize = when {
                                    text.length > 8_000 -> 160
                                    text.length > 3_000 -> 96
                                    else -> 36
                                }
                                val chunkDelayMs = when {
                                    text.length > 8_000 -> 3L
                                    text.length > 3_000 -> 6L
                                    else -> 12L
                                }
                                text.chunked(chunkSize).forEach { chunk ->
                                    output.append(chunk)
                                    val visible = output.toString().cleanCodexDesktopOutput(prompt).takeLast(24_000)
                                    updateSession(sessionId) { state ->
                                        state.copy(streamingToken = visible)
                                    }
                                    overlay.onToken(chunk)
                                    if (chunkDelayMs > 0L && text.length > chunkSize) {
                                        Thread.sleep(chunkDelayMs)
                                    }
                                }
                            }
                            "progress" -> {
                                val fallbackText = json.get("text")?.asString.orEmpty()
                                val text = json.get("label")?.asString?.ifBlank { null } ?: fallbackText
                                val detail = json.get("detail")?.asString?.ifBlank { null }
                                    ?: json.get("output")?.asString?.ifBlank { null }
                                    ?: json.get("command")?.asString?.ifBlank { null }
                                    ?: ""
                                val running = json.get("status")?.asString == "running"
                                if (text.isNotBlank()) {
                                    val line = LogLine(
                                        type = LogType.ACTION,
                                        text = text,
                                        skillId = "codex_desktop",
                                        details = listOf(detail).filter { it.isNotBlank() },
                                    ).withLifecycle(running = running)
                                    updateSession(sessionId) { state ->
                                        state.copy(
                                            activeLogLines = state.activeLogLines.finishLatestRunningLine() + line,
                                        )
                                    }
                                }
                            }
                            "done" -> {
                                ok = json.get("ok")?.asBoolean ?: true
                                finalOutput = json.get("output")?.asString.orEmpty()
                            }
                        }
                    }
                }
                val resolvedOutput = finalOutput.ifBlank { output.toString().trim() }.cleanCodexDesktopOutput(prompt)
                com.mobileclaw.skill.SkillResult(
                    ok,
                    resolvedOutput.ifBlank { if (ok) "Codex finished with no output." else "Codex failed with no output." },
                )
            } ?: com.mobileclaw.skill.SkillResult(false, "Codex bridge returned an empty response.")
        }.getOrElse {
            com.mobileclaw.skill.SkillResult(false, "Codex bridge stream failed: ${it.message}")
        }
    }

    private fun buildCodexDesktopAttachments(
        imageBase64: String?,
        imageLocalPath: String,
        fileAttachment: FileAttachment?,
    ): JsonArray = JsonArray().apply {
        if (!imageBase64.isNullOrBlank()) {
            add(JsonObject().apply {
                addProperty("kind", "image")
                addProperty("name", imageLocalPath.substringAfterLast('/').ifBlank { "mobileclaw-image.jpg" })
                addProperty("mime_type", imageBase64.substringAfter("data:", "").substringBefore(";").ifBlank { "image/jpeg" })
                addProperty("data_uri", imageBase64)
            })
        }
        if (fileAttachment != null) {
            add(JsonObject().apply {
                addProperty("kind", "file")
                addProperty("name", fileAttachment.name.ifBlank { "attachment" })
                addProperty("mime_type", fileAttachment.mimeType.ifBlank { "application/octet-stream" })
                addProperty("is_text", fileAttachment.isText)
                if (fileAttachment.isText) {
                    addProperty("text", fileAttachment.content)
                } else {
                    addProperty("base64", fileAttachment.content)
                }
            })
        }
    }

    fun stopTask() {
        val sessionId = _uiState.value.currentSessionId
        val shouldStopDesktopCodex = _uiState.value.codexDesktopMode || sessionId in _uiState.value.codexDesktopSessionIds
        app.agentTaskController.cancelSession(sessionId, AgentCancellationReason.USER_REQUEST)
        taskJobs[sessionId]?.cancel()
        taskJobs.remove(sessionId)
        if (shouldStopDesktopCodex) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    registry.get("codex_desktop")?.execute(mapOf("action" to "stop"))
                }
            }
        }
        overlay.hide()
        auroraOverlay.hide()
        consoleServer.broadcast("task_stopped", "")
        val salvaged = salvageInterruptedRunMessages(sessionId)
        updateSession(sessionId) { state ->
            state.copy(
                isRunning = false,
                runStartedAt = 0L,
                streamingToken = "",
                streamingThought = "",
                messages = state.messages + salvaged,
                activeLogLines = emptyList(),
                activeAttachments = emptyList(),
            )
        }
        if (salvaged.isNotEmpty() && sessionId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { persistMessages(sessionId, null, salvaged) } }
        }
    }

    fun setInputImage(imageBase64: String?) {
        _uiState.update { it.copy(inputImageBase64 = imageBase64) }
    }

    fun setFileAttachment(attachment: FileAttachment?) {
        _uiState.update { it.copy(inputFileAttachment = attachment) }
    }

    private val backStack = ArrayDeque<AppPage>().apply { add(AppPage.HOME) }

    fun consumeSettingsLaunchTarget() {
        _uiState.update { it.copy(settingsLaunchTarget = null) }
    }

    fun openGatewayConfig() {
        checkPrivServer()
        loadVideoTasks()
        if (backStack.isEmpty() || backStack.last() != AppPage.SETTINGS) {
            backStack.addLast(AppPage.SETTINGS)
        }
        _uiState.update {
            it.copy(
                currentPage = AppPage.SETTINGS,
                canNavigateBack = backStack.size > 1,
                settingsLaunchTarget = SettingsLaunchTarget.GATEWAY,
            )
        }
    }

    fun navigate(page: AppPage) {
        val targetPage = page
        if (targetPage == AppPage.SKILLS) refreshPromotableSkills()
        if (targetPage == AppPage.PROFILE || targetPage == AppPage.USER_INFO || targetPage == AppPage.MEMORY_SETTINGS) loadProfileData()
        if (targetPage == AppPage.SETTINGS || targetPage == AppPage.AI_BASIC_SETTINGS || targetPage == AppPage.GENERAL_SETTINGS || targetPage == AppPage.TOOLS_SETTINGS) {
            checkPrivServer()
            loadVideoTasks()
        }
        if (targetPage == AppPage.VIDEO_GENERATOR) loadVideoTasks()
        if (targetPage == AppPage.WORKSPACE) loadCurrentWorkspaceSnapshot()
        if (targetPage == AppPage.APPS) loadMiniApps()
        if (targetPage == AppPage.ROLES || targetPage == AppPage.ROLE_DETAIL || targetPage == AppPage.AI_TOWN) townStore.ensureRooms(roleManager.all())
        if (targetPage == AppPage.HOME) {
            backStack.clear()
            backStack.addLast(targetPage)
        } else if (backStack.isEmpty() || backStack.last() != targetPage) {
            backStack.addLast(targetPage)
        }

        if (targetPage == AppPage.BROWSER && _uiState.value.browserUrl.isBlank()) {
            _uiState.update { it.copy(browserUrl = "https://www.bing.com", currentPage = targetPage, canNavigateBack = backStack.size > 1) }
            return
        }
        _uiState.update { it.copy(currentPage = targetPage, canNavigateBack = backStack.size > 1) }
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeLast()
            val page = backStack.last()
            if (page == AppPage.APPS) loadMiniApps()
            val clearEdit = page != AppPage.ROLE_EDIT
            _uiState.update { it.copy(
                currentPage = page,
                canNavigateBack = backStack.size > 1,
                editingRole = if (clearEdit) null else it.editingRole,
            ) }
        }
    }

    fun navigateToBrowser(url: String) {
        val currentPage = _uiState.value.currentPage
        if (backStack.isEmpty()) {
            backStack.addLast(if (currentPage == AppPage.BROWSER) AppPage.HOME else currentPage)
        } else if (backStack.last() != currentPage && currentPage != AppPage.BROWSER) {
            backStack.addLast(currentPage)
        }
        if (backStack.last() != AppPage.BROWSER) backStack.addLast(AppPage.BROWSER)
        _uiState.update { it.copy(browserUrl = url, currentPage = AppPage.BROWSER, canNavigateBack = backStack.size > 1) }
    }

    fun loadMiniApps() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshMiniAppsSnapshot()
        }
    }

    private fun refreshMiniAppsSnapshot() {
        val apps = runCatching { app.miniAppStore.all() }.getOrDefault(emptyList())
        _uiState.update { it.copy(miniApps = apps) }
    }

    fun clearPendingAppOpen() {
        _uiState.update { it.copy(openAppId = null) }
    }

    fun clearChatMiniAppPreview() {
        miniAppValidationOverlay.hide(notifyDismissed = false)
        _uiState.update {
            it.copy(
                chatMiniAppPreviewId = null,
                chatMiniAppPreviewMode = "",
                chatMiniAppPreviewSessionId = null,
                chatMiniAppPreviewStatus = "",
                chatMiniAppPreviewHealthy = true,
            )
        }
    }

    fun updateChatMiniAppPreviewStatus(appId: String, status: String, healthy: Boolean) {
        val normalized = status.trim().ifBlank {
            if (healthy) "Validation preview passed" else "Validation preview found issues"
        }
        val snapshot = _uiState.value
        if (snapshot.chatMiniAppPreviewId != appId) return
        val previewSessionId = snapshot.chatMiniAppPreviewSessionId ?: snapshot.currentSessionId
        val changed = snapshot.chatMiniAppPreviewStatus != normalized || snapshot.chatMiniAppPreviewHealthy != healthy
        _uiState.update {
            if (it.chatMiniAppPreviewId != appId) it
            else it.copy(
                chatMiniAppPreviewStatus = normalized,
                chatMiniAppPreviewHealthy = healthy,
            )
        }
        if (healthy) {
            if (changed) {
                viewModelScope.launch {
                    delay(900)
                    val latest = _uiState.value
                    if (latest.chatMiniAppPreviewId == appId &&
                        latest.chatMiniAppPreviewHealthy &&
                        latest.chatMiniAppPreviewStatus == normalized) {
                        clearChatMiniAppPreview()
                    }
                }
            }
            return
        }
        if (!changed) return
        updateSession(previewSessionId) { state ->
            if (!state.isRunning) return@updateSession state
            val line = LogLine(
                type = LogType.OBSERVATION,
                text = normalized.take(220),
                skillId = "app_manager",
                details = listOf(
                    progressDetail(ProgressDetailKey.RESULT,
                        "The validation preview in chat has detected a runtime issue",
                    ),
                    progressDetail(ProgressDetailKey.NOTE,
                        "Close the validation preview, inspect logs, and make a targeted fix instead of rewriting the whole MiniAPP",
                    ),
                ),
            )
            if (state.activeLogLines.lastOrNull()?.text == line.text) state
            else state.copy(activeLogLines = state.activeLogLines + line)
        }
        // Keep the preview visible even when it reports a runtime issue. The user needs
        // to see the failing surface while the agent inspects logs and repairs it.
        enqueueMiniAppAutoRepair(sessionId = previewSessionId, appId = appId, previewStatus = normalized)
    }

    fun clearAiPageOpen() {
        _uiState.update { it.copy(openAiPageId = null) }
    }

    fun openTownRole(roleId: String) {
        _uiState.update { it.copy(openTownRoleId = roleId) }
    }

    fun closeTownRole() {
        _uiState.update { it.copy(openTownRoleId = null) }
    }

    fun deleteAiPage(id: String) {
        viewModelScope.launch(Dispatchers.IO) { app.aiPageStore.delete(id) }
    }

    fun openHtmlViewer(attachment: SkillAttachment.HtmlData) {
        _uiState.update {
            val stack = it.htmlAttachmentStack + attachment
            it.copy(openHtmlAttachment = attachment, htmlAttachmentStack = stack)
        }
    }

    fun closeHtmlViewer() {
        _uiState.update {
            val stack = it.htmlAttachmentStack.dropLast(1)
            it.copy(openHtmlAttachment = stack.lastOrNull(), htmlAttachmentStack = stack)
        }
    }

    fun deleteApp(appId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.miniAppStore.delete(appId) }
            refreshMiniAppsSnapshot()
        }
    }

    fun deleteApps(appIds: Collection<String>) {
        val ids = appIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> runCatching { app.miniAppStore.delete(id) } }
            refreshMiniAppsSnapshot()
            val message = "Deleted ${ids.size} MiniAPPs"
            withContext(Dispatchers.Main) {
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun exportRolePackage(roleId: String) {
        if (roleId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { rolePackageStore.exportPackage(roleId) }
            val file = result.getOrNull()
            if (file != null) {
                shareExportedPackage(
                    file = file,
                    chooserTitle = "Export role package",
                    successMessage = "Role package is ready",
                )
            } else {
                val e = result.exceptionOrNull()
                showPackageToast("Role export failed: " + (e?.message ?: ""))
            }
        }
    }

    fun importRolePackage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempFile = copyImportUriToCache(uri, "role", RolePackageStore.ROLE_PACKAGE_EXTENSION)
                rolePackageStore.importPackage(tempFile)
            }.onSuccess { result ->
                townStore.ensureRooms(roleManager.all())
                showPackageToast("Imported role: " + result.role.name.ifBlank { result.importedId })
            }.onFailure { e ->
                showPackageToast("Role import failed: " + (e.message ?: ""))
            }
        }
    }

    fun exportMiniAppPackage(appId: String) {
        if (appId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { app.miniAppStore.exportPackage(appId) }
            val file = result.getOrNull()
            if (file != null) {
                shareExportedPackage(
                    file = file,
                    chooserTitle = "Export MiniAPP package",
                    successMessage = "MiniAPP package is ready",
                )
            } else {
                val e = result.exceptionOrNull()
                showPackageToast("MiniAPP export failed: " + (e?.message ?: ""))
            }
        }
    }

    fun importMiniAppPackage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempFile = copyImportUriToCache(uri, "miniapp", MiniAppStore.MINI_APP_PACKAGE_EXTENSION)
                app.miniAppStore.importPackage(tempFile)
            }.onSuccess { result ->
                refreshMiniAppsSnapshot()
                showPackageToast("Imported MiniAPP: " + result.app.title.ifBlank { result.importedId })
            }.onFailure { e ->
                showPackageToast("MiniAPP import failed: " + (e.message ?: ""))
            }
        }
    }

    private fun copyImportUriToCache(uri: Uri, prefix: String, extension: String): File {
        val importDir = File(app.cacheDir, "workspace_imports").also { it.mkdirs() }
        val outFile = File(importDir, "${prefix}_${System.currentTimeMillis()}.$extension")
        val input = app.contentResolver.openInputStream(uri)
            ?: error("Unable to read the selected file")
        input.use { source ->
            outFile.outputStream().use { target -> source.copyTo(target) }
        }
        return outFile
    }

    private suspend fun shareExportedPackage(file: File, chooserTitle: String, successMessage: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                app.startActivity(
                    Intent.createChooser(shareIntent, chooserTitle).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                Toast.makeText(app, successMessage, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(
                    app,
                    "Unable to open share sheet: " + (e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private suspend fun showPackageToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(app, message.take(180), Toast.LENGTH_LONG).show()
        }
    }

    fun checkPrivServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val connected = PrivilegedClient.isAvailable()
            _uiState.update { it.copy(privServerConnected = connected) }
        }
    }

    fun setProfileFact(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.semanticMemory.set(key = key, value = value, source = "profile_ui") }
            refreshProfileFacts()
        }
    }

    fun setMemoryPinned(key: String, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.semanticMemory.setPinned(key, pinned) }
            refreshProfileFacts()
        }
    }

    fun setMemoryEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.semanticMemory.setEnabled(key, enabled) }
            refreshProfileFacts()
        }
    }

    fun deleteMemoryFact(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.semanticMemory.delete(key) }
            refreshProfileFacts()
        }
    }

    fun loadProfileData() {
        viewModelScope.launch {
            _uiState.update { it.copy(profileState = it.profileState.copy(isLoading = true)) }
            val episodes = runCatching { app.database.episodeDao().recent(limit = 20) }.getOrDefault(emptyList())
            val convCount = runCatching { conversationMemory.messageCount() }.getOrDefault(0)
            val semanticFacts = runCatching { app.semanticMemory.pageIncludingDisabled(limit = PROFILE_MEMORY_PAGE_SIZE, offset = 0) }.getOrDefault(emptyList())
            val facts = semanticFacts.filter { it.enabled }.associate { it.key to it.value }
            _uiState.update {
                it.copy(profileState = it.profileState.copy(
                    facts = facts,
                    semanticFacts = semanticFacts,
                    memoryHasMore = semanticFacts.size >= PROFILE_MEMORY_PAGE_SIZE,
                    memoryLoadingMore = false,
                    recentEpisodes = episodes,
                    conversationCount = convCount,
                    isLoading = false,
                ))
            }
        }
    }

    fun loadMoreProfileMemory() {
        val state = _uiState.value.profileState
        if (state.memoryLoadingMore || !state.memoryHasMore) return
        viewModelScope.launch {
            val offset = _uiState.value.profileState.semanticFacts.size
            _uiState.update { it.copy(profileState = it.profileState.copy(memoryLoadingMore = true)) }
            val next = runCatching {
                app.semanticMemory.pageIncludingDisabled(limit = PROFILE_MEMORY_PAGE_SIZE, offset = offset)
            }.getOrDefault(emptyList())
            _uiState.update { current ->
                val merged = (current.profileState.semanticFacts + next)
                    .distinctBy { it.key }
                    .sortedWith(compareByDescending<com.mobileclaw.memory.MemoryFact> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.key })
                current.copy(
                    profileState = current.profileState.copy(
                        semanticFacts = merged,
                        facts = merged.filter { it.enabled }.associate { it.key to it.value },
                        memoryHasMore = next.size >= PROFILE_MEMORY_PAGE_SIZE,
                        memoryLoadingMore = false,
                    )
                )
            }
        }
    }

    fun triggerProfileExtraction() {
        viewModelScope.launch {
            _uiState.update { it.copy(profileState = it.profileState.copy(isExtracting = true)) }
            val episodes = runCatching { app.database.episodeDao().recent(limit = 20) }.getOrDefault(emptyList())
            val convJob = launch { runCatching { profileExtractor.extractAndUpdate("", "") } }
            val epJob   = launch { runCatching { profileExtractor.extractFromEpisodes(episodes) } }
            convJob.join(); epJob.join()
            val loadedCount = _uiState.value.profileState.semanticFacts.size.coerceAtLeast(PROFILE_MEMORY_PAGE_SIZE)
            val semanticFacts = runCatching { app.semanticMemory.pageIncludingDisabled(limit = loadedCount, offset = 0) }.getOrDefault(emptyList())
            val facts = semanticFacts.filter { it.enabled }.associate { it.key to it.value }
            _uiState.update {
                it.copy(profileState = it.profileState.copy(
                    facts = facts,
                    semanticFacts = semanticFacts,
                    memoryHasMore = semanticFacts.size >= loadedCount,
                    isExtracting = false,
                ))
            }
        }
    }

    // ── AI Profile Analysis ──────────────────────────────────────────────────

    fun generatePersonalitySummary() {
        if (_uiState.value.profileState.personalitySummaryLoading) return
        val facts = _uiState.value.profileState.facts.filter { it.key.startsWith("profile.") }
        if (facts.isEmpty()) return
        _uiState.update {
            it.copy(profileState = it.profileState.copy(personalitySummaryLoading = true, personalitySummary = ""))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val factsText = facts.entries.joinToString("\n") { (k, v) -> "- ${k.removePrefix("profile.")}: $v" }
            val foundationalMemory = buildUserMemoryContextForPrompt("Generate a user profile summary", TaskType.GENERAL).take(1600)
            val prompt = ProfileAiGeneration.buildPersonalitySummaryPrompt(factsText, foundationalMemory)
            val summary = runCatching {
                llm.chat(ChatRequest(
                    messages = listOf(
                        Message(role = "system", content = ProfileAiGeneration.PERSONALITY_SYSTEM_INSTRUCTION),
                        Message(role = "user", content = prompt),
                    ),
                    stream = false,
                )).content?.trim() ?: ""
            }.getOrDefault("")
            _uiState.update {
                it.copy(profileState = it.profileState.copy(personalitySummary = summary, personalitySummaryLoading = false))
            }
        }
    }

    private suspend fun fetchDimensionQuiz(dimensionId: String, dimensionTitle: String): List<AiQuizQuestion> {
        val facts = _uiState.value.profileState.facts
        val relevantFacts = facts.entries
            .filter { it.key.startsWith("profile.$dimensionId.") || it.key.startsWith("profile.personality.") || it.key.startsWith("profile.cognitive.") }
            .joinToString("\n") { (k, v) -> "- ${k.removePrefix("profile.")}: $v" }
            .ifBlank { "No known information yet." }
        val foundationalMemory = buildUserMemoryContextForPrompt(
            "Generate self-reflection questions for the $dimensionTitle dimension",
            TaskType.GENERAL,
        ).take(1600)
        val prompt = ProfileAiGeneration.buildDimensionQuizPrompt(
            dimensionId = dimensionId,
            dimensionTitle = dimensionTitle,
            relevantFacts = relevantFacts,
            foundationalMemory = foundationalMemory,
        )
        val content = runCatching {
            llm.chat(ChatRequest(
                messages = listOf(
                    Message(role = "system", content = ProfileAiGeneration.QUIZ_SYSTEM_INSTRUCTION),
                    Message(role = "user", content = prompt),
                ),
                stream = false,
            )).content?.trim() ?: ""
        }.getOrDefault("")
        return ProfileAiGeneration.parseDimensionQuiz(content, dimensionId)
    }

    fun generateDimensionQuiz(dimensionId: String, dimensionTitle: String) {
        if (_uiState.value.profileState.dimensionQuizLoading == dimensionId) return
        _uiState.update { it.copy(profileState = it.profileState.copy(dimensionQuizLoading = dimensionId)) }
        viewModelScope.launch(Dispatchers.IO) {
            val questions = fetchDimensionQuiz(dimensionId, dimensionTitle)
            _uiState.update { state ->
                state.copy(profileState = state.profileState.copy(
                    dimensionQuizzes = state.profileState.dimensionQuizzes + (dimensionId to questions),
                    dimensionQuizLoading = null,
                ))
            }
        }
    }

    fun prewarmAllDimensionQuizzes(dimensions: List<ProfileDimension>) {
        val todo = dimensions.filter { it.id !in _uiState.value.profileState.dimensionQuizzes }
        if (todo.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (dim in todo) {
                if (_uiState.value.profileState.dimensionQuizzes.containsKey(dim.id)) continue
                val questions = fetchDimensionQuiz(dim.id, dim.title)
                _uiState.update { state ->
                    state.copy(profileState = state.profileState.copy(
                        dimensionQuizzes = state.profileState.dimensionQuizzes + (dim.id to questions)
                    ))
                }
                delay(400)
            }
        }
    }

    // ── Skill Notes ──────────────────────────────────────────────────────────

    fun saveSkillNote(skillId: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.skillNotesStore.set(skillId, note)
        }
    }

    fun generateSkillNote(skillId: String, skillName: String, description: String) {
        if (_uiState.value.skillNoteGenerating == skillId) return
        _uiState.update { it.copy(skillNoteGenerating = skillId) }
        viewModelScope.launch(Dispatchers.IO) {
            val prompt = SkillNoteGeneration.buildPrompt(skillName, description)
            val note = runCatching {
                llm.chat(ChatRequest(
                    messages = listOf(Message(role = "user", content = prompt)),
                    stream = false,
                )).content?.trim() ?: ""
            }.getOrDefault("")
            if (note.isNotBlank()) {
                app.skillNotesStore.set(skillId, note)
            }
            _uiState.update { it.copy(skillNoteGenerating = null) }
        }
    }

    fun showSettings(show: Boolean) = navigate(if (show) AppPage.SETTINGS else AppPage.HOME)
    fun showSkillManager(show: Boolean) = navigate(if (show) AppPage.SKILLS else AppPage.HOME)
    fun openWorkspacePage() {
        loadCurrentWorkspaceSnapshot()
        navigate(AppPage.WORKSPACE)
    }

    fun loadCurrentWorkspaceSnapshot() {
        val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(_uiState.value.currentSessionId)
        val snapshot = workspaceId?.let { app.workspaceStore.inspectorSnapshot(it) }
        val facts = workspaceId?.let { id ->
            _uiState.value.profileState.semanticFacts
                .filter { fact -> fact.key.startsWith("session.$id.") }
                .sortedByDescending { fact -> fact.updatedAt }
        }.orEmpty()
        val areas = buildWorkspaceAreas(_uiState.value)
        val openArea = _uiState.value.workspaceState.openArea?.let { selected ->
            areas.firstOrNull { it.id == selected.id } ?: selected
        }
        val detail = openArea?.let { buildWorkspaceAreaDetail(it.id, _uiState.value.workspaceState.openAreaCurrentPath) }
        _uiState.update {
            it.copy(
                workspaceState = it.workspaceState.copy(
                    snapshot = snapshot,
                    facts = facts,
                    areas = areas,
                    openArea = openArea,
                    openAreaRoots = detail?.first.orEmpty(),
                    openAreaCurrentPath = detail?.second.orEmpty(),
                    openAreaEntries = detail?.third.orEmpty(),
                )
            )
        }
    }

    fun openWorkspaceArea(areaId: String) {
        val state = _uiState.value
        val areas = state.workspaceState.areas.ifEmpty { buildWorkspaceAreas(state) }
        val area = areas.firstOrNull { it.id == areaId } ?: return
        val (roots, currentPath, entries) = buildWorkspaceAreaDetail(area.id, "")
        _uiState.update {
            it.copy(
                workspaceState = it.workspaceState.copy(
                    areas = areas,
                    openArea = area,
                    openAreaRoots = roots,
                    openAreaCurrentPath = currentPath,
                    openAreaEntries = entries,
                )
            )
        }
    }

    fun openWorkspaceFolder(path: String) {
        val area = _uiState.value.workspaceState.openArea ?: return
        val dir = File(path)
        if (!dir.isDirectory || !isWorkspaceAreaPathAllowed(area.id, dir)) return
        val (roots, currentPath, entries) = buildWorkspaceAreaDetail(area.id, dir.absolutePath)
        _uiState.update {
            it.copy(
                workspaceState = it.workspaceState.copy(
                    openAreaRoots = roots,
                    openAreaCurrentPath = currentPath,
                    openAreaEntries = entries,
                )
            )
        }
    }

    fun navigateWorkspaceFolderUp() {
        val workspaceState = _uiState.value.workspaceState
        val area = workspaceState.openArea ?: return
        val current = workspaceState.openAreaCurrentPath.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val roots = workspaceAreaRoots(area.id)
        val parentPath = current.parentFile
            ?.takeIf { parent -> roots.any { root -> parent.absolutePath.startsWith(root.absolutePath) } }
            ?.absolutePath
            .orEmpty()
        val (rootLabels, currentPath, entries) = buildWorkspaceAreaDetail(area.id, parentPath)
        _uiState.update {
            it.copy(
                workspaceState = it.workspaceState.copy(
                    openAreaRoots = rootLabels,
                    openAreaCurrentPath = currentPath,
                    openAreaEntries = entries,
                )
            )
        }
    }

    fun closeWorkspaceArea() {
        _uiState.update {
            it.copy(
                workspaceState = it.workspaceState.copy(
                    openArea = null,
                    openAreaRoots = emptyList(),
                    openAreaCurrentPath = "",
                    openAreaEntries = emptyList(),
                )
            )
        }
    }

    private fun buildWorkspaceAreas(state: MainUiState): List<WorkspaceAreaUi> {
        val taskWorkspaces = runCatching { app.workspaceStore.list(limit = 200) }.getOrDefault(emptyList())
        val semanticFacts = state.profileState.semanticFacts
        val configSnapshot = config.snapshot()
        val mcpSkills = state.allSkills.filter { it.type == SkillType.MCP || it.id.contains("mcp", ignoreCase = true) }
        val installedLocalModels = state.localModels.count { it.installed }
        val portablePackageCount = workspacePortablePackageCount()
        val configuredModelCount = listOfNotNull(
            configSnapshot.chatModel.takeIf { it.isNotBlank() },
            configSnapshot.imageModel,
            configSnapshot.videoModel,
            configSnapshot.embeddingModel.takeIf { it.isNotBlank() },
        ).distinct().size
        val mediaCount = state.miniApps.size + state.aiPages.size + state.videoTasks.size
        val taskCount = state.videoTasks.size + state.sessionStates.size + taskWorkspaces.count { it.status == "active" }

        return listOf(
            workspaceArea("roles", "Roles", "Role definitions, skills, memory, and role-specific state", state.availableRoles.size, "Connected to the role catalog"),
            workspaceArea("user_memory", "User Memory", "Long-term user memory, profile facts, and preferences", semanticFacts.size, "Connected to semantic memory"),
            workspaceArea("work", "Work", "AI-generated files, task artifacts, and workspaces", taskWorkspaces.size, "Uses existing task workspaces"),
            workspaceArea("sessions", "Sessions", "Chat sessions, context, and runtime records", state.sessions.size, "Connected to recent sessions"),
            workspaceArea("skills", "Skills", "Built-in skills, user skills, and skill notes", state.allSkills.size, "Visible skill capabilities"),
            workspaceArea("mcp", "MCP", "Tools installed through configured MCP integrations", mcpSkills.size, "Exposed through the skill system"),
            workspaceArea("models", "Models & Gateways", "Cloud gateways, assigned models, and local models", configuredModelCount + installedLocalModels, modelWorkspaceStatus(configSnapshot, installedLocalModels)),
            workspaceArea("media", "Media", "Images, videos, MiniAPPs, and AI Page artifacts", mediaCount, "Aggregates visible media artifacts"),
            workspaceArea("system", "System", "Permissions, console data, VPN, runtime cache, and configuration", configSnapshot.gateways.size, "Gateway and system configuration available"),
            workspaceArea("tasks", "Task Runtime", "Active task state, video tasks, recipes, replays, and workspaces", taskCount, "Summarizes current runtime activity"),
            workspaceArea("play", "Play", "Agent Town and related role-interaction state", state.agentTown.rooms.size, "Agent Town data available"),
            workspaceArea("backup", "Import & Export", "Portable role, MiniAPP, AI Page, and workspace packages", portablePackageCount, if (portablePackageCount > 0) "Portable packages available" else "Waiting for exported packages"),
        )
    }

    private fun workspaceArea(
        id: String,
        title: String,
        description: String,
        count: Int,
        status: String,
    ): WorkspaceAreaUi =
        WorkspaceAreaUi(
            id = id,
            title = title,
            description = description,
            countLabel = WorkspacePresentationSemantics.itemCount(count),
            statusLabel = status,
        )

    private fun modelWorkspaceStatus(snapshot: ConfigSnapshot, installedLocalModels: Int): String = when {
        snapshot.localNativeOnly -> "Local-native mode; $installedLocalModels local ${if (installedLocalModels == 1) "model" else "models"} installed"
        snapshot.localModelEnabled -> "Local model enabled; $installedLocalModels local ${if (installedLocalModels == 1) "model" else "models"} installed"
        snapshot.gateways.isNotEmpty() -> "Active gateway: ${snapshot.activeGateway?.name ?: snapshot.activeGatewayId ?: "Default"}"
        else -> "No gateway configured"
    }

    private fun workspacePortablePackageCount(): Int {
        val filesDir = app.filesDir
        return listOf(File(filesDir, "workspace_exports"), File(filesDir, "workspace_imports"))
            .sumOf { root ->
                if (!root.exists()) 0 else root.walkTopDown().count { file -> file.isFile && file.extension.startsWith("mobileclaw") }
            }
    }

    private fun buildWorkspaceAreaDetail(areaId: String, requestedPath: String): Triple<List<String>, String, List<WorkspaceFileEntryUi>> {
        val roots = workspaceAreaRoots(areaId)
        val currentDir = requestedPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory && roots.any { root -> it.absolutePath.startsWith(root.absolutePath) } }
        val entries = if (currentDir == null) {
            roots.map { root ->
                WorkspaceFileEntryUi(
                    path = root.name.ifBlank { root.absolutePath },
                    absolutePath = root.absolutePath,
                    isDirectory = true,
                    sizeLabel = "Directory",
                    updatedLabel = formatWorkspaceFileTime(root.lastModified()),
                    preview = root.absolutePath,
                )
            }
        } else {
            workspaceEntriesForDirectory(currentDir)
        }
        return Triple(roots.map { it.absolutePath }, currentDir?.absolutePath.orEmpty(), entries)
    }

    private fun isWorkspaceAreaPathAllowed(areaId: String, file: File): Boolean =
        workspaceAreaRoots(areaId).any { root -> file.absolutePath.startsWith(root.absolutePath) }

    private fun workspaceAreaRoots(areaId: String): List<File> {
        val filesDir = app.filesDir
        val dataDir = File(app.applicationInfo.dataDir)
        val databasesDir = File(dataDir, "databases")
        val datastoreDir = File(filesDir, "datastore")
        return when (areaId) {
            "roles" -> listOf(File(filesDir, "role_workspaces"), File(filesDir, "agent_town"))
            "user_memory" -> listOf(databasesDir, datastoreDir)
            "work" -> listOf(File(filesDir, "workspaces"), File(filesDir, "created_files"), File(filesDir, "documents"))
            "sessions" -> listOf(databasesDir, File(filesDir, "workspaces"))
            "skills" -> listOf(File(filesDir, "skills"), datastoreDir)
            "mcp" -> listOf(File(filesDir, "skills"))
            "models" -> listOf(File(filesDir, "models"), datastoreDir)
            "media" -> listOf(
                File(filesDir, "apps"),
                File(filesDir, "ai_pages"),
                File(filesDir, "chat_images"),
                File(filesDir, "workspace_image_inputs"),
                File(filesDir, "icons"),
                File(filesDir, "videos"),
                File(filesDir, "documents"),
                File(filesDir, "html_pages"),
            )
            "system" -> listOf(File(filesDir, "console_web"), File(filesDir, "pip_packages"), datastoreDir)
            "tasks" -> listOf(File(filesDir, "task_replays"), File(filesDir, "task_recipes"), File(filesDir, "workspaces"))
            "play" -> listOf(File(filesDir, "agent_town"))
            "backup" -> listOf(File(filesDir, "workspace_exports"), File(filesDir, "workspace_imports"))
            else -> emptyList()
        }.filter { it.exists() }
    }

    private fun workspaceEntriesForDirectory(dir: File): List<WorkspaceFileEntryUi> =
        dir.listFiles()
            ?.asSequence()
            ?.take(200)
            ?.map { file ->
                WorkspaceFileEntryUi(
                    path = file.name + if (file.isDirectory) "/" else "",
                    absolutePath = file.absolutePath,
                    isDirectory = file.isDirectory,
                    sizeLabel = if (file.isDirectory) "Directory" else formatWorkspaceFileSize(file.length()),
                    updatedLabel = formatWorkspaceFileTime(file.lastModified()),
                    preview = if (file.isFile) workspaceFilePreview(file) else file.absolutePath,
                )
            }
            ?.sortedWith(compareBy<WorkspaceFileEntryUi> { !it.isDirectory }.thenBy { it.path.lowercase(Locale.getDefault()) })
            ?.toList()
            .orEmpty()

    private fun workspaceFilePreview(file: File): String {
        if (file.length() > 256_000L) return "Large file; showing metadata only."
        val name = file.name.lowercase(Locale.getDefault())
        val textLike = listOf(".md", ".txt", ".json", ".jsonl", ".html", ".css", ".js", ".xml", ".yml", ".yaml", ".csv", ".log")
            .any { name.endsWith(it) }
        if (!textLike) return "Binary or media file; showing metadata only."
        return runCatching { file.readText().take(1600).ifBlank { "(empty)" } }
            .getOrDefault("Could not read this file.")
    }

    private fun formatWorkspaceFileSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    }

    private fun formatWorkspaceFileTime(timestamp: Long): String =
        if (timestamp <= 0L) "Unknown time" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun promoteWorkspaceFact(memoryKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { memoryWriter.promoteScopedMemory(memoryKey) }
            refreshProfileFacts()
            loadCurrentWorkspaceSnapshot()
        }
    }

    fun deleteWorkspaceFact(memoryKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.semanticMemory.delete(memoryKey) }
            refreshProfileFacts()
            loadCurrentWorkspaceSnapshot()
        }
    }

    fun saveConfig(snapshot: ConfigSnapshot) {
        viewModelScope.launch {
            config.update(snapshot)
            navigate(AppPage.HOME)
        }
    }

    fun setModel(model: String) {
        viewModelScope.launch {
            val snap = config.snapshot()
            if (model.startsWith("local:")) {
                config.update(snap.copy(localModelEnabled = true, localModelId = model.removePrefix("local:")))
                _uiState.update { it.copy(currentModel = model) }
                return@launch
            }
            val updatedGateways = snap.gateways.map {
                if (it.id == snap.activeGatewayId || (snap.activeGatewayId == null && it == snap.gateways.firstOrNull())) {
                    val existingCapabilities = it.capabilities.filterNot { cap -> cap.type.equals("chat", ignoreCase = true) }
                    it.copy(
                        model = model,
                        capabilities = existingCapabilities + GatewayCapabilityConfig(type = "chat", model = model),
                    )
                } else it
            }
            config.update(snap.copy(gateways = updatedGateways, localModelEnabled = false, localNativeOnly = false))
            _uiState.update { it.copy(currentModel = model) }
        }
    }

    fun setLocalModelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val snap = config.snapshot()
            config.update(snap.copy(
                localModelEnabled = enabled,
                localNativeOnly = if (enabled) snap.localNativeOnly else false,
                localToolCallingEnabled = if (enabled) snap.localToolCallingEnabled else false,
            ))
        }
    }

    fun setLocalNativeOnly(enabled: Boolean) {
        viewModelScope.launch {
            val snap = config.snapshot()
            config.update(snap.copy(localNativeOnly = enabled, localModelEnabled = if (enabled) true else snap.localModelEnabled))
        }
    }

    fun setLocalToolCallingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val snap = config.snapshot()
            config.update(snap.copy(
                localToolCallingEnabled = enabled,
                localModelEnabled = if (enabled) true else snap.localModelEnabled,
            ))
        }
    }

    fun selectLocalModel(id: String) {
        viewModelScope.launch {
            val normalized = app.localModelManager.runnableModelIdFor(id) ?: id.removePrefix("local:")
            val snap = config.snapshot()
            config.update(snap.copy(localModelEnabled = true, localModelId = normalized))
            _uiState.update { it.copy(currentModel = "local:$normalized") }
        }
    }

    fun downloadLocalModel(id: String, token: String = "", sourceUrl: String = "") {
        viewModelScope.launch { app.localModelManager.download(id, token, sourceUrl) }
    }

    fun importLocalModel(id: String, uri: android.net.Uri) {
        viewModelScope.launch { app.localModelManager.importModel(id, uri) }
    }

    fun deleteLocalModel(id: String) {
        viewModelScope.launch { app.localModelManager.delete(id) }
    }

    fun fetchModels() {
        if (_uiState.value.modelsLoading) return
        _uiState.update { it.copy(modelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val snap = config.snapshot()
            val remoteModels = if (snap.localNativeOnly) {
                emptyList()
            } else {
                runCatching { OpenAiGateway.fetchModels(snap.chatEndpoint, snap.chatApiKey) }.getOrDefault(emptyList())
            }
            val localModels = app.localModelManager.models.value
                .filter { it.supportsChatRuntime }
                .map { it.modelId }
            val models = (remoteModels + localModels).distinct()
            _uiState.update { it.copy(
                availableModels = if (models.isNotEmpty()) models else it.availableModels,
                modelsLoading = false,
            ) }
        }
    }

    fun fetchGatewayModels(gatewayId: String) {
        if (gatewayId.isBlank()) return
        if (gatewayId in _uiState.value.gatewayModelsLoadingIds) return
        _uiState.update { it.copy(gatewayModelsLoadingIds = it.gatewayModelsLoadingIds + gatewayId) }
        viewModelScope.launch(Dispatchers.IO) {
            val snap = config.snapshot()
            val gateway = snap.gateways.firstOrNull { it.id == gatewayId }
            val models = gateway?.let {
                runCatching {
                    OpenAiGateway.fetchModels(
                        it.capabilityEndpoint("chat"),
                        it.capabilityApiKey("chat"),
                    )
                }.getOrDefault(emptyList())
            }.orEmpty()
            _uiState.update { state ->
                val nextModels = if (models.isNotEmpty()) {
                    state.gatewayModels + (gatewayId to models)
                } else {
                    state.gatewayModels
                }
                state.copy(
                    gatewayModels = nextModels,
                    gatewayModelsLoadingIds = state.gatewayModelsLoadingIds - gatewayId,
                )
            }
        }
    }

    fun testVirtualDisplay() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { app.virtualDisplayManager.testSupport() }
                .getOrElse { "error:${it.message}" }
            _uiState.update { it.copy(virtualDisplayTestResult = result) }
        }
    }

    fun clearVirtualDisplayResult() {
        _uiState.update { it.copy(virtualDisplayTestResult = null) }
    }

    // ── Skill Manager ────────────────────────────────────────────────────────

    fun promoteSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loader.promote(skillId) }
            refreshPromotableSkills()
        }
    }

    fun demoteSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loader.demote(skillId) }
            refreshPromotableSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loader.delete(skillId) }
            refreshPromotableSkills()
        }
    }

    fun installMarketSkill(def: com.mobileclaw.skill.SkillDefinition) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { loader.persist(def) }
            refreshPromotableSkills()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(app, str(R.string.installed_skill, def.meta.name), Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: str(R.string.vm_not_)
                    Toast.makeText(app, str(R.string.install_failed, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshPromotableSkills() {
        val all = registry.userVisibleWithEffectiveLevel()
        val promotable = all.filter { !it.isBuiltin && it.injectionLevel == 2 }
        _uiState.update { it.copy(promotableSkills = promotable, allSkills = all) }
    }

    fun setSkillLevel(skillId: String, level: Int) {
        val defaultLevel = registry.get(skillId)?.meta?.injectionLevel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (level == defaultLevel) {
                // Reset to default — remove override
                app.skillLevelStore.remove(skillId)
                registry.removeLevelOverride(skillId)
            } else {
                app.skillLevelStore.set(skillId, level)
                registry.setLevelOverride(skillId, level)
            }
            withContext(Dispatchers.Main) { refreshPromotableSkills() }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun onAppForegroundChanged(foreground: Boolean) {
        if (foreground) {
            overlay.hideCompleted()
            return
        }
        if (overlay.state.visible && !overlay.state.completed) {
            overlay.collapseToCompactIfRunning()
        }
    }

    private fun isMobileClawForegroundNow(): Boolean {
        val foregroundPackage = ClawAccessibilityService.getCurrentPackageOrNull()
        return if (foregroundPackage != null) {
            foregroundPackage == app.packageName
        } else {
            app.isAppForeground()
        }
    }

    private fun showCompletionOverlayIfNeeded(summary: String) {
        if (isMobileClawForegroundNow()) {
            overlay.hide()
        } else {
            overlay.showCompleted(summary)
        }
    }

    private fun shouldUseScheduledRoleForRun(
        goal: String,
        taskType: TaskType,
        currentRole: Role,
        scheduledRole: Role,
    ): Boolean {
        return true
    }

    private fun requiresUserExecutionConfirmation(route: TaskRoute): Boolean {
        return false
    }

    private fun shouldPushAccessibilityCardForGoal(goal: String): Boolean {
        if (activeWorkflowForCurrentSession()?.taskType == TaskType.PHONE_CONTROL) return false
        return MainExecutionSemantics.hasExplicitPhoneControlIntent(goal)
    }

    private fun shouldRunDirectChat(
        route: TaskRoute,
        roleControlPlan: RoleChatControlPlan,
        goal: String,
    ): Boolean {
        if (roleControlPlan.executionModeHint == ChatExecutionMode.AGENT) return false
        if (roleControlPlan.executionModeHint == ChatExecutionMode.DIRECT_CHAT &&
            route.taskType in setOf(TaskType.CHAT, TaskType.GENERAL) &&
            route.contextualIntent.aiToolHints.isEmpty() &&
            route.contextualIntent.aiSupportingChannels.isEmpty()) {
            return true
        }
        if (route.contextualIntent.disableToolNarrowing) return false
        if (route.contextualIntent.aiPrimaryChannel == ChannelType.INFO) return true
        if (roleControlPlanPrefersAgent(goal, roleControlPlan)) return false
        if (route.taskType == TaskType.CHAT) {
            return route.contextualIntent.aiSupportingChannels.isEmpty() &&
                route.contextualIntent.aiToolHints.isEmpty()
        }
        if (route.taskType != TaskType.GENERAL) return false
        if (route.contextualIntent.aiPrimaryChannel != null) {
            return route.contextualIntent.aiPrimaryChannel == ChannelType.CHAT &&
                route.contextualIntent.aiSupportingChannels.isEmpty() &&
                route.contextualIntent.aiToolHints.isEmpty()
        }
        return false
    }

    private fun roleControlPlanPrefersAgent(goal: String, roleControlPlan: RoleChatControlPlan): Boolean {
        val memoryIntent = MainExecutionSemantics.hasMemoryIntent(goal)
        if (memoryIntent && (roleControlPlan.persistencePolicy.allowRoleMemoryWrite || roleControlPlan.persistencePolicy.allowUserMemoryWrite)) {
            return true
        }
        val actionIntent = MainExecutionSemantics.hasExecutionIntent(goal)
        return actionIntent && roleControlPlan.toolPolicy.preferredToolIds.isNotEmpty()
    }

    private fun TaskRoute.primaryChannelForExecution(): ChannelType =
        contextualIntent.aiPrimaryChannel ?: when (taskType) {
            TaskType.PHONE_CONTROL -> ChannelType.PHONE
            TaskType.CHAT, TaskType.GENERAL ->
                if (looksLikeCapabilityInfoQuestion(contextualIntent.classificationGoal)) ChannelType.INFO else ChannelType.CHAT
            TaskType.WEB_RESEARCH -> ChannelType.WEB
            TaskType.FILE_CREATE, TaskType.APP_BUILD -> ChannelType.ARTIFACT
            TaskType.IMAGE_GENERATION -> ChannelType.MEDIA
            TaskType.VPN_CONTROL -> ChannelType.VPN
            TaskType.SKILL_MANAGEMENT -> ChannelType.SKILL
            TaskType.CODE_EXECUTION -> ChannelType.CODE
        }

    private fun looksLikeCapabilityInfoQuestion(goal: String): Boolean =
        MainExecutionSemantics.isCapabilityInfoQuestion(goal)

    private suspend fun buildMobileClawCapabilityDirectory(goal: String): String {
        val metas = registry.userVisibleMetasWithTaxonomy()
        val byCategory = metas.flatMap { meta ->
            meta.categories.ifEmpty { emptyList() }.map { category -> category to meta }
        }.groupBy({ it.first }, { it.second })
        val snap = config.snapshot()
        val accessibility = if (ClawAccessibilityService.isEnabled()) "enabled" else "not enabled; accessibility permission is required"
        val codexDesktop = if (
            _uiState.value.userConfigEntries["codex_desktop_endpoint"]?.value.orEmpty().isNotBlank() &&
            _uiState.value.userConfigEntries["codex_desktop_token"]?.value.orEmpty().isNotBlank()
        ) "configured" else "not configured"
        val imageReady = registry.contains("generate_image") && (
            snap.activeGateway?.hasCapability("image") == true ||
                userConfig.get("image_api_endpoint")?.isNotBlank() == true ||
                userConfig.get("huggingface_api_key")?.isNotBlank() == true
            )
        val videoReady = registry.contains("generate_video") && (
            snap.activeGateway?.hasCapability("video") == true ||
                userConfig.get("video_api_endpoint")?.isNotBlank() == true
            )
        fun examples(category: SkillToolCategory, limit: Int = 4): String {
            val items = byCategory[category].orEmpty()
                .distinctBy { it.id }
                .filterNot { it.internalTool }
                .sortedWith(compareBy<SkillMeta> { it.injectionLevel }.thenBy { it.id })
                .take(limit)
                .map { it.name }
            return items.joinToString(", ").ifBlank { "No visible tools found" }
        }
        return """
I can help in these areas. The relevant capability directory is loaded for each task rather than filling every chat with every tool:

- Ordinary chat and reasoning: answer questions, explain concepts, write and edit text, translate, plan, and tutor.
- Phone operation: inspect the screen, tap, type, scroll, open apps, and complete multi-step flows. Status: $accessibility. Example tools: ${examples(SkillToolCategory.PHONE)}.
- Artifact creation: create MiniAPPs, native pages, dashboards, forms, HTML/CSS/JS, files, and documents. Example tools: ${examples(SkillToolCategory.ARTIFACT)}.
- Web and research: search, open webpages, read their content, and summarize findings. Example tools: ${examples(SkillToolCategory.WEB)}.
- Image and video: image generation is ${if (imageReady) "available" else "not fully configured"}; video generation is ${if (videoReady) "available" else "not fully configured"}. Example tools: ${examples(SkillToolCategory.MEDIA)}.
- Memory and configuration: remember preferences, use conversation and workspace context, and manage default configuration. Example tools: ${examples(SkillToolCategory.MEMORY)}.
- Skills and self-improvement: install or create skills, switch roles, and adjust page or capability policies. Example tools: ${examples(SkillToolCategory.SKILL)}.
- Desktop Codex collaboration: send complex development tasks to the desktop Codex bridge. Status: $codexDesktop.

For example: "Create an expense-tracker MiniAPP", "Open Settings and change the display option", "Summarize this webpage", or "Remember that I prefer a minimal dark style". I will route the request to ordinary chat, the capability directory, or agent execution as appropriate.
        """.trimIndent()
    }

    private fun isRecentContinuationRoute(route: TaskRoute, goal: String): Boolean {
        if (route.source != TaskRouteSource.RECENT_CONTEXT) return false
        if (route.taskType == TaskType.PHONE_CONTROL && !isPhoneControlReady()) return false
        return MainExecutionSemantics.isRecentContinuationCommand(goal)
    }

    private fun resolveAllowedToolIds(
        route: TaskRoute,
        hintedToolIds: List<String>,
        goal: String,
    ): List<String> {
        return emptyList()
    }

    private fun requestTaskExecutionConfirmation(goal: String, taskType: TaskType, confirmedRoute: TaskRoute? = null) {
        if (taskType == TaskType.PHONE_CONTROL && !isPhoneControlReady()) {
            pendingAccessibilityTaskGoal = goal
            appendConfirmationExchange(
                goal,
                ConfirmationFlow.accessibilityActionCard(
                    goal = goal,
                ),
            )
            return
        }
        if (confirmedRoute != null) {
            synchronized(pendingConfirmedRoutes) {
                pendingConfirmedRoutes[goal] = confirmedRoute
            }
        }
        appendConfirmationExchange(
            goal,
            ConfirmationFlow.taskConfirmationCard(
                goal = goal,
                taskType = taskType,
            ),
        )
    }

    private fun isPhoneControlReady(): Boolean =
        app.deviceReadinessEngine.evaluate(DeviceCapability.PHONE_CONTROL).level != ReadinessLevel.BLOCKED

    private fun requestRoleSwitchConfirmation(goal: String, role: Role) {
        pendingRoleSwitchTaskGoal = goal
        appendConfirmationExchange(
            goal,
            ConfirmationFlow.roleSwitchConfirmationCard(
                goal = goal,
                role = role,
            ),
        )
    }

    private fun appendConfirmationExchange(userText: String, card: SkillAttachment.ActionCard) {
        viewModelScope.launch {
            var sessionId = _uiState.value.currentSessionId
            if (sessionId.isBlank()) {
                withContext(Dispatchers.IO) { createNewSessionInternal() }
                sessionId = _uiState.value.currentSessionId
            }
            val userMessage = ChatMessage(MessageRole.USER, userText)
            val agentMessage = ChatMessage(
                role = MessageRole.AGENT,
                text = "",
                attachments = listOf(card),
                senderRoleId = _uiState.value.currentRole.id,
                senderRoleName = _uiState.value.currentRole.name,
                senderRoleAvatar = _uiState.value.currentRole.avatar,
            )
            updateSession(sessionId) { s -> s.copy(messages = s.messages + userMessage + agentMessage) }
            if (sessionId.isNotBlank()) {
                launch(Dispatchers.IO) { persistMessages(sessionId, userMessage, listOf(agentMessage)) }
            }
        }
    }

    fun checkAppUpdateOnLaunch() {
        if (appUpdateCheckedThisRun) return
        appUpdateCheckedThisRun = true
        checkAppUpdate(showResultInChat = false, showNoUpdateDialog = false)
    }

    fun checkAppUpdate(
        showResultInChat: Boolean = false,
        showNoUpdateDialog: Boolean = true,
    ) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    appUpdate = state.appUpdate.copy(
                        checking = true,
                        installing = false,
                        showDialog = showNoUpdateDialog,
                        errorMessage = "",
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                PgyerReleaseSkill(app, userConfig).checkUpdateInfo()
            }
            result.fold(
                onSuccess = { info ->
                    _uiState.update { state ->
                        state.copy(
                            appUpdate = AppUpdateUiState(
                                checking = false,
                                installing = false,
                                showDialog = info.hasNewVersion || showNoUpdateDialog,
                                hasNewVersion = info.hasNewVersion,
                                currentVersion = info.currentVersion,
                                currentVersionCode = info.currentVersionCode,
                                remoteVersion = info.remoteVersion,
                                remoteVersionCode = info.remoteVersionCode,
                                releaseNotes = info.releaseNotes,
                                downloadUrl = info.downloadUrl,
                                installUrl = info.installUrl,
                                checkedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    if (showResultInChat) {
                        appendConfirmationResolution(formatAppUpdateInfoResult(info))
                    }
                },
                onFailure = { error ->
                    val message = error.message.orEmpty().ifBlank { "Network or configuration error" }
                    _uiState.update { state ->
                        state.copy(
                            appUpdate = state.appUpdate.copy(
                                checking = false,
                                installing = false,
                                showDialog = showNoUpdateDialog,
                                errorMessage = message,
                                checkedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    if (showResultInChat) {
                        appendConfirmationResolution("Update check failed: ${message.releaseMessageForUser()}")
                    }
                }
            )
        }
    }

    fun dismissAppUpdateDialog() {
        _uiState.update { state -> state.copy(appUpdate = state.appUpdate.copy(showDialog = false)) }
    }

    fun installAppUpdate() {
        val state = _uiState.value.appUpdate
        if (state.downloadUrl.isBlank() && state.installUrl.isBlank()) {
            _uiState.update {
                it.copy(appUpdate = it.appUpdate.copy(showDialog = true, errorMessage = "The update service did not return a downloadable URL."))
            }
            return
        }
        val info = PgyerUpdateInfo(
            hasNewVersion = state.hasNewVersion,
            currentVersion = state.currentVersion,
            currentVersionCode = state.currentVersionCode,
            remoteVersion = state.remoteVersion,
            remoteVersionCode = state.remoteVersionCode,
            downloadUrl = state.downloadUrl,
            installUrl = state.installUrl,
            releaseNotes = state.releaseNotes,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(appUpdate = it.appUpdate.copy(installing = true, errorMessage = "")) }
            val result = withContext(Dispatchers.IO) {
                PgyerReleaseSkill(app, userConfig).downloadAndOpenInstaller(info)
            }
            _uiState.update { current ->
                current.copy(
                    appUpdate = current.appUpdate.copy(
                        installing = false,
                        showDialog = !result.success,
                        errorMessage = if (result.success) "" else result.output.releaseMessageForUser(),
                    )
                )
            }
            Toast.makeText(
                app,
                if (result.success) "Opened the update installer." else "Update failed: ${result.output.releaseMessageForUser()}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun formatAppUpdateInfoResult(info: PgyerUpdateInfo): String =
        AppUpdatePresentation.formatInfo(
            hasNewVersion = info.hasNewVersion,
            currentVersion = info.currentVersion,
            currentVersionCode = info.currentVersionCode,
            remoteVersion = info.remoteVersion,
            releaseNotes = info.releaseNotes,
        )

    private fun formatAppUpdateResult(success: Boolean, rawOutput: String): String =
        AppUpdatePresentation.formatRawResult(success, rawOutput)

    private fun String.releaseMessageForUser(): String =
        with(AppUpdatePresentation) { this@releaseMessageForUser.releaseMessageForUser() }

    private fun appendConfirmationResolution(text: String) {
        viewModelScope.launch {
            var sessionId = _uiState.value.currentSessionId
            if (sessionId.isBlank()) {
                withContext(Dispatchers.IO) { createNewSessionInternal() }
                sessionId = _uiState.value.currentSessionId
            }
            val agentMessage = ChatMessage(
                role = MessageRole.AGENT,
                text = text,
                senderRoleId = _uiState.value.currentRole.id,
                senderRoleName = _uiState.value.currentRole.name,
                senderRoleAvatar = _uiState.value.currentRole.avatar,
            )
            updateSession(sessionId) { s -> s.copy(messages = s.messages + agentMessage) }
            if (sessionId.isNotBlank()) {
                launch(Dispatchers.IO) {
                    database.sessionMessageDao().insert(
                        SessionMessageEntity(
                            sessionId = sessionId,
                            role = "agent",
                            text = agentMessage.text,
                            senderRoleId = agentMessage.senderRoleId,
                            senderRoleName = agentMessage.senderRoleName,
                            senderRoleAvatar = agentMessage.senderRoleAvatar,
                        )
                    )
                }
            }
        }
    }

    private fun inferExplicitRoleSwitch(goal: String): ExplicitRoleSwitch? {
        return ConfirmationFlow.inferExplicitRoleSwitch(goal, _uiState.value.availableRoles + Role.BUILTINS)
    }

    private fun activeWorkflowForCurrentSession(): ActiveWorkflow? {
        val sessionId = _uiState.value.currentSessionId
        return activeWorkflows[sessionId]?.takeIf { System.currentTimeMillis() - it.updatedAt < ACTIVE_WORKFLOW_TTL_MS }
    }

    private fun rememberActiveWorkflow(sessionId: String, goal: String, taskType: TaskType, role: Role) {
        if (sessionId.isBlank()) return
        if (taskType in listOf(TaskType.CHAT, TaskType.GENERAL)) return
        activeWorkflows[sessionId] = ActiveWorkflow(
            originalGoal = sanitizeWorkflowGoal(goal).take(ACTIVE_WORKFLOW_GOAL_LIMIT),
            taskType = taskType,
            roleId = role.id,
        )
    }

    private fun sanitizeWorkflowGoal(goal: String): String =
        goal
            .replace(Regex("""\n\n\[resolved_context:[\s\S]*$"""), "")
            .trim()
            .ifBlank { goal.take(ACTIVE_WORKFLOW_GOAL_LIMIT) }

    private fun buildPriorContext(
        goal: String,
        taskType: TaskType = TaskType.GENERAL,
        intent: ContextualTaskIntent = ContextualTaskIntent(goal),
        includeMemory: Boolean = true,
        includeRecentMessages: Boolean = true,
    ): String = chatContextComposer.buildPriorContext(goal, taskType, intent, includeMemory, includeRecentMessages)

    private fun persistWorkspaceObservation(
        sessionId: String,
        skillId: String?,
        rawOutput: String,
    ) {
        workspaceRecorder.persistObservation(sessionId, skillId, rawOutput)
    }

    private fun persistRuntimeWorkspaceUpdate(
        sessionId: String,
        goal: String,
        update: AgentWorkspaceUpdate,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceRecorder.persistRuntimeUpdate(sessionId, goal, update) }
        }
    }

    private fun buildStructuredDirectChatMessages(
        sessionId: String,
        systemPrompt: String,
        currentGoal: String,
        imageBase64: String? = null,
    ): List<Message> = chatContextComposer.buildStructuredDirectChatMessages(
        sessionMessages = _uiState.value.sessionStates[sessionId]?.messages.orEmpty(),
        systemPrompt = systemPrompt,
        currentGoal = currentGoal,
        imageBase64 = imageBase64,
    )

    private fun buildUserMemoryContextForPrompt(goal: String, taskType: TaskType, activeWorkspaceId: String? = null): String =
        runCatching {
            val state = _uiState.value
            val semanticFacts = state.profileState.semanticFacts
            if (semanticFacts.isNotEmpty()) {
                memoryContextBuilder.buildFromSnapshots(
                    userMessage = goal,
                    taskType = taskType,
                    userConfigEntries = state.userConfigEntries,
                    facts = semanticFacts,
                    activeSessionScopeId = activeWorkspaceId,
                ).toPrompt()
            } else {
                memoryContextBuilder.buildFromSnapshots(
                    userMessage = goal,
                    taskType = taskType,
                    userConfigEntries = state.userConfigEntries,
                    facts = state.profileState.facts,
                    activeSessionScopeId = activeWorkspaceId,
                ).toPrompt()
            }
        }.getOrDefault("")

    private companion object {
        const val ACTIVE_WORKFLOW_TTL_MS = 30 * 60 * 1000L
        const val ACTIVE_WORKFLOW_GOAL_LIMIT = 3000
    }

    private fun registerBuiltinSkills() {
        listOf(
            // Screen perception
            ScreenshotSkill(),
            ReadScreenSkill(),
            SeeScreenSkill(),
            // Interaction
            TapSkill(),
            LongClickSkill(),
            ScrollSkill(),
            InputTextSkill(),
            NavigateSkill(app.virtualDisplayManager, app.installedAppResolver),
            ListAppsSkill(app.installedAppCatalog),
            PhoneStatusSkill(),
            // Web
            WebSearchSkill(app.webViewManager),
            FetchUrlSkill(),
            WebBrowseSkill(app.webViewManager),
            WebContentSkill(app.webViewManager),
            WebJsSkill(app.webViewManager),
            // Content creation
            GenerateImageSkill(config, app.userConfig),
            GenerateIconSkill(app, config, app.userConfig, app.miniAppStore, roleManager),
            GenerateDocumentSkill(app),
            GenerateVideoSkill(config, app, app.userConfig, videoTaskManager),
            CreateFileSkill(app),
            ReadFileSkill(app),
            ListFilesSkill(app),
            CreateHtmlSkill(app),
            // Virtual display (background execution)
            BgLaunchSkill(app.virtualDisplayManager, app.installedAppResolver),
            BgReadScreenSkill(app.virtualDisplayManager),
            BgScreenshotSkill(app.virtualDisplayManager),
            BgStopSkill(app.virtualDisplayManager),
            VirtualDisplaySetupSkill(app.virtualDisplayManager),
            // System
            ShellSkill(),
            PipInstallSkill(),
            RunPythonSkill(),
            CodexDesktopSkill(userConfig),
            PgyerReleaseSkill(app, userConfig),
            ClipboardSkill(),
            ShowToastSkill(),
            DeviceInfoSkill(),
            MemorySkill(app.semanticMemory),
            com.mobileclaw.skill.builtin.UserProfileSkill(app.semanticMemory, userConfig),
            PermissionSkill(app.permissionManager),
            // Skill management
            MetaSkill(loader),
            SkillCheckSkill(registry),
            QuickSkillSkill(llm, loader),
            SkillMarketSkill(loader),
            McpClientSkill(),
            McpConnectSkill(loader),
            // Dynamic config, model & role
            SwitchModelSkill(config),
            UserConfigSkill(userConfig, app.semanticMemory),
            switchRoleSkill,
            RoleManagerSkill(roleManager, roleRequests, RolePackageStore(app, roleManager, app.roleWorkspaceStore)),
            RoleWorkspaceSkill(roleManager, app.roleWorkspaceStore, config) { registry.allMetasWithTaxonomy() },
            AiHomeAssetSkill(townStore, roleManager),
            TownBuilderSkill(townStore, roleManager),
            // Session management
            SessionManagerSkill(database.sessionDao(), sessionRequests, mutationGuard = ::guardSessionMutation),
            // App navigation
            PageControlSkill(pageRequests),
            // Skill notes
            SkillNotesSkill(app.skillNotesStore),
            // Mini-app manager
            appManagerSkill,
            // AI native page builder
            uiBuilderSkill,
            // Task replay, recipes, and quick-entry pages
            TaskRecipeSkill(app.taskReplayStore, app.taskRecipeStore, app.aiPageStore, aiPageOpenRequests, app.pendingAgentTask),
            // User external storage management
            com.mobileclaw.skill.builtin.UserStorageSkill(app.userStorageManager),
            // Internal workspace management
            WorkspaceManagerSkill(app.workspaceStore),
            // LAN console page editor
            com.mobileclaw.skill.builtin.ConsoleEditorSkill(consoleServer),
        ).forEach { registry.register(it) }
    }

    fun loadVideoTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            val tasks = runCatching { videoTaskManager.recent() }.getOrDefault(emptyList())
            _uiState.update { it.copy(videoTasks = tasks) }
            scheduleVideoTaskAutoRefresh(tasks)
        }
    }

    fun refreshVideoTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(videoTaskRefreshingIds = it.videoTaskRefreshingIds + taskId) }
            runCatching { videoTaskManager.refresh(taskId) }
            val tasks = runCatching { videoTaskManager.recent() }.getOrDefault(_uiState.value.videoTasks)
            _uiState.update {
                it.copy(
                    videoTasks = tasks,
                    videoTaskRefreshingIds = it.videoTaskRefreshingIds - taskId,
                )
            }
            scheduleVideoTaskAutoRefresh(tasks)
        }
    }

    fun refreshPendingVideoTasks() {
        refreshPendingVideoTasksInternal(showSpinner = true)
    }

    fun generateImage(request: ImageGenerationRequest) {
        if (_uiState.value.imageGenerationRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(imageGenerationRunning = true) }
            try {
                val result = GenerateImageSkill(config, app.userConfig)
                    .execute(request.toSkillParams())
                if (result.success && !result.imageBase64.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            imageGenerationPreviewBase64 = result.imageBase64,
                            imageGenerationPreviewPrompt = request.prompt,
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        if (result.success) result.output.ifBlank { "Image generated" } else result.output.ifBlank { "Image generation failed" },
                        if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Image generation failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                _uiState.update { it.copy(imageGenerationRunning = false) }
            }
        }
    }

    fun rewriteImagePrompt(prompt: String, action: ImagePromptAiAction, onResult: (String) -> Unit) {
        if (_uiState.value.imagePromptAiRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(imagePromptAiRunning = true) }
            try {
                val rewritten = rewriteImagePromptInternal(prompt, action)
                withContext(Dispatchers.Main) { onResult(rewritten) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "LLM processing failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                _uiState.update { it.copy(imagePromptAiRunning = false) }
            }
        }
    }

    private fun rewriteImagePromptInternal(prompt: String, action: ImagePromptAiAction): String {
        val raw = prompt.trim()
        if (raw.isBlank()) return raw
        val systemPrompt = when (action) {
            ImagePromptAiAction.ENRICH -> """
                You enrich short user ideas into a strong image generation prompt.
                Return only one concise English prompt.
                Preserve the user's intent and add helpful subject details, composition, lighting, material, style, and visual constraints.
                Do not add explanations, markdown, JSON, quotes, or safety commentary.
            """.trimIndent()
            ImagePromptAiAction.TRANSLATE -> """
                You translate prompts for image generation models.
                Return only one concise English prompt.
                Preserve the user's exact intent, subject, style, composition, and constraints.
                Do not add new creative details.
                Do not add explanations, markdown, JSON, quotes, or safety commentary.
            """.trimIndent()
        }
        return callMediaPromptLlm(systemPrompt = systemPrompt, userPrompt = raw)
            .trim()
            .trim('"')
            .takeIf { it.isNotBlank() } ?: raw
    }

    fun generateVideo(request: VideoGenerationRequest) {
        if (_uiState.value.videoGenerationRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(videoGenerationRunning = true) }
            try {
                val result = GenerateVideoSkill(config, app, app.userConfig, videoTaskManager)
                    .execute(request.toSkillParams())
                loadVideoTasks()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        if (result.success) result.output.ifBlank { "Video task submitted" } else result.output.ifBlank { "Video generation failed" },
                        if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Video generation failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                _uiState.update { it.copy(videoGenerationRunning = false) }
            }
        }
    }

    fun rewriteVideoPrompt(prompt: String, action: VideoPromptAiAction, onResult: (String) -> Unit) {
        if (_uiState.value.videoPromptAiRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(videoPromptAiRunning = true) }
            try {
                val rewritten = rewriteVideoPromptInternal(prompt, action)
                withContext(Dispatchers.Main) {
                    onResult(rewritten)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "LLM processing failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                _uiState.update { it.copy(videoPromptAiRunning = false) }
            }
        }
    }

    private suspend fun rewriteVideoPromptInternal(prompt: String, action: VideoPromptAiAction): String {
        val raw = prompt.trim()
        if (raw.isBlank()) return raw
        val systemPrompt = when (action) {
            VideoPromptAiAction.ENRICH -> """
                You enrich short user ideas into a strong video generation prompt.
                Return only one concise English prompt.
                Preserve the user's intent and add helpful visual details, subject action, camera movement, lighting, atmosphere, and style.
                Do not add explanations, markdown, JSON, quotes, or safety commentary.
            """.trimIndent()
            VideoPromptAiAction.TRANSLATE -> """
                You translate prompts for video generation models.
                Return only one concise English prompt.
                Preserve the user's exact intent, subject, motion, style, camera, and constraints.
                Do not add new creative details.
                Do not add explanations, markdown, JSON, quotes, or safety commentary.
            """.trimIndent()
        }
        return callMediaPromptLlm(systemPrompt = systemPrompt, userPrompt = raw)
            .trim()
            .trim('"')
            .takeIf { it.isNotBlank() } ?: raw
    }

    private fun callMediaPromptLlm(systemPrompt: String, userPrompt: String): String {
        val snapshot = config.snapshot()
        val gateway = selectMediaPromptChatGateway(snapshot)
            ?: throw IllegalStateException("No chat-capable gateway is available for AI prompt rewriting.")
        val endpoint = gateway.capabilityEndpoint("chat").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The chat gateway endpoint is empty.")
        val apiKey = gateway.capabilityApiKey("chat").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The chat gateway API key is empty.")
        val model = gateway.capabilityModel("chat") ?: gateway.model.takeIf { it.isNotBlank() } ?: "gpt-4o"
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            })
        }
        val req = Request.Builder()
            .url("${normalizeOpenAiBase(endpoint)}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return videoPromptLlmClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Chat gateway request failed with HTTP ${resp.code}: ${extractLlmError(raw)}")
            }
            val json = JsonParser.parseString(raw).asJsonObject
            json["choices"]?.asJsonArray?.firstOrNull()
                ?.asJsonObject?.get("message")?.asJsonObject?.get("content")?.asString
                ?: throw IllegalStateException("The chat gateway returned no content.")
        }
    }

    private fun selectMediaPromptChatGateway(snapshot: ConfigSnapshot): GatewayConfig? {
        val active = snapshot.activeGateway
        return active?.takeIf { it.isUsableExplicitChatGateway() }
            ?: snapshot.gateways.firstOrNull { it.isUsableExplicitChatGateway() }
            ?: active?.takeIf { it.isLegacyChatGatewayCandidate() }
            ?: snapshot.gateways.firstOrNull { it.isLegacyChatGatewayCandidate() }
    }

    private fun GatewayConfig.isUsableExplicitChatGateway(): Boolean =
        hasCapability("chat") && capabilityEndpoint("chat").isNotBlank() && capabilityApiKey("chat").isNotBlank()

    private fun GatewayConfig.isLegacyChatGatewayCandidate(): Boolean {
        val explicitCapabilities = runCatching { capabilities }.getOrNull().orEmpty()
        return explicitCapabilities.isEmpty() && endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    private fun normalizeOpenAiBase(endpoint: String): String {
        val trimmed = endpoint.trim()
            .removeSuffix("/")
            .removeSuffix("/chat/completions")
            .trimEnd('/')
        if (trimmed.isBlank()) return trimmed
        val hasVersionSuffix = Regex("/v\\d+$", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        return if (hasVersionSuffix) trimmed else "$trimmed/v1"
    }

    private fun extractLlmError(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            val error = obj["error"]
            when {
                error == null || error.isJsonNull -> trimmed.take(240)
                error.isJsonObject -> error.asJsonObject["message"]?.asString ?: error.toString().take(240)
                error.isJsonPrimitive -> error.asString
                else -> error.toString().take(240)
            }
        }.getOrDefault(trimmed.take(240))
    }

    fun uploadVideoFrameImage(imageUri: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { videoImageUploader.uploadIfNeeded(imageUri) }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private fun refreshPendingVideoTasksInternal(showSpinner: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (showSpinner) {
                _uiState.update { it.copy(videoTasksRefreshing = true) }
            }
            runCatching { videoTaskManager.refreshPendingTasks() }
            val tasks = runCatching { videoTaskManager.recent() }.getOrDefault(_uiState.value.videoTasks)
            _uiState.update {
                it.copy(
                    videoTasks = tasks,
                    videoTasksRefreshing = if (showSpinner) false else it.videoTasksRefreshing,
                    videoTaskRefreshingIds = emptySet(),
                )
            }
            scheduleVideoTaskAutoRefresh(tasks)
        }
    }

    fun deleteVideoTask(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { videoTaskManager.delete(taskId) }
            val tasks = runCatching { videoTaskManager.recent() }.getOrDefault(emptyList())
            _uiState.update { it.copy(videoTasks = tasks, videoTaskRefreshingIds = it.videoTaskRefreshingIds - taskId) }
            scheduleVideoTaskAutoRefresh(tasks)
        }
    }

    private fun scheduleVideoTaskAutoRefresh(tasks: List<com.mobileclaw.memory.db.VideoGenerationTaskEntity>) {
        val hasPending = tasks.any { task ->
            task.status == VideoTaskStatuses.SUBMITTED ||
                task.status == VideoTaskStatuses.RUNNING ||
                task.status == VideoTaskStatuses.TIMED_OUT
        }
        if (!hasPending) {
            videoTaskAutoRefreshJob?.cancel()
            videoTaskAutoRefreshJob = null
            return
        }
        if (videoTaskAutoRefreshJob?.isActive == true) return
        videoTaskAutoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(VIDEO_TASK_AUTO_REFRESH_INTERVAL_MS)
                refreshPendingVideoTasksInternal(showSpinner = false)
                val latestTasks = _uiState.value.videoTasks
                val stillPending = latestTasks.any { task ->
                    task.status == VideoTaskStatuses.SUBMITTED ||
                        task.status == VideoTaskStatuses.RUNNING ||
                        task.status == VideoTaskStatuses.TIMED_OUT
                }
                if (!stillPending) {
                    videoTaskAutoRefreshJob = null
                    break
                }
            }
        }
    }

    private fun loadDynamicSkills() {
        runCatching { loader.loadAll() }
    }

    // ── Session Serialization ────────────────────────────────────────────────

    private val gson = Gson()

    // ── VPN ──────────────────────────────────────────────────────────────────────

    private val vpnManager = com.mobileclaw.vpn.VpnManager(app)
    private var vpnInitialized = false

    fun initVpn() {
        if (vpnInitialized) return
        vpnInitialized = true
        if (!registry.contains("vpn_control")) {
            registry.register(com.mobileclaw.vpn.VpnControlSkill(vpnManager) {
                _uiState.value.vpnSubscriptions.firstNotNullOfOrNull { sub ->
                    val proxy = sub.proxies.firstOrNull { it.id == sub.entity.selectedProxyId }
                    if (proxy != null) sub to proxy else null
                }
            })
            refreshPromotableSkills()
        }
        viewModelScope.launch {
            vpnManager.subscriptions.collect { subs ->
                _uiState.update { it.copy(vpnSubscriptions = subs) }
            }
        }
        viewModelScope.launch {
            vpnManager.status.collect { status ->
                _uiState.update { it.copy(vpnStatus = status) }
            }
        }
    }

    fun syncVpnStatus() = vpnManager.syncStatus()

    fun vpnPrepareIntent() = vpnManager.prepareIntent()

    fun startVpn(sub: com.mobileclaw.vpn.VpnSubscription, proxy: com.mobileclaw.vpn.ProxyConfig) {
        vpnManager.startVpn(sub, proxy)
    }

    fun stopVpn() = vpnManager.stopVpn()

    fun addVpnSubscription(name: String, url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(vpnAddingSubscription = true) }
            val result = vpnManager.addSubscription(name, url)
            _uiState.update { it.copy(vpnAddingSubscription = false) }
            if (result.isFailure) {
                android.widget.Toast.makeText(app,
                    "Failed: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updateVpnSubscription(sub: com.mobileclaw.vpn.VpnSubscription) {
        viewModelScope.launch {
            val result = vpnManager.updateSubscription(sub)
            if (result.isFailure) {
                android.widget.Toast.makeText(app,
                    "Update failed: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteVpnSubscription(id: String) {
        viewModelScope.launch { vpnManager.deleteSubscription(id) }
    }

    fun selectVpnProxy(subId: String, proxyId: String?) {
        viewModelScope.launch { vpnManager.selectProxy(subId, proxyId) }
    }

    fun testAllVpnLatencies(sub: com.mobileclaw.vpn.VpnSubscription) {
        viewModelScope.launch {
            // Mark all proxies as "testing"
            val testing = sub.proxies.associate { it.id to LATENCY_TESTING }
            _uiState.update { it.copy(vpnLatencies = it.vpnLatencies + testing) }
            vpnManager.testAllLatencies(sub) { proxyId, ms ->
                _uiState.update { it.copy(vpnLatencies = it.vpnLatencies + (proxyId to ms)) }
            }
        }
    }

    fun testVpnProxyReachable(onResult: (String) -> Unit) {
        viewModelScope.launch { onResult(vpnManager.testProxyReachable()) }
    }

    private fun deserializeAttachments(raw: String): List<SkillAttachment> {
        return runCatching {
            val arr = JsonParser.parseString(raw.ifBlank { "[]" }).asJsonArray
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                when (o["type"]?.asString) {
                    "image" -> SkillAttachment.ImageData(
                        base64 = o["base64"]?.asString ?: "",
                        prompt = o["prompt"]?.asString?.ifBlank { null },
                        localPath = o["localPath"]?.asString ?: "",
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                    "file" -> SkillAttachment.FileData(
                        path = o["path"]?.asString ?: "",
                        name = o["name"]?.asString ?: "",
                        mimeType = o["mimeType"]?.asString ?: "",
                        sizeBytes = o["sizeBytes"]?.asString?.toLongOrNull() ?: 0L,
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                    "html" -> SkillAttachment.HtmlData(
                        path = o["path"]?.asString ?: "",
                        title = o["title"]?.asString ?: "",
                        htmlContent = o["htmlContent"]?.asString ?: "",
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                    "webpage" -> SkillAttachment.WebPage(
                        url = o["url"]?.asString ?: "",
                        title = o["title"]?.asString ?: "",
                        excerpt = o["excerpt"]?.asString ?: "",
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                    "search_results" -> {
                        val pages = o["pages"]?.asJsonArray?.mapNotNull { pe ->
                            val p = pe.asJsonObject
                            SkillAttachment.WebPage(
                                url = p["url"]?.asString ?: return@mapNotNull null,
                                title = p["title"]?.asString ?: "",
                                excerpt = p["excerpt"]?.asString ?: "",
                            )
                        } ?: emptyList()
                        SkillAttachment.SearchResults(
                            o["query"]?.asString ?: "",
                            o["engine"]?.asString ?: "",
                            pages,
                            instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                        )
                    }
                    "file_list" -> {
                        val files = o["files"]?.asJsonArray?.mapNotNull { fe ->
                            val f = fe.asJsonObject
                            SkillAttachment.FileList.FileEntry(
                                path = f["path"]?.asString ?: return@mapNotNull null,
                                name = f["name"]?.asString ?: "",
                                mimeType = f["mimeType"]?.asString ?: "text/plain",
                                sizeBytes = f["sizeBytes"]?.asString?.toLongOrNull() ?: 0L,
                            )
                        } ?: emptyList()
                        SkillAttachment.FileList(
                            files,
                            o["directory"]?.asString ?: "",
                            instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                        )
                    }
                    "action_card" -> {
                        val actions = o["actions"]?.asJsonArray?.mapNotNull { ae ->
                            val a = ae.asJsonObject
                            SkillAttachment.ActionCard.Action(
                                label = a["label"]?.asString ?: return@mapNotNull null,
                                message = a["message"]?.asString ?: return@mapNotNull null,
                                style = a["style"]?.asString ?: "secondary",
                            )
                        }.orEmpty()
                        ConfirmationActionProtocol.migrateLegacyCard(
                            title = o["title"]?.asString ?: "",
                            body = o["body"]?.asString ?: "",
                            actions = actions,
                            tone = o["tone"]?.asString ?: "default",
                            instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                        )
                    }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeAttachments(attachments: List<SkillAttachment>): String {
        val list = attachments.map { att ->
            when (att) {
                is SkillAttachment.ImageData -> serializeImageAttachment(att)
                is SkillAttachment.FileData  -> mapOf("type" to "file", "path" to att.path, "name" to att.name, "mimeType" to att.mimeType, "sizeBytes" to att.sizeBytes.toString(), "instanceId" to att.instanceId)
                is SkillAttachment.HtmlData  -> mapOf("type" to "html", "path" to att.path, "title" to att.title, "instanceId" to att.instanceId)
                is SkillAttachment.WebPage   -> mapOf("type" to "webpage", "url" to att.url, "title" to att.title, "excerpt" to att.excerpt, "instanceId" to att.instanceId)
                is SkillAttachment.SearchResults -> mapOf(
                    "type" to "search_results",
                    "query" to att.query,
                    "engine" to att.engine,
                    "pages" to att.pages.map { p -> mapOf("url" to p.url, "title" to p.title, "excerpt" to p.excerpt) },
                    "instanceId" to att.instanceId,
                )
                is SkillAttachment.AccessibilityRequest -> null
                is SkillAttachment.ActionCard -> mapOf(
                    "type" to "action_card",
                    "title" to att.title,
                    "body" to att.body,
                    "tone" to att.tone,
                    "instanceId" to att.instanceId,
                    "actions" to att.actions.map { action ->
                        mapOf("label" to action.label, "message" to action.message, "style" to action.style)
                    },
                )
                is SkillAttachment.FileList -> mapOf(
                    "type" to "file_list",
                    "directory" to att.directory,
                    "instanceId" to att.instanceId,
                    "files" to att.files.map { f -> mapOf("path" to f.path, "name" to f.name, "mimeType" to f.mimeType, "sizeBytes" to f.sizeBytes.toString()) },
                )
            }
        }.filterNotNull()
        return gson.toJson(list)
    }

    private fun serializeImageAttachment(att: SkillAttachment.ImageData): Map<String, String> {
        if (att.localPath.isNotBlank()) {
            return mapOf(
                "type" to "image",
                "base64" to att.base64,
                "prompt" to (att.prompt ?: ""),
                "localPath" to att.localPath,
                "instanceId" to att.instanceId,
            )
        }
        if (att.base64.length <= 500_000) {
            return mapOf("type" to "image", "base64" to att.base64, "prompt" to (att.prompt ?: ""), "instanceId" to att.instanceId)
        }
        val file = persistImageDataUri(att.base64)
        return if (file != null) {
            mapOf(
                "type" to "file",
                "path" to file.absolutePath,
                "name" to file.name,
                "mimeType" to mimeTypeFromDataUri(att.base64),
                "sizeBytes" to file.length().toString(),
            )
        } else {
            mapOf("type" to "image", "base64" to "", "prompt" to (att.prompt ?: ""))
        }
    }

    private fun persistImageDataUri(dataUri: String): File? = runCatching {
        val raw = dataUri.substringAfter("base64,", missingDelimiterValue = dataUri.substringAfter(",", dataUri))
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val mime = mimeTypeFromDataUri(dataUri)
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val dir = File(app.filesDir, "chat_images").also { it.mkdirs() }
        File(dir, "img_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext").also { it.writeBytes(bytes) }
    }.getOrNull()

    private fun persistUserImageForWorkspace(sessionId: String, dataUri: String): String? = runCatching {
        val raw = dataUri.substringAfter("base64,", missingDelimiterValue = dataUri.substringAfter(",", dataUri))
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val mime = mimeTypeFromDataUri(dataUri)
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val workspaceId = workspaceRuntime.resolveSessionWorkspaceId(sessionId)
        val path = if (workspaceId != null) {
            app.workspaceStore.writeBytes(
                id = workspaceId,
                relativeDir = "inputs",
                name = "user_image_${System.currentTimeMillis()}.$ext",
                bytes = bytes,
            )
        } else {
            val dir = File(app.filesDir, "workspace_image_inputs").also { it.mkdirs() }
            File(dir, "user_image_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext").also { it.writeBytes(bytes) }.absolutePath
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { userConfig.set("latest_image_local_path", path, "Most recent user-attached image local path for image-to-video and media tools") }
        }
        path
    }.getOrNull()

    private fun mimeTypeFromDataUri(dataUri: String): String =
        dataUri.substringAfter("data:", "image/jpeg").substringBefore(";").ifBlank { "image/jpeg" }
}

private fun SessionMessageEntity.toChatMessage(): ChatMessage {
    val gson = Gson()
    val logLines = runCatching {
        val arr = JsonParser.parseString(logLinesJson).asJsonArray
        arr.map { el ->
            val o = el.asJsonObject
            LogLine(
                entryId = o["entryId"]?.asString ?: "",
                type = runCatching { LogType.valueOf(o["type"]?.asString ?: "INFO") }.getOrDefault(LogType.INFO),
                text = o["text"]?.asString ?: "",
                skillId = o["skillId"]?.asString,
                imageBase64 = null, // stripped on save
                details = runCatching { o["details"]?.asJsonArray?.map { it.asString } ?: emptyList() }.getOrDefault(emptyList()),
                startedAt = o["startedAt"]?.asLong ?: 0L,
                finishedAt = o["finishedAt"]?.asLong ?: 0L,
                isRunning = o["isRunning"]?.asBoolean ?: false,
            ).let { line -> if (line.entryId.isBlank()) line.copy(entryId = java.util.UUID.randomUUID().toString()) else line }
        }
    }.getOrDefault(emptyList())

    val attachments = runCatching {
        val arr = JsonParser.parseString(attachmentsJson).asJsonArray
        arr.mapNotNull { el ->
            val o = el.asJsonObject
            when (o["type"]?.asString) {
                "image" -> SkillAttachment.ImageData(
                    base64 = o["base64"]?.asString ?: "",
                    prompt = o["prompt"]?.asString?.ifBlank { null },
                    localPath = o["localPath"]?.asString ?: "",
                    instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                )
                "file" -> SkillAttachment.FileData(
                    path = o["path"]?.asString ?: "",
                    name = o["name"]?.asString ?: "",
                    mimeType = o["mimeType"]?.asString ?: "",
                    sizeBytes = o["sizeBytes"]?.asString?.toLongOrNull() ?: 0L,
                    instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                )
                "html" -> SkillAttachment.HtmlData(
                    path = o["path"]?.asString ?: "",
                    title = o["title"]?.asString ?: "",
                    htmlContent = o["htmlContent"]?.asString ?: "",
                    instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                )
                "webpage" -> SkillAttachment.WebPage(
                    url = o["url"]?.asString ?: "",
                    title = o["title"]?.asString ?: "",
                    excerpt = o["excerpt"]?.asString ?: "",
                    instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                )
                "search_results" -> {
                    val pages = runCatching {
                        o["pages"]?.asJsonArray?.mapNotNull { pe ->
                            val p = pe.asJsonObject
                            SkillAttachment.WebPage(
                                url = p["url"]?.asString ?: return@mapNotNull null,
                                title = p["title"]?.asString ?: "",
                                excerpt = p["excerpt"]?.asString ?: "",
                            )
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                    SkillAttachment.SearchResults(
                        query = o["query"]?.asString ?: "",
                        engine = o["engine"]?.asString ?: "",
                        pages = pages,
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                }
                "file_list" -> {
                    val files = runCatching {
                        o["files"]?.asJsonArray?.mapNotNull { fe ->
                            val f = fe.asJsonObject
                            SkillAttachment.FileList.FileEntry(
                                path = f["path"]?.asString ?: return@mapNotNull null,
                                name = f["name"]?.asString ?: "",
                                mimeType = f["mimeType"]?.asString ?: "text/plain",
                                sizeBytes = f["sizeBytes"]?.asString?.toLongOrNull() ?: 0L,
                            )
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                    SkillAttachment.FileList(
                        files,
                        o["directory"]?.asString ?: "",
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                }
                "action_card" -> {
                    val actions = runCatching {
                        o["actions"]?.asJsonArray?.mapNotNull { ae ->
                            val a = ae.asJsonObject
                            SkillAttachment.ActionCard.Action(
                                label = a["label"]?.asString ?: return@mapNotNull null,
                                message = a["message"]?.asString ?: return@mapNotNull null,
                                style = a["style"]?.asString ?: "secondary",
                            )
                        }.orEmpty()
                    }.getOrDefault(emptyList())
                    ConfirmationActionProtocol.migrateLegacyCard(
                        title = o["title"]?.asString ?: "",
                        body = o["body"]?.asString ?: "",
                        actions = actions,
                        tone = o["tone"]?.asString ?: "default",
                        instanceId = o["instanceId"]?.asString?.ifBlank { null } ?: java.util.UUID.randomUUID().toString(),
                    )
                }
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    return ChatMessage(
        role = if (role == "user") MessageRole.USER else MessageRole.AGENT,
        text = text,
        logLines = logLines,
        imageBase64 = imageBase64,
        attachments = attachments,
        imageLocalPath = attachments.firstOrNull { it is SkillAttachment.ImageData && it.localPath.isNotBlank() }
            ?.let { (it as SkillAttachment.ImageData).localPath }
            ?: "",
        senderRoleId = senderRoleId,
        senderRoleName = senderRoleName,
        senderRoleAvatar = senderRoleAvatar,
    )
}

private fun AgentEvent.toLogLine(): LogLine? = when (this) {
    is AgentEvent.Started      -> LogLine(type = LogType.INFO, text = "▶ $goal")
    is AgentEvent.Thinking     -> null
    is AgentEvent.ThinkingToken -> null
    is AgentEvent.SkillCalling -> LogLine(type = LogType.ACTION, text = friendlySkillDescription(skillId, params), skillId = skillId)
    is AgentEvent.Observation  -> LogLine(type = LogType.OBSERVATION, text = text.take(400), imageBase64 = imageBase64)
    is AgentEvent.Completed    -> LogLine(type = LogType.SUCCESS, text = summary)
    // Warnings remain informational so runtime guards appear as guidance rather than failures.
    is AgentEvent.Warning      -> LogLine(type = LogType.INFO, text = friendlyRuntimeNotice(message))
    is AgentEvent.Error        -> LogLine(type = LogType.ERROR, text = friendlyRuntimeNotice(message))
    is AgentEvent.Token        -> null
    is AgentEvent.ThinkingComplete -> null
    is AgentEvent.PlanCreated -> LogLine(type = LogType.THINKING, text = plan.toPrompt())
}

private fun progressDetail(key: ProgressDetailKey, value: String): String =
    ProgressDetailProtocol.encode(key, value)

private fun buildRoleUiInstruction(plan: RoleChatControlPlan): String =
    if (plan.responsePolicy.allowUiBlocks) {
        "This role may output UI blocks when the user explicitly needs interaction; normal chat remains plain text."
    } else {
        "This role does not output UI blocks by default; use plain text unless the user explicitly asks for an interactive interface."
    }

private fun roleControlUserSummary(
    role: Role,
    plan: RoleChatControlPlan,
    mode: ChatExecutionMode,
): String {
    val files = plan.contextPolicy.readRoleFiles.joinToString(", ").ifBlank { "none" }
    val tools = plan.toolPolicy.preferredToolIds.joinToString(", ").ifBlank { "AI selects by task" }
    return "This run uses ${role.name}'s protocol, mode $mode, reads $files, tools: $tools."
}

private fun roleControlNextStep(mode: ChatExecutionMode): String = when (mode) {
    ChatExecutionMode.DIRECT_CHAT,
    ChatExecutionMode.INFO -> "Compose the answer directly using the role response policy."
    ChatExecutionMode.AGENT,
    ChatExecutionMode.CODEX_DESKTOP -> "Enter execution, select tools by role policy, and keep useful trace."
}

private fun roleExecutionModeText(mode: ChatExecutionMode, hint: ChatExecutionMode?): String =
    if (hint != null) {
        "final $mode, role hint $hint"
    } else {
        "final $mode, no forced role mode"
    }

private fun roleIntentPolicyText(plan: RoleChatControlPlan): String =
    "short=${plan.intentPolicy.shortFollowUpMode}; latest=${plan.intentPolicy.currentMessagePriority}; artifact=${plan.intentPolicy.artifactReferenceMode}"

private fun roleResponsePolicyText(plan: RoleChatControlPlan): String =
    "style=${plan.responsePolicy.style}; summary=${plan.responsePolicy.completionSummaryMode}; avoid capabilities=${plan.responsePolicy.avoidCapabilityListing}; UI=${plan.responsePolicy.allowUiBlocks}"

private fun roleContextPolicyText(plan: RoleChatControlPlan): String {
    val files = plan.contextPolicy.readRoleFiles.joinToString(", ").ifBlank { "none" }
    return "reads $files; user memory ${if (plan.contextPolicy.includeUserMemory) "on" else "off"}; recent messages ${if (plan.contextPolicy.includeRecentMessages) "on" else "on demand"}"
}

private fun roleToolPolicyText(plan: RoleChatControlPlan): String {
    val preferred = plan.toolPolicy.preferredToolIds.joinToString(", ").ifBlank { "no fixed preference" }
    val blocked = plan.toolPolicy.blockedToolIds.joinToString(", ").ifBlank { "none" }
    return "preferred: $preferred; blocked: $blocked; MCP ${if (plan.toolPolicy.allowMcp) "allowed" else "disabled"}"
}

private fun rolePersistencePolicyText(plan: RoleChatControlPlan): String =
    "role memory ${if (plan.persistencePolicy.allowRoleMemoryWrite) "allowed" else "passive"}; user memory ${if (plan.persistencePolicy.allowUserMemoryWrite) "allowed" else "disabled"}; threshold ${plan.persistencePolicy.memoryImportanceThreshold}"

private fun LogLine.withLifecycle(
    running: Boolean,
    now: Long = System.currentTimeMillis(),
): LogLine = copy(
    startedAt = when {
        startedAt > 0L -> startedAt
        else -> now
    },
    finishedAt = when {
        running -> 0L
        finishedAt > 0L -> finishedAt
        else -> now
    },
    isRunning = running,
)

private fun List<LogLine>.finishLatestRunningLine(now: Long = System.currentTimeMillis()): List<LogLine> {
    val index = indexOfLast { it.isRunning }
    if (index < 0) return this
    return toMutableList().also { lines ->
        lines[index] = lines[index].copy(
            isRunning = false,
            startedAt = if (lines[index].startedAt > 0L) lines[index].startedAt else now,
            finishedAt = now,
        )
    }
}

// Present internal runtime messages as user-facing progress rather than console output.
private fun friendlyRuntimeNotice(message: String): String {
    val normalized = message.trim()
    return when {
        normalized.startsWith("LLM error:") ->
            friendlyLlmFailureMessage(normalized.removePrefix("LLM error:").trim())
        normalized.contains("skill '", ignoreCase = true) && normalized.contains("not found", ignoreCase = true) ->
            "This tool call did not succeed, so switching to another approach"
        normalized.startsWith("Error executing ") ->
            "This step hit an execution issue; continuing to fix it from the result"
        else -> normalized
    }
}

private fun isNonRetryableLlmFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("authentication error") ||
        normalized.contains("api error 401") ||
        normalized.contains("not connected to the query engine") ||
        normalized.contains("must call connect() before attempting to query data")
}

private fun friendlyLlmFailureMessage(raw: String): String {
    val normalized = raw.trim()
    val lowered = normalized.lowercase()
    return when {
        lowered.contains("not connected to the query engine") ||
            lowered.contains("must call connect() before attempting to query data") ->
            "The current chat gateway is not connected to a query engine, so it cannot summarize the reply."
        lowered.contains("authentication error") || lowered.contains("api error 401") ->
            "The current chat gateway failed authentication, or the endpoint is not a usable chat endpoint."
        else -> "The model response failed, so this request was not completed."
    }
}

// Reduce long technical output to one useful progress statement when appropriate.
private fun summarizeTechnicalResultForUser(skillId: String?, rawText: String): String? {
    val normalized = rawText
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !line.startsWith("{") &&
                !line.startsWith("[") &&
                !line.startsWith("data:") &&
                !line.startsWith("http://") &&
                !line.startsWith("https://")
        }
        .orEmpty()
    if (normalized.isBlank()) return null
    return when (skillId) {
        "web_search", "fetch_url", "web_browse", "web_content", "web_js" ->
            "Candidate content is available; next, irrelevant information will be filtered out."
        "see_screen", "screenshot", "read_screen", "bg_screenshot", "bg_read_screen" ->
            "The current screen is visible; next, the agent will decide where to act."
        "tap", "long_click", "scroll", "input_text", "navigate" ->
            "Screen feedback is available; next, the agent will confirm whether the goal was reached."
        "app_manager", "ui_builder" -> null
        else -> normalized.take(120)
    }
}

private const val PROFILE_MEMORY_PAGE_SIZE = 40

private fun String.cleanLocalTurnTokens(): String = cleanLocalGeneratedText()

private fun String.cleanLocalStreamingText(): String {
    if (isEmpty()) return ""
    var text = decodeLocalTokenizerSpacing().replace("\r\n", "\n").replace('\r', '\n')
    listOf(
        Regex("""(?i)<\|?/?(?:start_of_turn|end_of_turn|turn|im_start|im_end|eot_id|eos|bos|endoftext|begin_of_text|start_header_id|end_header_id)\|?>"""),
        Regex("""(?i)<\|?/?(?:eot|eom|eod|end)\|?>"""),
    ).forEach { regex -> text = regex.replace(text, "") }
    return text.replace(Regex("""(?i)^\s*(?:assistant|model|ai|bot)\s*[:：]\s*"""), "")
}

private fun String.cleanLocalStreamDelta(): String {
    if (isEmpty()) return ""
    var text = decodeLocalTokenizerSpacing().replace("\r\n", "\n").replace('\r', '\n')
    listOf(
        Regex("""(?i)<\|?/?(?:start_of_turn|end_of_turn|turn|im_start|im_end|eot_id|eos|bos|endoftext|begin_of_text|start_header_id|end_header_id)\|?>"""),
        Regex("""(?i)<\|?/?(?:eot|eom|eod|end)\|?>"""),
    ).forEach { regex -> text = regex.replace(text, "") }
    text = text.replace(Regex("""(?i)^\s*(?:assistant|model|ai|bot)\s*[:：]\s*"""), "")
    return text
}

private fun String.cleanCodexDesktopOutput(prompt: String): String {
    val promptLines = prompt
        .trim()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    val envKeyPattern = Regex(
        """^(workdir|model|provider|approval|sandbox|reasoning effort|reasoning summaries|session id):\s""",
        RegexOption.IGNORE_CASE,
    )
    val timestampWarnPattern = Regex("""^\d{4}-\d{2}-\d{2}T.*\bWARN\b""")
    val cleaned = mutableListOf<String>()
    var skippingHeader = false
    var sawRoleMarker = false
    replace("\r\n", "\n").lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            if (cleaned.isNotEmpty()) cleaned += raw
            return@forEach
        }
        when {
            timestampWarnPattern.containsMatchIn(trimmed) -> return@forEach
            trimmed.startsWith("OpenAI Codex", ignoreCase = true) -> {
                skippingHeader = true
                return@forEach
            }
            skippingHeader && (trimmed == "--------" || envKeyPattern.containsMatchIn(trimmed)) -> return@forEach
            skippingHeader -> skippingHeader = false
        }
        when {
            trimmed == "user" -> {
                sawRoleMarker = true
                return@forEach
            }
            trimmed == "assistant" -> {
                sawRoleMarker = true
                cleaned.clear()
                return@forEach
            }
            sawRoleMarker && trimmed in promptLines -> return@forEach
            trimmed.startsWith("deprecated:", ignoreCase = true) -> return@forEach
            envKeyPattern.containsMatchIn(trimmed) -> return@forEach
            trimmed == "--------" -> return@forEach
            else -> cleaned += raw
        }
    }
    return cleaned.joinToString("\n").trim()
}

private fun String.isSupportedImageModel(): Boolean {
    val value = trim()
    return value == "pollinations" ||
        value == "pollinations-flux" ||
        value == "hf-flux-schnell" ||
        value.startsWith("huggingface:") ||
        value.startsWith("gpt-image-") ||
        value.startsWith("dall-e-") ||
        value.startsWith("agnes-image-") ||
        value.startsWith("wanx") ||
        value == "flux-dev" ||
        value.startsWith("flux-") ||
        value.startsWith("black-forest-labs/FLUX.1")
}

private fun String.isConfiguredImageModel(): Boolean {
    val value = trim()
    return value.isNotBlank() && value.isSupportedImageModel() && value != "pollinations" && value != "pollinations-flux"
}

private fun roleWorkspaceFileOrder(name: String): Int = when (name) {
    "core.md" -> 0
    "chat_protocol.md" -> 1
    "memory.md" -> 2
    "skills.md" -> 3
    "model.md" -> 4
    "journal.md" -> 5
    "skill_index.md" -> 6
    "model_config.json" -> 7
    else -> 20
}

private fun JsonObject.intOrNull(name: String): Int? =
    runCatching {
        get(name)?.takeIf { !it.isJsonNull }?.asInt
    }.getOrNull()
