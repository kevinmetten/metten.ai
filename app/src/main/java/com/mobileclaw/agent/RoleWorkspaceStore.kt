package com.mobileclaw.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.capabilityEndpoint
import com.mobileclaw.config.capabilityModel
import com.mobileclaw.llm.RoleModelResolver
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillToolCategory
import com.mobileclaw.storage.AtomicTextFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RoleWorkspaceSnapshot(
    val roleId: String,
    val rootPath: String,
    val core: String,
    val skills: String,
    val memory: String,
    val model: String,
    val chatProtocol: String,
)

class RoleWorkspaceStore(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val ioLock = Any()
    private val rootDir: File get() = context.filesDir.resolve("role_workspaces").also { it.mkdirs() }

    fun ensure(role: Role, skills: List<SkillMeta> = emptyList()): RoleWorkspaceSnapshot = synchronized(ioLock) {
        val dir = roleDir(role.id).also { it.mkdirs() }
        val core = File(dir, CORE_MD)
        val skill = File(dir, SKILLS_MD)
        val memory = File(dir, MEMORY_MD)
        val model = File(dir, MODEL_MD)
        val chatProtocol = File(dir, CHAT_PROTOCOL_MD)
        val journal = File(dir, JOURNAL_MD)

        if (!core.exists()) AtomicTextFile.write(core, defaultCore(role))
        if (!skill.exists()) AtomicTextFile.write(skill, defaultSkills(role, skills))
        if (!memory.exists()) AtomicTextFile.write(memory, defaultMemory(role))
        if (!model.exists()) AtomicTextFile.write(model, defaultModel(role))
        if (!chatProtocol.exists()) AtomicTextFile.write(chatProtocol, defaultChatProtocol(role))
        if (!journal.exists()) AtomicTextFile.write(journal, "# ${role.name.ifBlank { role.id }} Work Log\n\n")
        migrateWorkspaceFiles(core, skill, memory, model, chatProtocol, journal)
        migrateDefaultRoleSections(role, core, skill, memory, chatProtocol)

        if (skills.isNotEmpty()) {
            refreshSkillIndex(role.id, skills)
        }
        snapshotLocked(role.id)
    }

    fun snapshot(role: Role, skills: List<SkillMeta> = emptyList()): RoleWorkspaceSnapshot =
        ensure(role, skills)

    fun promptBlock(role: Role, skills: List<SkillMeta> = emptyList()): String {
        val snap = snapshot(role, skills)
        return promptBlock(snap, skills)
    }

    fun promptBlock(
        snap: RoleWorkspaceSnapshot,
        skills: List<SkillMeta> = emptyList(),
        includeChatProtocol: Boolean = true,
    ): String {
        val allSkillsNote = if (skills.isNotEmpty()) {
            val grouped = skills
                .filterNot { it.internalTool }
                .groupBy { it.categories.firstOrNull()?.name ?: "OTHER" }
                .toSortedMap()
                .map { (category, metas) ->
                    "$category: " + metas.sortedBy { it.id }.joinToString(", ") { it.id }
                }
                .joinToString("\n")
            "\n## All Available Skills\n$grouped\n"
        } else ""
        val chatProtocolSection = if (includeChatProtocol) {
            """

### chat_protocol.md
${snap.chatProtocol.take(2200)}
""".trimEnd()
        } else ""
        return """
## Role Workspace
Role id: ${snap.roleId}
Workspace path: ${snap.rootPath}

### core.md
${snap.core.take(1800)}

### memory.md
${snap.memory.take(1800)}

### model.md
${snap.model.take(1400)}
$chatProtocolSection

### skills.md
${snap.skills.take(2200)}
$allSkillsNote
Rules:
- You are operating as this role, not merely speaking in this role's style.
- Read this role workspace before making durable decisions about the role.
- Follow `chat_protocol.md` as the role's working protocol for input understanding, context reading, memory, skills, response, and persistence.
- Use `role_workspace` to read, write, append, or refresh role files when the role learns something durable.
- All installed skills are available for discovery. Choose tools by task need, then read skill details on demand instead of guessing.
""".trimIndent()
    }

    fun read(roleId: String, fileName: String): String? = synchronized(ioLock) {
        AtomicTextFile.readOrNull(resolveFile(roleId, fileName))
    }

    fun write(roleId: String, fileName: String, content: String): String = synchronized(ioLock) {
        val file = resolveFile(roleId, fileName)
        AtomicTextFile.write(file, content)
        file.relativeTo(rootDir).path
    }

    fun append(roleId: String, fileName: String, content: String): String = synchronized(ioLock) {
        val file = resolveFile(roleId, fileName)
        val previous = AtomicTextFile.readOrNull(file).orEmpty()
        val next = buildString {
            append(previous)
            if (previous.isNotBlank() && !previous.endsWith("\n")) append('\n')
            append(content.trimEnd())
            append("\n")
        }
        AtomicTextFile.write(file, next)
        file.relativeTo(rootDir).path
    }

    fun list(roleId: String): List<String> = synchronized(ioLock) {
        roleDir(roleId).also { it.mkdirs() }
            .walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(roleDir(roleId)).path }
            .toList()
            .sorted()
    }

    fun refreshSkillIndex(roleId: String, skills: List<SkillMeta>): String = synchronized(ioLock) {
        val file = File(roleDir(roleId).also { it.mkdirs() }, SKILL_INDEX_MD)
        AtomicTextFile.write(file, renderSkillIndex(skills))
        file.relativeTo(rootDir).path
    }

    fun recordModelConfig(role: Role, snapshot: ConfigSnapshot, source: String = "runtime"): String = synchronized(ioLock) {
        roleDir(role.id).also { it.mkdirs() }
        val modelInfo = roleModelConfigMap(role, snapshot, source)
        val md = renderModelConfigMarkdown(modelInfo)
        AtomicTextFile.write(File(roleDir(role.id), MODEL_MD), md)
        AtomicTextFile.write(File(roleDir(role.id), MODEL_CONFIG_JSON), gson.toJson(modelInfo))
        File(roleDir(role.id), MODEL_MD).relativeTo(rootDir).path
    }

    private fun snapshotLocked(roleId: String): RoleWorkspaceSnapshot =
        RoleWorkspaceSnapshot(
            roleId = roleId,
            rootPath = roleDir(roleId).absolutePath,
            core = AtomicTextFile.readOrNull(File(roleDir(roleId), CORE_MD)).orEmpty(),
            skills = AtomicTextFile.readOrNull(File(roleDir(roleId), SKILLS_MD)).orEmpty(),
            memory = AtomicTextFile.readOrNull(File(roleDir(roleId), MEMORY_MD)).orEmpty(),
            model = AtomicTextFile.readOrNull(File(roleDir(roleId), MODEL_MD)).orEmpty(),
            chatProtocol = AtomicTextFile.readOrNull(File(roleDir(roleId), CHAT_PROTOCOL_MD)).orEmpty(),
        )

    private fun roleDir(roleId: String): File = File(rootDir, sanitize(roleId))

    private fun resolveFile(roleId: String, fileName: String): File {
        val safePath = fileName.trim().ifBlank { CORE_MD }
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
            .ifBlank { CORE_MD }
        return File(roleDir(roleId).also { it.mkdirs() }, safePath)
    }

    private fun defaultCore(role: Role): String = """
# ${role.name.ifBlank { role.id }}

## ${RoleWorkspaceMarkdownSchema.Core.ROLE_IDENTITY}
${role.description.ifBlank { "This role provides a stable AI working identity in MobileClaw." }}

## ${RoleWorkspaceMarkdownSchema.Core.EXECUTION_PRINCIPLES}
${role.systemPromptAddendum.ifBlank { "- Identify the task goal before selecting the smallest viable capabilities.\n- Treat the role as a working identity, not merely a conversational tone." }}

## ${RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD}
${roleCorePlaybook(role)}

## ${RoleWorkspaceMarkdownSchema.Core.WORKING_BOUNDARIES}
- Role id: ${role.id}
- Preferred task types: ${role.preferredTaskTypes.joinToString(", ").ifBlank { "GENERAL" }}
- Keywords: ${role.keywords.joinToString(", ").ifBlank { "none" }}
- Forced skills: ${role.forcedSkillIds.joinToString(", ").ifBlank { "none" }}
- Model binding: ${roleModelBindingText(role)}
""".trimIndent() + "\n"

    private fun defaultSkills(role: Role, skills: List<SkillMeta>): String = """
# Skill Usage

## ${RoleWorkspaceMarkdownSchema.Skills.DEFAULT_ROLE_SKILLS}
${role.forcedSkillIds.joinToString("\n") { "- $it" }.ifBlank { "- No skills are forced. Discover and use skills according to the task." }}

## ${RoleWorkspaceMarkdownSchema.Skills.ON_DEMAND_SKILL_POLICY}
- All installed skills can be discovered, but invoke them only when the task requires them.
- Prefer the skill closest to the task goal; availability alone is not a reason to call it.
- If a skill's purpose is unclear, inspect `skill_index.md` or use the skill or marketplace tools for details.
- The role is not limited by a skill allowlist. Combine chat, phone, file, page, web, MCP, and workspace capabilities as needed.
- Creation tasks should use durable artifact tools. For revision tasks, identify the target artifact before updating it rather than creating a replacement accidentally.

## ${RoleWorkspaceMarkdownSchema.Skills.SKILL_SELECTION_HABITS}
${roleSkillPlaybook(role)}

## ${RoleWorkspaceMarkdownSchema.Skills.CURRENT_SKILL_INDEX}
${renderCompactSkillGroups(skills)}
""".trimIndent() + "\n"

    private fun defaultMemory(role: Role): String = """
# Role Memory

## ${RoleWorkspaceMarkdownSchema.Memory.STABLE_PREFERENCES}
- Record this role's durable preferences, constraints, and recurring working methods here.

## ${RoleWorkspaceMarkdownSchema.Memory.MEMORY_TRIGGERS}
- Update this file when the user states a durable preference, personal fact, important goal, constraint, or working habit.
- Also update it when the role learns reusable execution experience, such as a file that should always be checked first or a model that is unsuitable for a task type.
- Do not put temporary state, one-off results, or short-lived emotion in long-term memory; put task history in journal.md.

## ${RoleWorkspaceMarkdownSchema.Memory.TASK_EXPERIENCE}
- After important work, append only experience that can be reused.

## ${RoleWorkspaceMarkdownSchema.Memory.USER_COLLABORATION_PREFERENCES}
- Record how the user wants this role to work, not one-off conversation details.
""".trimIndent() + "\n"

    private fun defaultModel(role: Role): String = """
# Model and Gateway Configuration

## ${RoleWorkspaceMarkdownSchema.Model.CURRENT_CONFIGURATION}
- No runtime configuration has been recorded yet.
- Role model binding: ${roleModelBindingText(role)}

## ${RoleWorkspaceMarkdownSchema.Model.NOTES}
- This file records the model, gateway, and capability-model configuration most recently used by the role.
- API keys are never stored in the role directory. Only gateway identity and masked state are recorded; execution reads credentials from secure global configuration.
- Multi-role runtimes may use this record to distinguish each role's model and invocation preferences.
""".trimIndent() + "\n"

    private fun defaultChatProtocol(role: Role): String = """
# Chat Execution Protocol

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.RUNTIME_CONTRACT}
- Role id: ${role.id}
- Protocol version: 1
- This file defines how the role drives MobileClaw Chat Runtime stages.

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.INPUT_UNDERSTANDING}
- Distinguish ordinary chat, a follow-up, revision of the active artifact, and a request to perform an action.
- Resolve short follow-ups such as "continue", "retry", "that is wrong", or "change it" from recent conversation, the active workspace, and the current role task.
- Treat the role as a working identity and execution method, not merely a speaking style.

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.CONTEXT_READING}
- Read core.md first to understand role identity and boundaries.
- Read memory.md for durable preferences, collaboration habits, and reusable experience.
- Read model.md for the role's most recent model and gateway configuration profile.
- When skills are needed, inspect skills.md and skill_index.md on demand instead of guessing capabilities.
- Use workspace and artifact context only to resolve the current task; the latest user intent remains authoritative.

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.MEMORY_POLICY}
- Persist only durable preferences, important milestones, role working habits, and reusable task experience.
- Do not write one-off chat, temporary emotion, or expired state to long-term memory.
- Append important facts about the user or role to memory.md; write one-task execution history to journal.md.

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.SKILL_POLICY}
- Discover and read any installed skill on demand, but first decide whether the task genuinely requires a tool.
- The role may work across chat, files, pages, phone control, web, MCP, and system configuration; do not restrict tools because of the role type or chat entry point.
- Answer ordinary questions directly. When the user's goal requires action, enter the tool or agent flow autonomously.
- ${role.forcedSkillIds.joinToString(", ").ifBlank { "This role has no forced skills; select them according to the task." }}
- ${roleProtocolHints(role)}

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.RESPONSE_POLICY}
- Respond to ordinary chat directly and clearly.
- For execution tasks, summarize what was done, the result, risks, and next steps.
- Persist memory or role-file changes after completing the task without turning internal file operations into a lengthy explanation.

## ${RoleWorkspaceMarkdownSchema.ChatProtocol.PERSISTENCE_POLICY}
- After important completed work, append the time, goal, result, and reusable experience to journal.md.
- Update memory.md or core.md when durable role preferences, workflows, or response habits change.
- Runtime writes model and gateway configuration to model.md and model_config.json without storing secrets here.
""".trimIndent() + "\n"

    private fun migrateWorkspaceFiles(
        core: File,
        skills: File,
        memory: File,
        model: File,
        chatProtocol: File,
        journal: File,
    ) {
        listOf(
            CORE_MD to core,
            SKILLS_MD to skills,
            MEMORY_MD to memory,
            MODEL_MD to model,
            CHAT_PROTOCOL_MD to chatProtocol,
        ).forEach { (fileName, file) ->
            val current = AtomicTextFile.readOrNull(file).orEmpty()
            val migrated = RoleWorkspaceMarkdownMigrator.migrate(fileName, current)
            if (migrated != current) AtomicTextFile.write(file, migrated)
        }
        val currentJournal = AtomicTextFile.readOrNull(journal).orEmpty()
        val migratedJournal = RoleWorkspaceMarkdownMigrator.migrateJournal(currentJournal)
        if (migratedJournal != currentJournal) AtomicTextFile.write(journal, migratedJournal)
    }

    private fun migrateDefaultRoleSections(
        role: Role,
        core: File,
        skills: File,
        memory: File,
        chatProtocol: File,
    ) {
        appendSectionIfMissing(
            core,
            RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD),
            "\n${RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD)}\n${roleCorePlaybook(role)}\n",
        )
        appendSectionIfMissing(
            skills,
            RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Skills.SKILL_SELECTION_HABITS),
            "\n${RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Skills.SKILL_SELECTION_HABITS)}\n${roleSkillPlaybook(role)}\n",
        )
        appendSectionIfMissing(
            memory,
            RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Memory.MEMORY_TRIGGERS),
            """

${RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.Memory.MEMORY_TRIGGERS)}
- Update this file for durable user preferences, personal facts, important goals, constraints, or working habits.
- Add reusable role experience, such as a file that should always be checked first or a model that is unsuitable for a task type.
- Put temporary state and one-off results in journal.md rather than long-term memory.
""".trimEnd() + "\n",
        )
        appendSectionIfMissing(
            chatProtocol,
            RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.ChatProtocol.ROLE_SPECIFIC_RUNTIME_HINT),
            "\n${RoleWorkspaceMarkdownSchema.heading(RoleWorkspaceMarkdownSchema.ChatProtocol.ROLE_SPECIFIC_RUNTIME_HINT)}\n- ${roleProtocolHints(role)}\n",
        )
    }

    private fun appendSectionIfMissing(file: File, marker: String, section: String) {
        val current = AtomicTextFile.readOrNull(file).orEmpty()
        if (current.contains(marker)) return
        AtomicTextFile.write(file, buildString {
            append(current.trimEnd())
            append("\n")
            append(section.trimEnd())
            append("\n")
        })
    }

    private fun roleCorePlaybook(role: Role): String = when (role.id) {
        "coder" -> """
- Read the existing code and error context before selecting an edit.
- For build, runtime, test, or log issues, reproduce or locate the cause before making a narrow fix.
- Run the most relevant build or test after editing and report the important output.
- Leave unrelated user changes untouched and limit work in a dirty tree to files required by the task.
""".trimIndent()
        "web_agent" -> """
- Decide whether information may be stale; verify stale or high-risk information online.
- Prefer official sources, primary material, and trustworthy reporting over a single secondary conclusion.
- Distinguish facts, inferences, and uncertainty, and retain links or citations the user can inspect.
""".trimIndent()
        "phone_operator" -> """
- Use an observe -> act -> verify loop and inspect the screen before interacting.
- Perform one clear action at a time, then verify that the interface reached the expected state.
- When permissions, dialogs, login, or network errors intervene, report the state and choose a recoverable path.
""".trimIndent()
        "creator" -> """
- Determine whether the user needs lightweight chat UI, a durable native page, MiniAPP, image, or file.
- Give every new artifact a unique identity. Before revising, identify the target artifact and the behavior that must remain.
- Treat displayed UI and artifacts as durable unless the user explicitly requests deletion or replacement.
- Open the completed artifact or identify its location, then retain only reusable design or repair experience.
""".trimIndent()
        "skill_admin" -> """
- Inspect current skills and marketplace state before installing, repairing, or reorganizing anything.
- After a skill change, verify its entry point, parameters, trigger guidance, and availability.
- For external skills and MCP, distinguish publicly usable services, token-gated services, and services unavailable on mobile.
""".trimIndent()
        "vpn_operator" -> """
- Inspect current configuration, subscriptions, nodes, and connection state before changing them.
- Preserve existing VPN configuration and avoid overwriting user subscriptions.
- Diagnose network reachability, subscription parsing, node latency, and system VPN state in that order.
""".trimIndent()
        else -> """
- Identify the user's actual goal, then choose direct chat, tool execution, workspace operations, or role memory.
- Answer simple questions directly; use execution flow for actions involving files, pages, phone control, web, or MCP.
- Retain only genuinely durable information after completion.
""".trimIndent()
    }

    private fun roleSkillPlaybook(role: Role): String = when (role.id) {
        "coder" -> """
- Files and code: read, search, edit, then run the relevant build or tests.
- Web: consult official documentation, dependency releases, and authoritative error references.
- Workspace: retain important implementation plans, architectural decisions, and retrospectives.
""".trimIndent()
        "web_agent" -> """
- Search and browse: search dynamic information first, then inspect the relevant pages.
- Files: produce summaries, reports, tables, or source lists when useful.
- MCP: use public MCP services for structured research, but do not assume registration-dependent services run on mobile.
""".trimIndent()
        "phone_operator" -> """
- Vision: prefer see_screen and use screenshots as supporting evidence.
- Actions: verify every tap, scroll, input, back, or launch operation.
- Memory: retain useful app package names and user-preferred navigation paths.
""".trimIndent()
        "creator" -> """
- Native pages: use ui_builder for settings, forms, dashboards, and data pages.
- MiniAPP: use app_manager for HTML/JS, games, Canvas, Python/SQLite, or WebView runtimes.
- Files and documents: use the corresponding document, spreadsheet, PDF, or image skill instead of substituting raw code in chat.
""".trimIndent()
        "skill_admin" -> """
- Skill and marketplace management: list or browse before install, update, or test.
- role_workspace: write reusable role skill habits to skills.md or memory.md.
- MCP: record service capabilities, authentication needs, mobile availability, and failure causes.
""".trimIndent()
        "vpn_operator" -> """
- vpn_control: manage connection state, subscriptions, node selection, and latency tests.
- phone, web, and files: combine them when needed to inspect system state, import subscriptions, or read configuration.
- State the diagnostic layer clearly: configuration, node, system permission, or network exit.
""".trimIndent()
        else -> """
- Prefer the smallest skill combination that directly completes the goal.
- Plan multi-tool tasks, then execute and verify them step by step.
- Put durable information in the workspace rather than leaving it only in chat.
""".trimIndent()
    }

    private fun roleProtocolHints(role: Role): String = when (role.id) {
        "creator" -> "For durable pages or apps, do not use an embedded UI block as the final result. Use ui_builder or app_manager and preserve a unique instance for each displayed artifact."
        "coder" -> "Structure code work as understand -> locate -> edit -> verify -> report; do not edit before locating the cause."
        "phone_operator" -> "Feed each screen observation and action result into the next decision instead of performing blind action sequences."
        "web_agent" -> "Account for source reliability and state any unresolved evidence gaps."
        "skill_admin" -> "For skill or MCP work, state mobile availability, authentication requirements, and recovery options."
        "vpn_operator" -> "Never overwrite VPN configuration implicitly; preserve rollback information for subscription and node changes."
        else -> "Adapt execution to task complexity: answer simple requests directly, break complex tasks into steps, and persist durable results at important checkpoints."
    }

    private fun roleModelConfigMap(role: Role, snapshot: ConfigSnapshot, source: String): Map<String, Any?> {
        val binding = role.effectiveModelBinding()
        val resolved = RoleModelResolver.resolve(role, snapshot)
        val gateway = if (resolved.localModelId.isNotBlank()) {
            null
        } else {
            snapshot.gateways.firstOrNull { resolved.gatewayId.isNotBlank() && it.id == resolved.gatewayId }
                ?: snapshot.gateways.firstOrNull { resolved.gatewayName.isNotBlank() && it.name.equals(resolved.gatewayName, ignoreCase = true) }
                ?: snapshot.activeGateway
        }
        val effectiveRoleModel = when {
            resolved.localModelId.isNotBlank() -> "local:${resolved.localModelId.removePrefix("local:")}"
            resolved.model.isNotBlank() -> resolved.model
            else -> snapshot.model
        }
        val capabilities = listOf("chat", "image", "video", "embedding").associateWith { type ->
            mapOf(
                "model" to (gateway?.capabilityModel(type) ?: ""),
                "endpoint" to maskEndpoint(gateway?.capabilityEndpoint(type).orEmpty()),
                "enabled" to (gateway?.capabilities.orEmpty().firstOrNull { it.type.equals(type, ignoreCase = true) }?.enabled ?: false),
            )
        }
        return mapOf(
            "updatedAt" to nowText(),
            "source" to source,
            "role" to mapOf(
                "id" to role.id,
                "name" to role.name,
                "modelOverride" to role.modelOverride,
                "modelBinding" to binding?.let {
                    mapOf(
                        "gatewayId" to it.gatewayId,
                        "gatewayName" to it.gatewayName,
                        "model" to it.model,
                        "localModelId" to it.localModelId,
                    )
                },
                "modelBindingSummary" to roleModelBindingText(role),
            ),
            "runtime" to mapOf(
                "effectiveModel" to effectiveRoleModel,
                "chatModel" to snapshot.chatModel,
                "embeddingModel" to snapshot.embeddingModel,
                "localModelEnabled" to snapshot.localModelEnabled,
                "localNativeOnly" to snapshot.localNativeOnly,
                "localToolCallingEnabled" to snapshot.localToolCallingEnabled,
                "localModelId" to snapshot.localModelId,
                "roleInheritsDefault" to resolved.inheritedDefault,
                "roleGatewayId" to resolved.gatewayId,
                "roleGatewayName" to resolved.gatewayName,
                "roleModel" to resolved.model,
                "roleLocalModelId" to resolved.localModelId,
            ),
            "gateway" to mapOf(
                "id" to gateway?.id,
                "name" to gateway?.name,
                "endpoint" to maskEndpoint(gateway?.endpoint.orEmpty()),
                "apiKey" to maskSecret(gateway?.apiKey.orEmpty()),
                "model" to gateway?.model,
                "embeddingModel" to gateway?.embeddingModel,
                "supportsMultimodal" to gateway?.supportsMultimodal,
            ),
            "capabilities" to capabilities,
        )
    }

    private fun renderModelConfigMarkdown(info: Map<String, Any?>): String {
        val runtime = info["runtime"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val gateway = info["gateway"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val role = info["role"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val capabilities = info["capabilities"] as? Map<*, *> ?: emptyMap<String, Any?>()
        fun textOrNone(value: Any?): String =
            value?.toString()?.takeIf { it.isNotBlank() } ?: "none"
        return buildString {
            appendLine("# Model and Gateway Configuration")
            appendLine()
            appendLine("## ${RoleWorkspaceMarkdownSchema.Model.CURRENT_CONFIGURATION}")
            appendLine("- Updated at: ${info["updatedAt"]}")
            appendLine("- Source: ${info["source"]}")
            appendLine("- Role: ${role["name"]} (${role["id"]})")
            appendLine("- Role model binding: ${role["modelBindingSummary"] ?: role["modelOverride"] ?: "none"}")
            appendLine()
            appendLine("## Effective Model")
            appendLine("- Effective model: ${runtime["effectiveModel"]}")
            appendLine("- Inherits global default: ${runtime["roleInheritsDefault"]}")
            appendLine("- Role gateway id: ${textOrNone(runtime["roleGatewayId"])}")
            appendLine("- Role gateway name: ${textOrNone(runtime["roleGatewayName"])}")
            appendLine("- Role model: ${textOrNone(runtime["roleModel"])}")
            appendLine("- Role local model id: ${textOrNone(runtime["roleLocalModelId"])}")
            appendLine("- Chat model: ${runtime["chatModel"]}")
            appendLine("- Embedding model: ${runtime["embeddingModel"]}")
            appendLine("- Local enabled: ${runtime["localModelEnabled"]}")
            appendLine("- Local native only: ${runtime["localNativeOnly"]}")
            appendLine("- Local tool calling: ${runtime["localToolCallingEnabled"]}")
            appendLine("- Local model id: ${runtime["localModelId"]}")
            appendLine()
            appendLine("## Gateway")
            appendLine("- Gateway id: ${gateway["id"] ?: "none"}")
            appendLine("- Gateway name: ${gateway["name"] ?: "none"}")
            appendLine("- Endpoint: ${gateway["endpoint"] ?: ""}")
            appendLine("- API key: ${gateway["apiKey"] ?: ""}")
            appendLine("- Base model: ${gateway["model"] ?: ""}")
            appendLine("- Base embedding model: ${gateway["embeddingModel"] ?: ""}")
            appendLine("- Supports multimodal: ${gateway["supportsMultimodal"] ?: false}")
            appendLine()
            appendLine("## Capability Models")
            capabilities.forEach { (type, value) ->
                val item = value as? Map<*, *> ?: return@forEach
                appendLine("- $type: model=${item["model"]}, endpoint=${item["endpoint"]}, enabled=${item["enabled"]}")
            }
            appendLine()
            appendLine("## ${RoleWorkspaceMarkdownSchema.Model.NOTES}")
            appendLine("- This runtime model profile lets multi-role features distinguish each role’s invocation configuration.")
            appendLine("- Never store a plaintext API key here; use the global gateway id when credentials are required.")
        }
    }

    private fun renderCompactSkillGroups(skills: List<SkillMeta>): String {
        if (skills.isEmpty()) return "- The skill index will be refreshed at runtime."
        return skills
            .filterNot { it.internalTool }
            .groupBy { it.categories.firstOrNull() ?: SkillToolCategory.SYSTEM }
            .toSortedMap(compareBy { it.name })
            .map { (category, metas) ->
                "- ${category.name}: ${metas.sortedBy { it.id }.take(20).joinToString(", ") { it.id }}"
            }
            .joinToString("\n")
    }

    private fun renderSkillIndex(skills: List<SkillMeta>): String {
        if (skills.isEmpty()) return "# Skill Index\n\nNo skills registered yet.\n"
        return buildString {
            appendLine("# Skill Index")
            appendLine()
            skills.filterNot { it.internalTool }
                .sortedWith(compareBy<SkillMeta> { it.categories.firstOrNull()?.name ?: "OTHER" }.thenBy { it.id })
                .forEach { meta ->
                    appendLine("## ${meta.id}")
                    appendLine("- Name: ${meta.name}")
                    appendLine("- Category: ${meta.categories.joinToString(", ") { it.name }.ifBlank { "OTHER" }}")
                    appendLine("- Level: ${meta.injectionLevel}")
                    appendLine("- Description: ${meta.description.replace('\n', ' ').take(240)}")
                    if (meta.parameters.isNotEmpty()) {
                        appendLine("- Params: ${meta.parameters.joinToString(", ") { p -> p.name + if (p.required) "*" else "" }}")
                    }
                    appendLine()
                }
        }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9_\\-]+"), "_").ifBlank { "role" }

    private fun maskSecret(value: String): String =
        when {
            value.isBlank() -> ""
            value.length <= 8 -> "***"
            else -> value.take(4) + "***" + value.takeLast(4)
        }

    private fun maskEndpoint(value: String): String = value.take(220)

    private fun roleModelBindingText(role: Role): String {
        val binding = role.effectiveModelBinding() ?: return "none"
        val normalized = binding.normalized()
        return when {
            normalized.localModelId.isNotBlank() -> "local:${normalized.localModelId.removePrefix("local:")}"
            normalized.gatewayName.isNotBlank() && normalized.model.isNotBlank() -> "${normalized.gatewayName} / ${normalized.model}"
            normalized.gatewayId.isNotBlank() && normalized.model.isNotBlank() -> "${normalized.gatewayId} / ${normalized.model}"
            normalized.gatewayName.isNotBlank() -> "${normalized.gatewayName} / default chat model"
            normalized.gatewayId.isNotBlank() -> "${normalized.gatewayId} / default chat model"
            normalized.model.isNotBlank() -> "default gateway / ${normalized.model}"
            else -> "none"
        }
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        const val CORE_MD = "core.md"
        const val SKILLS_MD = "skills.md"
        const val MEMORY_MD = "memory.md"
        const val MODEL_MD = "model.md"
        const val CHAT_PROTOCOL_MD = "chat_protocol.md"
        const val MODEL_CONFIG_JSON = "model_config.json"
        const val JOURNAL_MD = "journal.md"
        const val SKILL_INDEX_MD = "skill_index.md"
    }
}
