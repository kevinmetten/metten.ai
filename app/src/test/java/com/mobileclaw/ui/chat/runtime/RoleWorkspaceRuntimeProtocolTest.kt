package com.mobileclaw.ui.chat.runtime

import com.mobileclaw.agent.Role
import com.mobileclaw.agent.RoleWorkspaceMarkdownSchema
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

        assertEquals(ChatExecutionMode.AGENT, plan.executionModeHint)
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
