package com.mobileclaw.agent

/**
 * A Role bundles a persona (system prompt addendum), forced skill injection list,
 * and optional model override. The agent operates differently depending on the active role.
 */
data class Role(
    val id: String,
    val name: String,
    val description: String,
    val avatar: String,                         // single role image URI/path/data URI or role icon key, never emoji
    val systemPromptAddendum: String = "",
    val forcedSkillIds: List<String> = emptyList(),
    val modelBinding: RoleModelBinding? = null,
    val modelOverride: String? = null,
    val preferredTaskTypes: List<TaskType> = emptyList(),
    val keywords: List<String> = emptyList(),
    val schedulerPriority: Int = 0,
    val isBuiltin: Boolean = false,
    val chatBubbleStyle: ChatBubbleStyle = ChatBubbleStyle(),
) {
    companion object {
        val DEFAULT = Role(
            id = "general",
            name = "General Assistant",
            description = "Coordinates available capabilities according to the user's task.",
            avatar = RoleAvatarDefaults.GENERAL,
            systemPromptAddendum = "Act as the user's general runtime coordinator. You may use any available MobileClaw capability when it directly serves the user's goal, while respecting actual Android/system availability.",
            preferredTaskTypes = listOf(TaskType.CHAT, TaskType.GENERAL),
            keywords = listOf("chat", "conversation", "assistant", "general", "question", "answer"),
            isBuiltin = true,
        )

        val BUILTINS: List<Role> = listOf(
            DEFAULT,
            Role(
                id = "coder",
                name = "Code Expert",
                description = "Focuses on programming, debugging, scripting, and automation.",
                avatar = RoleAvatarDefaults.CODER,
                systemPromptAddendum = "You are an expert software engineer. Use code, files, phone control, web, MCP, and other MobileClaw capabilities whenever they help solve the user's goal. Keep changes scoped, verify with builds/tests when possible, and report command results clearly.",
                preferredTaskTypes = listOf(TaskType.CODE_EXECUTION, TaskType.FILE_CREATE),
                keywords = listOf("code", "programming", "script", "debug", "compile", "bug", "shell", "python", "gradle"),
                isBuiltin = true,
            ),
            Role(
                id = "web_agent",
                name = "Web Assistant",
                description = "Focuses on web search, research, information retrieval, and browsing.",
                avatar = RoleAvatarDefaults.WEB,
                systemPromptAddendum = "You specialize in web research, but you may use any MobileClaw capability needed to complete the user's goal. Prefer source-backed answers and summarize findings concisely.",
                preferredTaskTypes = listOf(TaskType.WEB_RESEARCH),
                keywords = listOf("search", "research", "web", "website", "sources", "news", "latest", "browse"),
                isBuiltin = true,
            ),
            Role(
                id = "phone_operator",
                name = "Phone Operator",
                description = "Focuses on Android UI control, tapping, scrolling, and app operation.",
                avatar = RoleAvatarDefaults.PHONE,
                systemPromptAddendum = "You specialize in VLM phone-control tasks. Use the observe -> act -> verify loop. Start with see_screen, use screenshot only when markers are unusable, then take a concrete action before observing again. Coordinates from see_screen/screenshot are image pixels; tap/scroll/long_click map them to device pixels. Verify target app state with foreground package/activity from tool results or phone_status.",
                preferredTaskTypes = listOf(TaskType.PHONE_CONTROL),
                keywords = listOf("phone", "android", "open", "tap", "click", "scroll", "swipe", "type", "screen", "app"),
                isBuiltin = true,
            ),
            Role(
                id = "creator",
                name = "Creative Assistant",
                description = "Focuses on native pages, MiniAPPs, media generation, and content or artifact creation.",
                avatar = RoleAvatarDefaults.CREATOR,
                systemPromptAddendum = "You specialize in IMAGE_GENERATION, APP_BUILD, and FILE_CREATE tasks. Respect conversation context before creating anything: follow-up messages like continue/change/optimize usually modify the current artifact, and questions or feedback should be answered directly. For explicit page/dashboard/form/panel/screen creation or update requests, prefer ui_builder to create or update an AI Native Page. Use app_manager only for explicit app/mini-app/program/game or custom HTML/JS/Python/SQLite runtime needs. Never return raw code or HTML when a creation tool can create the artifact. For PPT/PPTX, Word/DOCX, Excel/XLSX, and PDF, always use generate_document and provide structured JSON content; never hand-write office files with create_file or Python libraries. Produce complete usable outputs instead of long raw content in chat.",
                preferredTaskTypes = listOf(TaskType.IMAGE_GENERATION, TaskType.APP_BUILD, TaskType.FILE_CREATE),
                keywords = listOf("image", "picture", "draw", "icon", "video", "page", "native page", "dashboard", "app", "miniapp", "html", "document", "file", "generate"),
                isBuiltin = true,
            ),
            Role(
                id = "skill_admin",
                name = "Skill Manager",
                description = "Focuses on inspecting, creating, installing, and organizing skills.",
                avatar = RoleAvatarDefaults.SKILL,
                systemPromptAddendum = "You specialize in skill management. Inspect the current skill inventory before changing it, and use any MobileClaw capability needed to install, test, repair, or organize skills.",
                preferredTaskTypes = listOf(TaskType.SKILL_MANAGEMENT),
                keywords = listOf("skill", "capability", "install", "create skill", "skill market"),
                isBuiltin = true,
            ),
            Role(
                id = "vpn_operator",
                name = "VPN Manager",
                description = "Focuses on VPN switching, proxy or node selection, subscriptions, and connection diagnostics.",
                avatar = RoleAvatarDefaults.VPN,
                systemPromptAddendum = "You specialize in VPN and proxy tasks. Use vpn_control, phone control, web, files, and other MobileClaw capabilities as needed for setup, diagnosis, and operation.",
                preferredTaskTypes = listOf(TaskType.VPN_CONTROL),
                keywords = listOf("vpn", "proxy", "node", "subscription", "connect", "network", "mihomo", "clash"),
                isBuiltin = true,
            ),
        )
    }
}

