package com.mobileclaw.ui.chat.runtime

import com.mobileclaw.agent.Role
import com.mobileclaw.agent.RoleWorkspaceMarkdownMigrator
import com.mobileclaw.agent.RoleWorkspaceMarkdownSchema
import com.mobileclaw.agent.RoleWorkspaceStore
import com.mobileclaw.agent.RoleWorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleWorkspaceRuntimeProtocolTest {
    @Test
    fun `execution protocol parser reads every canonical section`() {
        val markdown = canonicalProtocol()

        val protocol = RoleExecutionProtocolParser.parse("test", markdown)

        assertEquals(3, protocol.version)
        assertTrue(protocol.inputUnderstanding.contains("recent conversation"))
        assertTrue(protocol.contextReading.contains("skill_index.md"))
        assertTrue(protocol.memoryPolicy.contains("durable preferences"))
        assertTrue(protocol.skillPolicy.contains("Blocked tools"))
        assertTrue(protocol.responsePolicy.contains("compact summary"))
        assertTrue(protocol.persistencePolicy.contains("journal.md"))
    }

    @Test
    fun `runtime section extraction uses canonical headings`() {
        val core = "# Role\n\n## Working Method\nObserve, act, verify.\n\n## Working Boundaries\nStay scoped.\n"
        val memory = "# Role Memory\n\n## Stable Preferences\nPrefer concise updates.\n"

        assertEquals(
            "Observe, act, verify.",
            extractRoleWorkspaceSection(core, RoleWorkspaceMarkdownSchema.Core.WORKING_METHOD),
        )
        assertEquals(
            "Prefer concise updates.",
            extractRoleWorkspaceSection(memory, RoleWorkspaceMarkdownSchema.Memory.STABLE_PREFERENCES),
        )
    }

    @Test
    fun `control plan derives English workspace policies`() {
        val protocol = RoleExecutionProtocolParser.parse("test", canonicalProtocol())
        val profile = profile(protocol)

        val plan = RoleChatControlPlanCompiler.compile(profile)

        assertEquals(null, plan.executionModeHint)
        assertTrue(plan.contextPolicy.includeRecentMessages)
        assertTrue(plan.contextPolicy.includeUserMemory)
        assertTrue(plan.contextPolicy.readRoleFiles.containsAll(listOf("core.md", "memory.md", "model.md", "skills.md", "skill_index.md")))
        assertTrue(plan.toolPolicy.allowMcp)
        assertEquals(listOf("preferred_tool"), plan.toolPolicy.preferredToolIds)
        assertEquals(listOf("dangerous_tool"), plan.toolPolicy.blockedToolIds)
        assertEquals("resolve_from_recent_context", plan.intentPolicy.shortFollowUpMode)
        assertEquals("active_artifact_aware", plan.intentPolicy.artifactReferenceMode)
        assertEquals("concise_direct", plan.responsePolicy.style)
        assertEquals("compact", plan.responsePolicy.completionSummaryMode)
        assertTrue(plan.persistencePolicy.writeJournalOnCompletion)
        assertTrue(plan.persistencePolicy.allowRoleMemoryWrite)
        assertFalse(plan.visibilityPolicy.exposeTraceByDefault)
    }

    @Test
    fun `managed execution preferences round trip with authoritative values`() {
        val ordinaryPolicy = """
            - Answer ordinary questions directly.
            - When action is required, enter the tool or agent flow.
        """.trimIndent()

        assertEquals(RoleExecutionPreference.AUTO, RoleExecutionPreferenceProtocol.parse(ordinaryPolicy))
        assertEquals(null, RoleChatControlPlanCompiler.compile(profile(protocol(skillPolicy = ordinaryPolicy))).executionModeHint)
        assertEquals(
            RoleExecutionPreference.AUTO,
            RoleExecutionPreferenceProtocol.parse("- Execution mode: auto\n- Agent first."),
        )

        val directPolicy = RoleExecutionPreferenceProtocol.update(ordinaryPolicy, RoleExecutionPreference.DIRECT_FIRST)
        val directReloaded = RoleExecutionProtocolParser.parse(
            "test",
            protocol(skillPolicy = directPolicy, responsePolicy = "- Agent first would otherwise be ambiguous.").toMarkdown(),
        )
        assertTrue(directPolicy.contains("Execution mode: direct_first"))
        assertEquals(RoleExecutionPreference.DIRECT_FIRST, RoleExecutionPreferenceProtocol.parse(directReloaded.skillPolicy, directReloaded.responsePolicy))
        assertEquals(ChatExecutionMode.DIRECT_CHAT, RoleChatControlPlanCompiler.compile(profile(directReloaded)).executionModeHint)

        val agentPolicy = RoleExecutionPreferenceProtocol.update(ordinaryPolicy, RoleExecutionPreference.AGENT_FIRST)
        val agentReloaded = RoleExecutionProtocolParser.parse(
            "test",
            protocol(skillPolicy = agentPolicy, responsePolicy = "- Prefer direct responses when possible.").toMarkdown(),
        )
        assertTrue(agentPolicy.contains("Execution mode: agent_first"))
        assertEquals(RoleExecutionPreference.AGENT_FIRST, RoleExecutionPreferenceProtocol.parse(agentReloaded.skillPolicy, agentReloaded.responsePolicy))
        assertEquals(ChatExecutionMode.AGENT, RoleChatControlPlanCompiler.compile(profile(agentReloaded)).executionModeHint)

        val autoAgain = RoleExecutionPreferenceProtocol.update(agentPolicy, RoleExecutionPreference.AUTO)
        assertFalse(autoAgain.contains("Execution mode:"))
        assertEquals(RoleExecutionPreference.AUTO, RoleExecutionPreferenceProtocol.parse(autoAgain))
    }

    @Test
    fun `real legacy stock protocol bodies migrate without changing runtime semantics`() {
        val legacy = legacyStockProtocol()
        val migrated = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, legacy)
        val migratedAgain = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, migrated)
        val parsed = RoleExecutionProtocolParser.parse("test", migrated)
        val plan = RoleChatControlPlanCompiler.compile(profile(parsed))
        val canonicalPlan = RoleChatControlPlanCompiler.compile(
            profile(RoleExecutionProtocolParser.parse("test", canonicalStockProtocol())),
        )

        assertEquals(migrated, migratedAgain)
        assertTrue(migrated.contains("## Input Understanding"))
        assertTrue(migrated.contains("Distinguish ordinary chat, a follow-up, revision of the active artifact, and a request to perform an action."))
        assertTrue(migrated.contains("Answer ordinary questions directly. When the user's goal requires action, enter the tool or agent flow autonomously."))
        assertFalse(migrated.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        assertTrue(parsed.inputUnderstanding.isNotBlank())
        assertTrue(parsed.contextReading.contains("skill_index.md"))
        assertEquals(null, plan.executionModeHint)
        assertTrue(plan.contextPolicy.includeRecentMessages)
        assertTrue(plan.contextPolicy.readRoleFiles.containsAll(listOf("core.md", "memory.md", "model.md", "skills.md", "skill_index.md")))
        assertTrue(plan.persistencePolicy.writeJournalOnCompletion)
        assertTrue(plan.persistencePolicy.allowRoleMemoryWrite)
        assertEquals(canonicalPlan.executionModeHint, plan.executionModeHint)
        assertEquals(canonicalPlan.contextPolicy, plan.contextPolicy)
        assertEquals(canonicalPlan.intentPolicy, plan.intentPolicy)
        assertEquals(canonicalPlan.responsePolicy, plan.responsePolicy)
        assertEquals(canonicalPlan.visibilityPolicy, plan.visibilityPolicy)
        assertEquals(canonicalPlan.persistencePolicy, plan.persistencePolicy)
    }

    @Test
    fun `legacy managed preferences retain direct and agent selections`() {
        val legacyDirectExecution = legacy("2d 20 45 78 65 63 75 74 69 6f 6e 20 70 72 65 66 65 72 65 6e 63 65 3a 20 666e 901a 95ee 7b54 76f4 63a5 56de 7b54 ff1b 9700 8981 884c 52a8 65f6 8fdb 5165 20 61 67 65 6e 74 3002")
        val legacyAgentExecution = legacy("2d 20 45 78 65 63 75 74 69 6f 6e 20 70 72 65 66 65 72 65 6e 63 65 3a 20 4f18 5148 20 61 67 65 6e 74 ff1b 6267 884c 7c7b 4efb 52a1 8fdb 5165 20 61 67 65 6e 74 ff1b 9700 8981 884c 52a8 65f6 8fdb 5165 5de5 5177 3002")
        val legacyDirectResponse = legacy("2d 20 52 65 73 70 6f 6e 73 65 20 70 72 65 66 65 72 65 6e 63 65 3a 20 76f4 63a5 56de 7b54 4f18 5148 ff0c 5c11 5c55 793a 5185 90e8 8fc7 7a0b 3002")
        val legacyAgentResponse = legacy("2d 20 52 65 73 70 6f 6e 73 65 20 70 72 65 66 65 72 65 6e 63 65 3a 20 6267 884c 4f18 5148 ff0c 5b8c 6210 540e 6c47 62a5 7ed3 679c 548c 5173 952e 8fc7 7a0b 3002")

        listOf(
            LegacyPreferenceCase(
                executionLine = legacyDirectExecution,
                responseLine = legacyDirectResponse,
                expectedPreference = RoleExecutionPreference.DIRECT_FIRST,
                expectedHint = ChatExecutionMode.DIRECT_CHAT,
                canonicalMode = "direct_first",
                canonicalResponse = "- Response preference: prefer direct answers and minimize internal-process detail.",
            ),
            LegacyPreferenceCase(
                executionLine = legacyAgentExecution,
                responseLine = legacyAgentResponse,
                expectedPreference = RoleExecutionPreference.AGENT_FIRST,
                expectedHint = ChatExecutionMode.AGENT,
                canonicalMode = "agent_first",
                canonicalResponse = "- Response preference: prefer execution, then report results and key steps.",
            ),
        ).forEach { case ->
            val original = managedPreferenceProtocol(case.executionLine, case.responseLine)
            val migrated = RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, original)
            val parsed = RoleExecutionProtocolParser.parse("test", migrated)

            assertEquals(migrated, RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, migrated))
            assertEquals(1, Regex("^\\s*- Execution mode:", RegexOption.MULTILINE).findAll(migrated).count())
            assertTrue(migrated.contains("- Execution mode: ${case.canonicalMode}"))
            assertTrue(migrated.contains(case.canonicalResponse))
            assertTrue(migrated.contains("Custom skill instruction."))
            assertTrue(migrated.contains("Custom response instruction."))
            assertFalse(migrated.contains(case.executionLine))
            assertFalse(migrated.contains(case.responseLine))
            assertEquals(case.expectedPreference, RoleExecutionPreferenceProtocol.parse(parsed.skillPolicy, parsed.responsePolicy))
            assertEquals(case.expectedHint, RoleChatControlPlanCompiler.compile(profile(parsed)).executionModeHint)
        }
    }

    @Test
    fun `English intermediate preferences migrate and canonical field wins conflicts`() {
        listOf(
            "- Execution preference: answer ordinary questions directly; enter the agent for action requests." to "direct_first",
            "- Execution preference: agent first; use tools for execution requests." to "agent_first",
        ).forEach { (legacyLine, expectedMode) ->
            val migrated = RoleWorkspaceMarkdownMigrator.migrate(
                RoleWorkspaceStore.CHAT_PROTOCOL_MD,
                managedPreferenceProtocol(legacyLine, "Custom response instruction."),
            )
            assertTrue(migrated.contains("- Execution mode: $expectedMode"))
            assertFalse(migrated.contains(legacyLine))
            assertEquals(migrated, RoleWorkspaceMarkdownMigrator.migrate(RoleWorkspaceStore.CHAT_PROTOCOL_MD, migrated))
        }

        val canonicalWins = RoleWorkspaceMarkdownMigrator.migrate(
            RoleWorkspaceStore.CHAT_PROTOCOL_MD,
            managedPreferenceProtocol(
                "- Execution mode: agent_first\n- Execution preference: answer ordinary questions directly; enter the agent for action requests.",
                "Custom response instruction.",
            ),
        )
        val parsed = RoleExecutionProtocolParser.parse("test", canonicalWins)
        assertEquals(1, Regex("^\\s*- Execution mode:", RegexOption.MULTILINE).findAll(canonicalWins).count())
        assertTrue(canonicalWins.contains("- Execution mode: agent_first"))
        assertFalse(canonicalWins.contains("Execution preference:"))
        assertEquals(RoleExecutionPreference.AGENT_FIRST, RoleExecutionPreferenceProtocol.parse(parsed.skillPolicy, parsed.responsePolicy))
        assertEquals(ChatExecutionMode.AGENT, RoleChatControlPlanCompiler.compile(profile(parsed)).executionModeHint)
    }

    private fun canonicalStockProtocol(): String = """
        # Chat Execution Protocol

        ## Runtime Contract
        - Role id: test
        - Protocol version: 1

        ## Input Understanding
        - Distinguish ordinary chat, a follow-up, revision of the active artifact, and a request to perform an action.
        - Resolve short follow-ups from recent conversation, the active workspace, and the current role task.
        - Treat the role as a working identity and execution method, not merely a speaking style.

        ## Context Reading
        - Read core.md, memory.md, model.md, skills.md, and skill_index.md as appropriate.
        - The latest user intent remains authoritative over workspace and artifact context.

        ## Memory Policy
        - Persist durable preferences and reusable experience in memory.md; write task history to journal.md.

        ## Skill Policy
        - Discover skills on demand.
        - Answer ordinary questions directly. When action is required, enter the tool or agent flow.

        ## Response Policy
        - Respond to ordinary chat directly and clearly.
        - Summarize execution results, risks, and next steps.

        ## Persistence Policy
        - Append important completed work to journal.md.
        - Update memory.md or core.md for durable changes.
        - Runtime writes configuration to model.md and model_config.json without secrets.
    """.trimIndent() + "\n"

    private fun managedPreferenceProtocol(executionLine: String, responseLine: String): String = """
        # Chat Execution Protocol

        ## Runtime Contract
        - Role id: test
        - Protocol version: 1

        ## Input Understanding
        - Resolve short follow-ups from recent conversation.

        ## Skill Policy
        $executionLine
        Custom skill instruction.

        ## Response Policy
        $responseLine
        Custom response instruction.

        ## Persistence Policy
        - Append important completed work to journal.md.
    """.trimIndent() + "\n"

    private data class LegacyPreferenceCase(
        val executionLine: String,
        val responseLine: String,
        val expectedPreference: RoleExecutionPreference,
        val expectedHint: ChatExecutionMode,
        val canonicalMode: String,
        val canonicalResponse: String,
    )

    private fun protocol(
        skillPolicy: String,
        responsePolicy: String = "- Respond directly and clearly.",
    ): RoleExecutionProtocol = RoleExecutionProtocol(
        roleId = "test",
        inputUnderstanding = "- Resolve short follow-ups from recent conversation.",
        contextReading = "- Read core.md and memory.md.",
        memoryPolicy = "- Persist durable preferences in memory.md.",
        skillPolicy = skillPolicy,
        responsePolicy = responsePolicy,
        persistencePolicy = "- Append important completed work to journal.md.",
    )

    private fun canonicalProtocol(): String = """
        # Chat Execution Protocol

        ## Runtime Contract
        - Role id: test
        - Protocol version: 3

        ## Input Understanding
        - Resolve short follow-ups from recent conversation and the active artifact.

        ## Context Reading
        - Read core.md, memory.md, model.md, skills.md, and skill_index.md.

        ## Memory Policy
        - Persist durable preferences in memory.md.

        ## Skill Policy
        - Action requests enter the tool or agent flow.
        - Blocked tools:
        - dangerous_tool

        ## Response Policy
        - Answer ordinary questions directly with a compact summary.

        ## Persistence Policy
        - Append important completed work to journal.md.
    """.trimIndent() + "\n"

    private fun legacyStockProtocol(): String = buildString {
        appendLine("# Chat Execution Protocol")
        appendLine("## Runtime Contract")
        appendLine("- Role id: test")
        appendLine("- Protocol version: 1")
        appendLine("- This file defines how the role drives MobileClaw Chat Runtime stages.")
        appendLine("## Input Understanding")
        appendLine(legacy("2d 20 5148 5224 65ad 7528 6237 662f 5728 95f2 804a 3001 8ffd 95ee 3001 4fee 6539 5f53 524d 4ea7 7269 ff0c 8fd8 662f 8981 6c42 6267 884c 4e00 4e2a 52a8 4f5c 3002"))
        appendLine(legacy("2d 20 77ed 53e5 5982 201c 7ee7 7eed 201d 201c 91cd 8bd5 201d 201c 4e0d 5bf9 201d 201c 6539 4e00 4e0b 201d 5fc5 987b 7ed3 5408 6700 8fd1 5bf9 8bdd 3001 6d3b 52a8 5de5 4f5c 533a 548c 5f53 524d 89d2 8272 4efb 52a1 7406 89e3 3002"))
        appendLine(legacy("2d 20 4e0d 628a 89d2 8272 7406 89e3 6210 8bed 6c14 5305 ff1b 89d2 8272 662f 4e00 5957 5de5 4f5c 8eab 4efd 548c 6267 884c 65b9 6cd5 3002"))
        appendLine("## Context Reading")
        appendLine(legacy("2d 20 4f18 5148 8bfb 53d6 20 63 6f 72 65 2e 6d 64 20 7406 89e3 89d2 8272 5b9a 4f4d 548c 8fb9 754c 3002"))
        appendLine(legacy("2d 20 8bfb 53d6 20 6d 65 6d 6f 72 79 2e 6d 64 20 83b7 53d6 957f 671f 504f 597d 3001 534f 4f5c 4e60 60ef 548c 53ef 590d 7528 7ecf 9a8c 3002"))
        appendLine(legacy("2d 20 8bfb 53d6 20 6d 6f 64 65 6c 2e 6d 64 20 83b7 53d6 89d2 8272 6700 8fd1 4f7f 7528 7684 6a21 578b 548c 7f51 5173 914d 7f6e 753b 50cf 3002"))
        appendLine(legacy("2d 20 9700 8981 6280 80fd 65f6 6309 9700 67e5 770b 20 73 6b 69 6c 6c 73 2e 6d 64 20 548c 20 73 6b 69 6c 6c 5f 69 6e 64 65 78 2e 6d 64 ff0c 4e0d 8981 51ed 8bb0 5fc6 731c 6d4b 6280 80fd 80fd 529b 3002"))
        appendLine(legacy("2d 20 5de5 4f5c 533a 548c 20 61 72 74 69 66 61 63 74 20 4e0a 4e0b 6587 53ea 7528 4e8e 89e3 51b3 5f53 524d 4efb 52a1 ff0c 4e0d 8981 8986 76d6 6700 65b0 7528 6237 610f 56fe 3002"))
        appendLine("## Memory Policy")
        appendLine(legacy("2d 20 53ea 6c89 6dc0 7a33 5b9a 504f 597d 3001 91cd 8981 4e8b 4ef6 8282 70b9 3001 89d2 8272 5de5 4f5c 4e60 60ef 548c 53ef 590d 7528 4efb 52a1 7ecf 9a8c 3002"))
        appendLine(legacy("2d 20 4e00 6b21 6027 95f2 804a 3001 4e34 65f6 60c5 7eea 3001 8fc7 671f 72b6 6001 4e0d 5199 5165 957f 671f 8bb0 5fc6 3002"))
        appendLine(legacy("2d 20 5982 679c 5b66 5230 5173 4e8e 7528 6237 6216 89d2 8272 7684 91cd 8981 4e8b 5b9e ff0c 4f18 5148 8ffd 52a0 20 6d 65 6d 6f 72 79 2e 6d 64 ff1b 5982 679c 53ea 662f 4e00 6b21 4efb 52a1 8fc7 7a0b ff0c 5199 5165 20 6a 6f 75 72 6e 61 6c 2e 6d 64 3002"))
        appendLine("## Skill Policy")
        appendLine(legacy("2d 20 6240 6709 6280 80fd 90fd 53ef 4ee5 6309 9700 53d1 73b0 548c 8bfb 53d6 ff0c 4f46 5fc5 987b 5148 5224 65ad 4efb 52a1 662f 5426 771f 7684 9700 8981 6280 80fd 3002"))
        appendLine(legacy("2d 20 89d2 8272 53ef 4ee5 8de8 804a 5929 3001 6587 4ef6 3001 9875 9762 3001 624b 673a 64cd 4f5c 3001 8054 7f51 3001 4d 43 50 3001 7cfb 7edf 914d 7f6e 7b49 80fd 529b 57df 6267 884c ff1b 4e0d 8981 56e0 4e3a 89d2 8272 7c7b 578b 6216 804a 5929 5165 53e3 9650 5236 6280 80fd 9009 62e9 3002"))
        appendLine(legacy("2d 20 666e 901a 95ee 7b54 53ef 4ee5 76f4 63a5 56de 7b54 ff1b 4e00 65e6 7528 6237 76ee 6807 9700 8981 884c 52a8 ff0c 5c31 81ea 4e3b 8fdb 5165 5de5 5177 2f 61 67 65 6e 74 20 6d41 7a0b 3002"))
        appendLine(legacy("2d 20 5f53 524d 89d2 8272 6ca1 6709 5f3a 5236 6280 80fd ff1b 6309 4efb 52a1 9700 8981 9009 62e9 3002"))
        appendLine(legacy("2d 20 6267 884c 6d41 7a0b 5e94 6309 4efb 52a1 590d 6742 5ea6 81ea 9002 5e94 ff1a 7b80 5355 76f4 63a5 7b54 ff0c 590d 6742 4efb 52a1 62c6 6b65 6267 884c 5e76 5728 5173 952e 8282 70b9 6c89 6dc0 3002"))
        appendLine("## Response Policy")
        appendLine(legacy("2d 20 666e 901a 804a 5929 76f4 63a5 3001 6e05 695a 5730 56de 5e94 7528 6237 3002"))
        appendLine(legacy("2d 20 6267 884c 7c7b 4efb 52a1 8bf4 660e 505a 4e86 4ec0 4e48 3001 7ed3 679c 5728 54ea 91cc 3001 8fd8 6709 4ec0 4e48 98ce 9669 6216 4e0b 4e00 6b65 3002"))
        appendLine(legacy("2d 20 5982 679c 9700 8981 5199 5165 8bb0 5fc6 6216 89d2 8272 6587 4ef6 ff0c 5e94 5728 5b8c 6210 4efb 52a1 540e 6c89 6dc0 ff0c 4e0d 8981 628a 5185 90e8 6587 4ef6 64cd 4f5c 53d8 6210 5197 957f 89e3 91ca 3002"))
        appendLine("## Persistence Policy")
        appendLine(legacy("2d 20 91cd 8981 4efb 52a1 5b8c 6210 540e 8ffd 52a0 20 6a 6f 75 72 6e 61 6c 2e 6d 64 ff0c 8bb0 5f55 65f6 95f4 3001 76ee 6807 3001 7ed3 679c 548c 53ef 590d 7528 7ecf 9a8c 3002"))
        appendLine(legacy("2d 20 89d2 8272 504f 597d 3001 5de5 4f5c 94fe 8def 3001 56de 590d 4e60 60ef 53d8 5316 65f6 66f4 65b0 20 6d 65 6d 6f 72 79 2e 6d 64 20 6216 20 63 6f 72 65 2e 6d 64 3002"))
        appendLine(legacy("2d 20 6a21 578b 548c 7f51 5173 914d 7f6e 7531 8fd0 884c 65f6 5199 5165 20 6d 6f 64 65 6c 2e 6d 64 20 2f 20 6d 6f 64 65 6c 5f 63 6f 6e 66 69 67 2e 6a 73 6f 6e ff0c 4e0d 5728 672c 6587 4ef6 4fdd 5b58 5bc6 94a5 3002"))
    }.trimEnd() + "\n"


    private fun legacy(hex: String): String = hex
        .split(" ")
        .joinToString("") { codePoint -> String(Character.toChars(codePoint.toInt(16))) }

    private fun profile(protocol: RoleExecutionProtocol): RoleRuntimeProfile = RoleRuntimeProfile(
        role = Role(
            id = "test",
            name = "Test",
            description = "Test role",
            avatar = "role:test",
            forcedSkillIds = listOf("preferred_tool"),
        ),
        workspace = RoleWorkspaceSnapshot(
            roleId = "test",
            rootPath = "/tmp/test",
            core = "",
            skills = "",
            memory = "",
            model = "",
            chatProtocol = protocol.rawMarkdown,
        ),
        protocol = protocol,
        skills = emptyList(),
        workspacePrompt = "",
        compiledPrompt = "",
    )
}
