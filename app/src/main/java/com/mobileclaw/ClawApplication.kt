package com.mobileclaw

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.mobileclaw.agent.RoleManager
import com.mobileclaw.agent.AgentTaskController
import com.mobileclaw.agent.AgentTaskSubmissionService
import com.mobileclaw.agent.AgentRuntime
import com.mobileclaw.agent.TaskType
import com.mobileclaw.agent.VoiceAgentCoordinator
import com.mobileclaw.auth.chatgpt.ChatGptAuthManager
import com.mobileclaw.agent.RoleWorkspaceStore
import com.mobileclaw.agent.TaskRecipeStore
import com.mobileclaw.agent.TaskReplayStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.mobileclaw.app.MiniAppStore
import com.mobileclaw.config.AgentConfig
import com.mobileclaw.config.SkillLevelStore
import com.mobileclaw.config.SkillNotesStore
import com.mobileclaw.config.UserConfig
import com.mobileclaw.config.supportsCapabilityMultimodal
import com.mobileclaw.llm.HybridLlmGateway
import com.mobileclaw.llm.LocalGemmaGateway
import com.mobileclaw.llm.OpenAiGateway
import com.mobileclaw.llm.ChatGptOAuthGateway
import com.mobileclaw.llm.ChatGptModelService
import com.mobileclaw.llm.CloudLlmRouter
import com.mobileclaw.llm.CloudProviderResolver
import com.mobileclaw.llm.EffectiveCloudProvider
import com.mobileclaw.llm.LocalModelManager
import com.mobileclaw.memory.ConversationMemory
import com.mobileclaw.memory.SemanticMemory
import com.mobileclaw.memory.UserProfileExtractor
import com.mobileclaw.memory.db.ClawDatabase
import com.mobileclaw.perception.VirtualDisplayManager
import com.mobileclaw.perception.AndroidLaunchableAppProvider
import com.mobileclaw.perception.InstalledAppCatalog
import com.mobileclaw.perception.InstalledAppResolver
import com.mobileclaw.permission.PermissionManager
import com.mobileclaw.permission.AndroidDeviceReadinessSignalsProvider
import com.mobileclaw.permission.DeviceReadinessEngine
import com.mobileclaw.permission.detectRom
import com.mobileclaw.realtime.AndroidWebRtcVoiceTransport
import com.mobileclaw.realtime.ChatGptRealtimeCallClient
import com.mobileclaw.realtime.ChatGptRealtimeSessionController
import com.mobileclaw.realtime.ChatGptRealtimeSidebandClient
import com.mobileclaw.realtime.VoiceSessionForegroundService
import com.mobileclaw.memory.MemoryContextBuilder
import com.mobileclaw.runtime.PageRuntimeCapabilities
import com.mobileclaw.server.ConsoleServer
import com.mobileclaw.server.LocalApiServer
import com.mobileclaw.skill.SkillLoader
import com.mobileclaw.skill.SkillRegistry
import com.mobileclaw.town.AgentTownStore
import com.mobileclaw.ui.AgentOverlayManager
import com.mobileclaw.ui.AuroraOverlayManager
import com.mobileclaw.ui.InAppWebViewManager
import com.mobileclaw.ui.MiniAppValidationOverlayManager
import com.mobileclaw.ui.aipage.AiPageStore
import com.mobileclaw.workspace.WorkspaceStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

class ClawApplication : Application() {

    val agentExecutionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var agentTaskController: AgentTaskController
        private set

    lateinit var chatGptAuthManager: ChatGptAuthManager
        private set

    lateinit var chatGptModelService: ChatGptModelService
        private set

    lateinit var realtimeVoiceController: ChatGptRealtimeSessionController
        private set

    lateinit var agentTaskSubmissionService: AgentTaskSubmissionService
        private set

    lateinit var voiceAgentCoordinator: VoiceAgentCoordinator
        private set

    lateinit var database: ClawDatabase
        private set

    lateinit var skillRegistry: SkillRegistry
        private set

    lateinit var agentConfig: AgentConfig
        private set

    lateinit var overlayManager: AgentOverlayManager
        private set

    lateinit var auroraOverlayManager: AuroraOverlayManager
        private set

    lateinit var miniAppValidationOverlayManager: MiniAppValidationOverlayManager
        private set

    lateinit var permissionManager: PermissionManager
        private set

    lateinit var deviceReadinessEngine: DeviceReadinessEngine
        private set

    lateinit var semanticMemory: SemanticMemory
        private set

    lateinit var conversationMemory: ConversationMemory
        private set

    lateinit var userProfileExtractor: UserProfileExtractor
        private set

    lateinit var webViewManager: InAppWebViewManager
        private set

    lateinit var virtualDisplayManager: VirtualDisplayManager
        private set