data class RoleModelBinding(
    val gatewayId: String = "",
    val gatewayName: String = "",
    val model: String = "",
    val localModelId: String = "",
) {
    fun normalized(): RoleModelBinding = copy(
        gatewayId = gatewayId.trim(),
        gatewayName = gatewayName.trim(),
        model = model.trim(),
        localModelId = localModelId.trim().removePrefix("local:"),
    )

    fun isEmpty(): Boolean =
        gatewayId.isBlank() && gatewayName.isBlank() && model.isBlank() && localModelId.isBlank()

    fun legacyModelOverride(): String? = when {
        localModelId.isNotBlank() -> "local:${localModelId.removePrefix("local:")}"
        model.isNotBlank() -> model
        else -> null
    }

    companion object {
        fun fromLegacy(modelOverride: String?): RoleModelBinding? {
            val model = modelOverride?.trim().orEmpty()
            if (model.isBlank()) return null
            return if (model.startsWith("local:")) {
                RoleModelBinding(localModelId = model.removePrefix("local:"))
            } else {
                RoleModelBinding(model = model)
            }
        }
    }
}

fun Role.effectiveModelBinding(): RoleModelBinding? {
    val binding = modelBinding?.normalized()?.takeUnless { it.isEmpty() }
    return binding ?: RoleModelBinding.fromLegacy(modelOverride)
}


object RoleAvatarDefaults {
    const val GENERAL = "role:general"
    const val CODER = "role:coder"
    const val WEB = "role:web"
    const val PHONE = "role:phone"
    const val CREATOR = "role:creator"
    const val SKILL = "role:skill"
    const val VPN = "role:vpn"
    const val CUSTOM = "role:custom"

    fun forRoleId(roleId: String): String = when (roleId) {
        "general" -> GENERAL
        "coder" -> CODER
        "web_agent" -> WEB
        "phone_operator" -> PHONE
        "creator" -> CREATOR
        "skill_admin" -> SKILL
        "vpn_operator" -> VPN
        else -> CUSTOM
    }
}

fun normalizeRoleAvatar(roleId: String, avatar: String?): String {
    val value = avatar.orEmpty().trim()
    if (value.isBlank()) return RoleAvatarDefaults.forRoleId(roleId)
    if (isRoleImageAvatar(value)) return value
    if (value.startsWith("role:")) return value
    if (value.equals("file", ignoreCase = true)) return RoleAvatarDefaults.forRoleId(roleId)
    if (looksLikeEmojiAvatar(value)) return RoleAvatarDefaults.forRoleId(roleId)
    return value.takeIf { it.length <= 40 } ?: RoleAvatarDefaults.forRoleId(roleId)
}

fun isRoleImageAvatar(value: String): Boolean =
    value.startsWith("content://") ||
        value.startsWith("file://") ||
        value.startsWith("data:") ||
        value.startsWith("/")

private fun looksLikeEmojiAvatar(value: String): Boolean =
    value.any { Character.getType(it) == Character.SURROGATE.toInt() || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }

data class ChatBubbleStyle(
    val preset: String = "minimal",
    val renderer: String = "native",
    val htmlTemplate: String = "",
    val htmlHeightDp: Int = 160,
    val htmlAllowJs: Boolean = false,
    val htmlAllowNetwork: Boolean = true,
    val htmlTransparent: Boolean = true,
    val backgroundColor: String = "",
    val backgroundImage: String = "",
    val textColor: String = "",
    val borderColor: String = "",
    val accentColor: String = "",
    val radiusDp: Int = 18,
    val radiusTopStartDp: Int = -1,
    val radiusTopEndDp: Int = -1,
    val radiusBottomEndDp: Int = -1,
    val radiusBottomStartDp: Int = -1,
    val tail: String = "soft",
    val pattern: String = "none",
    val decoration: String = "none",
    val decorationText: String = "",
    val decorationPosition: String = "top_end",
    val decorationAnimation: String = "none",
    val decorationSizeDp: Int = 14,
    val decorations: List<ChatBubbleDecoration> = emptyList(),
    val gradient: List<String> = emptyList(),
    val animation: String = "none",
    val emotion: String = "neutral",
    val fontFamily: String = "system",
    val fontWeight: String = "regular",
    val textAnimation: String = "none",
    val fontSizeSp: Int = 14,
    val lineHeightSp: Int = 20,
    val paddingHorizontalDp: Int = 12,
    val paddingVerticalDp: Int = 8,
    val shadow: String = "none",
    val shadowColor: String = "",
    val shadowAlpha: Float = -1f,
    val shadowElevationDp: Int = -1,
    val shadowOffsetXDp: Int = 0,
    val shadowOffsetYDp: Int = 0,
    val imageMode: String = "cover",
    val schemaVersion: Int = 2,
)

data class ChatBubbleDecoration(
    val type: String = "none",
    val text: String = "",
    val position: String = "top_end",
    val x: Float = -1f,
    val y: Float = -1f,
    val animation: String = "none",
    val sizeDp: Int = 14,
    val color: String = "",
    val alpha: Float = -1f,
)
