package com.mobileclaw.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiIntentRouterTest {
    private val prompt = AiIntentRoutingPrompt.build(
        goal = "Build a dashboard",
        contextPack = IntentContextPack(
            compressedContext = "The user previously discussed reporting.",
            recentContext = "User: Build a dashboard",
            activeWorkflowSummary = "No execution is currently active.",
            roleSummary = "Default role",
        ),
        hasImage = false,
        hasFile = false,
        activeWorkflow = null,
    )

    @Test
    fun `prompt documents the stable JSON schema and every enum value`() {
        val protocolKeys = listOf(
            "task_type",
            "requires_execution",
            "confidence",
            "reason",
            "normalized_goal",
            "target_app",
            "primary_channel",
            "supporting_channels",
            "tool_hints",
            "user_visible_steps",
        )

        protocolKeys.forEach { key -> assertTrue("Missing protocol key $key", prompt.contains("\"$key\"")) }
        TaskType.entries.forEach { taskType ->
            assertTrue("Missing task type ${taskType.name}", prompt.contains(taskType.name))
        }
        ChannelType.entries.forEach { channel ->
            assertTrue("Missing channel ${channel.name}", prompt.contains(channel.name))
        }
    }

    @Test
    fun `prompt distinguishes direct chat informational answers and execution`() {
        assertTrue(prompt.contains("either direct chat or an agent execution path"))
        assertTrue(prompt.contains("requires_execution=false"))
        assertTrue(prompt.contains("requires_execution=true"))
        assertTrue(prompt.contains("What kinds of apps can you build?"))
        assertTrue(prompt.contains("Can you build a page for me?"))
        assertTrue(prompt.contains("Can you connect MCP?"))
        assertTrue(prompt.contains("Connect this MCP server"))
    }

    @Test
    fun `direct chat contract remains tool free`() {
        assertTrue(prompt.contains("task_type=CHAT"))
        assertTrue(prompt.contains("primary_channel=CHAT"))
        assertTrue(prompt.contains("primary_channel=INFO"))
        assertTrue(prompt.contains("supporting_channels=[]"))
        assertTrue(prompt.contains("tool_hints=[]"))
        assertTrue(prompt.contains("user_visible_steps=[]"))
    }

    @Test
    fun `continuation semantics use latest-turn evidence and an English sequence`() {
        assertTrue(prompt.contains("Active workflow is context, not an automatic continuation command"))
        assertTrue(prompt.contains("A short \"Continue\" should continue"))
        assertTrue(prompt.contains("[\"Hi\", \"Build me a mini-app\", \"Continue\", \"Great, just chat with me now\"]"))
        assertTrue(prompt.contains("[CHAT, APP_BUILD/ARTIFACT, APP_BUILD/ARTIFACT, CHAT]"))
        assertTrue(prompt.contains("exits execution context and routes to CHAT"))
    }

    @Test
    fun `execution guidance preserves phone and artifact tool distinctions`() {
        assertTrue(prompt.contains("Use PHONE_CONTROL"))
        assertTrue(prompt.contains("Open Gmail"))
        assertTrue(prompt.contains("examples, not an app allowlist"))
        assertTrue(prompt.contains("APP_BUILD with primary_channel=ARTIFACT"))
        assertTrue(prompt.contains("include app_manager in tool_hints"))
        assertTrue(prompt.contains("include ui_builder in tool_hints"))
        assertTrue(prompt.contains("Do not route explicit MiniAPP, program, or runtime requests to ui_builder"))
        assertTrue(prompt.contains("Do not route explicit native-page requests to app_manager"))
    }

    @Test
    fun `textual output fields have an explicit English-only contract`() {
        assertTrue(prompt.contains("Write reason as concise English regardless of the input language"))
        assertTrue(prompt.contains("Write normalized_goal as a clear, concise, executable goal in English"))
        assertTrue(prompt.contains("Write user_visible_steps as concise, natural English"))
        assertTrue(prompt.contains("Preserve the actual app or product name in target_app"))
        assertFalse(prompt.contains("goal in the user's language"))
    }

    @Test
    fun `execution steps remain concrete user facing and limited to two through four`() {
        assertTrue(prompt.contains("Generate 2-4 short, concrete, user-facing steps only for execution routes"))
        assertTrue(prompt.contains("Find nearby restaurants that match the request"))
        assertTrue(prompt.contains("Open the target app and navigate to the order screen"))
        assertTrue(prompt.contains("Complete the missing piano-key code and fix the error"))
        assertTrue(prompt.contains("Bad steps: \"Confirm the goal\""))
        assertTrue(prompt.contains("Never mention internal configuration keys"))
    }

    @Test
    fun `prompt contains no Han characters`() {
        assertFalse(prompt.any { it.code in 0x3400..0x9FFF })
    }
}
