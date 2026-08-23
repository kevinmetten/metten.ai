package com.mobileclaw.memory

import com.mobileclaw.agent.TaskType
import com.mobileclaw.config.ConfigEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextBuilderTest {
    private val builder = MemoryContextBuilder()

    @Test
    fun `phone control selects phone facts by task type and key family`() {
        val facts = facts(
            "app.reddit.package" to "com.reddit.frontpage",
            "tool.phone.observe" to "Observe before acting.",
            "failure.phone.reddit" to "Dismiss the startup dialog.",
            "project.website.current" to "Marketing site",
            "failure.web.timeout" to "A source timed out.",
            "vpn.last_node" to "Example node",
            "model.active" to "Example model",
        )

        val direct = packet(TaskType.PHONE_CONTROL, "Open Reddit", facts = facts)
        val keywordHeavy = packet(TaskType.PHONE_CONTROL, "Use my phone to open Reddit", facts = facts)

        listOf(direct, keywordHeavy).forEach { result ->
            assertContainsKeys(result, "app.reddit.package", "tool.phone.observe", "failure.phone.reddit")
            assertTrue(result.appFacts.any { it.startsWith("tool.phone.observe:") })
            assertTrue(result.corrections.any { it.startsWith("failure.phone.reddit:") })
            assertExcludesKeys(
                result,
                "project.website.current",
                "failure.web.timeout",
                "vpn.last_node",
                "model.active",
            )
        }
    }

    @Test
    fun `web research exposes semantic config families without keyword broadening`() {
        val configs = configs(
            "research.preferred_sources" to "Primary sources",
            "user.name" to "Casey",
            "profile.locale" to "Canada",
            "preference.citations" to "Inline links",
            "document.default_format" to "PDF",
            "phone.preferred_app" to "Firefox",
            "random.setting" to "unrelated",
        )

        val result = packet(
            taskType = TaskType.WEB_RESEARCH,
            message = "Search the web for this",
            configs = configs,
        )

        assertConfigKeys(
            result,
            "research.preferred_sources",
            "user.name",
            "profile.locale",
            "preference.citations",
        )
        assertConfigExcludes(result, "document.default_format", "phone.preferred_app", "random.setting")
    }

    @Test
    fun `task families remain isolated across representative task types`() {
        val allFacts = facts(
            "project.mobile.current" to "App workspace",
            "ui.design.system" to "Warm neutral",
            "failure.ui.layout" to "Avoid overflow",
            "failure.document.export" to "Verify the PDF",
            "failure.image.crop" to "Preserve framing",
            "vpn.last_node" to "Example node",
            "failure.vpn.connect" to "Retry once",
            "failure.code.compile" to "Check imports",
            "correction.recent.browser" to "Do not browse automatically",
            "failure.general.retry" to "Avoid repeated retries",
            "lesson.general.verify" to "Verify completion",
        )

        val app = packet(TaskType.APP_BUILD, "Build an app", facts = allFacts)
        assertContainsKeys(app, "project.mobile.current", "ui.design.system", "failure.ui.layout")
        assertExcludesKeys(app, "vpn.last_node", "failure.vpn.connect")

        val file = packet(TaskType.FILE_CREATE, "Create a PDF", facts = allFacts)
        assertContainsKeys(file, "project.mobile.current", "failure.document.export")
        assertExcludesKeys(file, "failure.image.crop", "failure.vpn.connect")

        val vpn = packet(TaskType.VPN_CONTROL, "Connect", facts = allFacts)
        assertContainsKeys(vpn, "vpn.last_node", "failure.vpn.connect")
        assertExcludesKeys(vpn, "project.mobile.current", "failure.code.compile")

        val code = packet(TaskType.CODE_EXECUTION, "Run the checks", facts = allFacts)
        assertContainsKeys(code, "project.mobile.current", "failure.code.compile")
        assertExcludesKeys(code, "vpn.last_node", "failure.vpn.connect")

        val chat = packet(TaskType.CHAT, "What happened?", facts = allFacts)
        assertContainsKeys(chat, "correction.recent.browser", "failure.general.retry", "lesson.general.verify")
        assertExcludesKeys(chat, "project.mobile.current", "vpn.last_node")
    }

    @Test
    fun `global rules policies behaviors profiles and preferences cross task boundaries`() {
        val result = packet(
            taskType = TaskType.PHONE_CONTROL,
            message = "Open Reddit",
            facts = facts(
                "rule.user_do_not.browse" to "Do not browse without permission.",
                "tool.policy.web.permission" to "Ask before online research.",
                "agent.behavior.keep_current_role" to "Keep the current role.",
                "profile.name" to "Casey",
                "preference.chat.style" to "Concise answers",
            ),
        )

        assertContainsKeys(
            result,
            "rule.user_do_not.browse",
            "tool.policy.web.permission",
            "agent.behavior.keep_current_role",
            "profile.name",
            "preference.chat.style",
        )
        assertTrue(result.hardRules.size == 3)
        assertTrue(result.userFacts.any { it.contains("Casey") })
        assertTrue(result.preferences.any { it.contains("Concise answers") })
    }

    @Test
    fun `active session facts are strictly isolated and formatted in English`() {
        val sessionFacts = facts(
            "session.A.task.goal" to "Open Reddit",
            "session.A.task.summary" to "Reddit opened",
            "session.A.task.type" to "PHONE_CONTROL",
            "session.A.task.state" to "RUNNING",
            "session.A.task.status" to "ACTIVE",
            "session.B.task.goal" to "Open a document",
        )

        val active = packet(TaskType.PHONE_CONTROL, "Continue", facts = sessionFacts, activeSession = "A")
        assertContainsKeys(
            active,
            "session.A.task.goal",
            "session.A.task.summary",
            "session.A.task.type",
            "session.A.task.state",
            "session.A.task.status",
        )
        assertExcludesKeys(active, "session.B.task.goal")
        assertTrue(active.activeTaskMemory.contains("current goal: Open Reddit"))
        assertTrue(active.activeTaskMemory.contains("latest summary: Reddit opened"))
        assertTrue(active.activeTaskMemory.contains("task type: PHONE_CONTROL"))
        assertTrue(active.activeTaskMemory.contains("task state: RUNNING"))
        assertTrue(active.activeTaskMemory.contains("task status: ACTIVE"))

        val inactive = packet(TaskType.PHONE_CONTROL, "Continue", facts = sessionFacts)
        assertTrue(inactive.sourceKeys.none { it.startsWith("session.") })
        assertTrue(inactive.activeTaskMemory.isEmpty())
    }

    @Test
    fun `sensitive memory and config keys are excluded`() {
        val result = packet(
            taskType = TaskType.WEB_RESEARCH,
            message = "Research the topic",
            facts = facts(
                "tool.policy.web.sources" to "Use primary sources.",
                "profile.api_key" to "harmless-example",
                "rule.secret.note" to "harmless-example",
                "app.password_hint" to "harmless-example",
            ),
            configs = configs(
                "research.source_policy" to "Primary sources",
                "user.token" to "harmless-example",
                "research.credential" to "harmless-example",
            ),
        )

        assertContainsKeys(result, "tool.policy.web.sources")
        assertExcludesKeys(result, "profile.api_key", "rule.secret.note", "app.password_hint")
        assertConfigKeys(result, "research.source_policy")
        assertConfigExcludes(result, "user.token", "research.credential")
    }

    @Test
    fun `prompt keeps English sections and omits empty sections`() {
        val packet = MemoryContextPacket(
            hardRules = listOf("Do not browse without permission."),
            explicitConfig = listOf("task.tone: direct"),
        )

        val prompt = packet.toPrompt()

        assertTrue(prompt.contains("## Foundational Memory"))
        assertTrue(prompt.contains("Hard rules"))
        assertTrue(prompt.contains("Explicit user configuration"))
        assertFalse(prompt.contains("User preferences"))
        assertFalse(prompt.contains("Active session/task memory"))
        assertFalse(containsHan(prompt))
        assertEquals("", MemoryContextPacket().toPrompt())
    }

    private fun packet(
        taskType: TaskType,
        message: String,
        facts: List<MemoryFact> = emptyList(),
        configs: Map<String, ConfigEntry> = emptyMap(),
        activeSession: String? = null,
    ): MemoryContextPacket = builder.buildFromSnapshots(
        userMessage = message,
        taskType = taskType,
        userConfigEntries = configs,
        facts = facts,
        activeSessionScopeId = activeSession,
    )

    private fun facts(vararg entries: Pair<String, String>): List<MemoryFact> =
        entries.map { (key, value) -> MemoryFact(key = key, value = value) }

    private fun configs(vararg entries: Pair<String, String>): Map<String, ConfigEntry> =
        entries.associate { (key, value) -> key to ConfigEntry(value) }

    private fun assertContainsKeys(packet: MemoryContextPacket, vararg keys: String) =
        keys.forEach { assertTrue("Expected source key $it", packet.sourceKeys.contains(it)) }

    private fun assertExcludesKeys(packet: MemoryContextPacket, vararg keys: String) =
        keys.forEach { assertFalse("Unexpected source key $it", packet.sourceKeys.contains(it)) }

    private fun assertConfigKeys(packet: MemoryContextPacket, vararg keys: String) =
        keys.forEach { key -> assertTrue("Expected config key $key", packet.explicitConfig.any { it.startsWith("$key:") }) }

    private fun assertConfigExcludes(packet: MemoryContextPacket, vararg keys: String) =
        keys.forEach { key -> assertFalse("Unexpected config key $key", packet.explicitConfig.any { it.startsWith("$key:") }) }

    private fun containsHan(value: String): Boolean = value.any { it.code in 0x3400..0x9FFF }
}