    lateinit var installedAppCatalog: InstalledAppCatalog
        private set

    lateinit var installedAppResolver: InstalledAppResolver
        private set

    lateinit var userConfig: UserConfig
        private set

    lateinit var roleManager: RoleManager
        private set

    lateinit var roleWorkspaceStore: RoleWorkspaceStore
        private set

    lateinit var localApiServer: LocalApiServer
        private set

    lateinit var miniAppStore: MiniAppStore
        private set

    lateinit var skillNotesStore: SkillNotesStore
        private set

    lateinit var skillLevelStore: SkillLevelStore
        private set

    lateinit var userStorageManager: com.mobileclaw.config.UserStorageManager
        private set

    lateinit var consoleServer: ConsoleServer
        private set

    lateinit var aiPageStore: AiPageStore
        private set

    lateinit var agentTownStore: AgentTownStore
        private set

    lateinit var localModelManager: LocalModelManager
        private set

    lateinit var taskReplayStore: TaskReplayStore
        private set

    lateinit var taskRecipeStore: TaskRecipeStore
        private set

    lateinit var workspaceStore: WorkspaceStore
        private set

    private var startedActivityCount = 0
    private val _appForeground = MutableStateFlow(false)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    fun isAppForeground(): Boolean = _appForeground.value

    override fun onCreate() {
        super.onCreate()
        instance = this
        agentTaskController = AgentTaskController()
        chatGptAuthManager = ChatGptAuthManager(this)
        chatGptModelService = ChatGptModelService(chatGptAuthManager)
        registerForegroundCallbacks()
        database = ClawDatabase.getInstance(this)
        agentConfig = AgentConfig(this)
        localModelManager = LocalModelManager(this)
        skillRegistry = SkillRegistry()
        overlayManager = AgentOverlayManager(this)
        auroraOverlayManager = AuroraOverlayManager(this)
        miniAppValidationOverlayManager = MiniAppValidationOverlayManager(this)
        val deviceRomType = detectRom()
        deviceReadinessEngine = DeviceReadinessEngine(
            AndroidDeviceReadinessSignalsProvider(this) { deviceRomType },
        )
        permissionManager = PermissionManager(this, deviceReadinessEngine)
        semanticMemory = SemanticMemory(database.semanticDao())
        conversationMemory = ConversationMemory(database.conversationDao())
        userProfileExtractor = UserProfileExtractor(createLlmGateway(), semanticMemory, conversationMemory)
        webViewManager = InAppWebViewManager(this)
        virtualDisplayManager = VirtualDisplayManager(this)
        installedAppCatalog = InstalledAppCatalog(AndroidLaunchableAppProvider(this))
        installedAppResolver = InstalledAppResolver(installedAppCatalog)
        userConfig = UserConfig(this)
        roleManager = RoleManager(this)
        roleWorkspaceStore = RoleWorkspaceStore(this)
        val consoleServerEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 || runBlocking {
            userConfig.get("console_server_enabled") == "true"
        }
        val consoleServerLanEnabled = runBlocking {
            userConfig.get("console_server_lan_enabled") == "true"
        }
        val consoleServerToken = if (consoleServerEnabled) {
            runBlocking {
                userConfig.get("console_server_token")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
                    userConfig.set("console_server_token", it, "Auth token for ConsoleServer LAN access")
                }
            }
        } else {
            ""
        }
        val sharedSkillLoader = SkillLoader(this, skillRegistry)
        localApiServer = LocalApiServer(
            skillRegistry = skillRegistry,
            skillLoader = sharedSkillLoader,
            semanticMemory = semanticMemory,
            userConfig = userConfig,
            runtime = PageRuntimeCapabilities(this),
        )
        localApiServer.start()
        miniAppStore = MiniAppStore(this)
        taskReplayStore = TaskReplayStore(filesDir)
        taskRecipeStore = TaskRecipeStore(filesDir)
        workspaceStore = WorkspaceStore(filesDir)
        skillNotesStore = SkillNotesStore(this)
        skillLevelStore = SkillLevelStore(this)
        userStorageManager = com.mobileclaw.config.UserStorageManager(this)
        consoleServer = ConsoleServer(
            filesDir = filesDir,
            database = database,
            enabled = consoleServerEnabled,
            lanEnabled = consoleServerLanEnabled,
            authToken = consoleServerToken,
            skillRegistry = skillRegistry,
            skillLoader = sharedSkillLoader,
            semanticMemory = semanticMemory,
            userConfig = userConfig,
        )
        consoleServer.start()
        aiPageStore = AiPageStore(filesDir)
        agentTownStore = AgentTownStore(this)
        agentTaskSubmissionService = AgentTaskSubmissionService(this, agentTaskController, agentExecutionScope)
        voiceAgentCoordinator = VoiceAgentCoordinator(
            agentExecutionScope,
            agentTaskController,
            agentTaskSubmissionService,
            { capability -> deviceReadinessEngine.evaluate(capability).level },
        ) { goal ->
            AgentRuntime(createLlmGateway(), skillRegistry, semanticMemory, MemoryContextBuilder(semanticMemory, userConfig))
                .run(goal = goal, taskType = TaskType.PHONE_CONTROL)
        }
        val realtimeCalls = ChatGptRealtimeCallClient(chatGptAuthManager)
        realtimeVoiceController = ChatGptRealtimeSessionController(
            agentExecutionScope,
            { AndroidWebRtcVoiceTransport(this, realtimeCalls, ChatGptRealtimeSidebandClient(agentExecutionScope, chatGptAuthManager)) },
            voiceAgentCoordinator,
        )
    }

    /** Called only from the foreground UI after RECORD_AUDIO is granted. */
    fun startLiveVoice(): Boolean {
        val started = realtimeVoiceController.start()
        if (started) VoiceSessionForegroundService.start(this)
        return started
    }

    fun endLiveVoice() {
        realtimeVoiceController.stop()
        VoiceSessionForegroundService.stop(this)
    }

    fun providerReadiness(snapshot: com.mobileclaw.config.ConfigSnapshot = agentConfig.snapshot()) = snapshot.let {
        CloudProviderResolver.readiness(
            it.cloudProviderPreference,
            CloudProviderResolver.localRuntimeReady(it.localModelEnabled, it.localNativeOnly, localModelManager.modelPath(it.localModelId) != null),
            chatGptAuthManager.hasUsableSession(),
            it.chatEndpoint.isNotBlank() && it.chatApiKey.isNotBlank(),
        )
    }

    fun effectiveModel(snapshot: com.mobileclaw.config.ConfigSnapshot = agentConfig.snapshot()): String = when {
        (snapshot.localModelEnabled || snapshot.localNativeOnly) && localModelManager.modelPath(snapshot.localModelId) != null -> "local:${snapshot.localModelId}"
        providerReadiness(snapshot).effectiveCloudProvider == EffectiveCloudProvider.CHATGPT_ACCOUNT ->
            snapshot.chatGptModel.ifBlank { chatGptModelService.pickerModels().firstOrNull()?.slug.orEmpty() }
        else -> snapshot.chatModel
    }

    fun supportsEffectiveMultimodal(snapshot: com.mobileclaw.config.ConfigSnapshot = agentConfig.snapshot()): Boolean {
        if ((snapshot.localModelEnabled || snapshot.localNativeOnly) && localModelManager.modelPath(snapshot.localModelId) != null) {
            val model = localModelManager.modelInfo(snapshot.localModelId) ?: return false
            return model.supportsVision && localModelManager.visionModelPathFor(snapshot.localModelId) != null
        }
        return CloudProviderResolver.supportsCloudMultimodal(
            providerReadiness(snapshot).effectiveCloudProvider,
            chatGptModelService.model(snapshot.chatGptModel),
            snapshot.activeGateway?.supportsCapabilityMultimodal() ?: snapshot.supportsMultimodal,
        )
    }

    fun createLlmGateway(): com.mobileclaw.llm.LlmGateway {
        val apiGateway = OpenAiGateway(agentConfig)
        val cloud = CloudLlmRouter(
            agentConfig,
            apiGateway,
            ChatGptOAuthGateway(chatGptAuthManager, agentConfig, chatGptModelService),
            chatGptAuthManager::hasUsableSession,
        )
        return HybridLlmGateway(
            local = LocalGemmaGateway(this, localModelManager) { agentConfig.snapshot().localModelId },
            cloud = cloud,
            useLocal = { agentConfig.snapshot().localModelEnabled },
            canUseCloud = { providerReadiness().effectiveCloudProvider != null },
            nativeOnly = { agentConfig.snapshot().localNativeOnly },
            localToolCallingEnabled = { agentConfig.snapshot().localToolCallingEnabled },
        )
    }

    override fun onTerminate() {
        // Best-effort cleanup for emulated processes; Android does not call this reliably in production.
        agentTaskController.activeTasks.value.forEach {
            agentTaskController.cancelTask(it.taskId, com.mobileclaw.agent.AgentCancellationReason.APP_SHUTDOWN)
        }
        realtimeVoiceController.stop()
        agentExecutionScope.cancel()
        localApiServer.stop()
        consoleServer.stop()
        super.onTerminate()
    }

    private fun registerForegroundCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) _appForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) _appForeground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Tasks submitted from MiniAppActivity to the main agent. */
    val pendingAgentTask = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** English-only application context used for centralized string access. */
    val localizedContext: android.content.Context
        get() = this

    companion object {
        lateinit var instance: ClawApplication
            private set
    }
}
