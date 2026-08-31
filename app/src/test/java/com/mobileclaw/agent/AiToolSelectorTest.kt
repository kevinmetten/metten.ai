package com.mobileclaw.agent

import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillToolCategory
import com.mobileclaw.skill.SkillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolSelectorTest {
    @Test
    fun `directory uses canonical English metadata and stable identifiers`() {
        val directory = AiToolSelectionContract.toolDirectory(
            listOf(
                skill(
                    id = "web_lookup",
                    name = "Web Lookup",
                    description = "Find current information from web sources.",
                    type = SkillType.HTTP,
                    categories = listOf(SkillToolCategory.WEB),
                ),
                skill(
                    id = "internal_probe",
                    name = "Internal Probe",
                    description = "Must not be selectable.",
                    internalTool = true,
                ),
            ),
        )

        assertTrue(directory.contains("web_lookup: Web Lookup"))
        assertTrue(directory.contains("Find current information from web sources."))
        assertTrue(directory.contains("type=HTTP"))
        assertTrue(directory.contains("categories=web"))
        assertFalse(directory.contains("ALT_NAME"))
        assertFalse(directory.contains("ALT_DESCRIPTION"))
        assertFalse(directory.contains("internal_probe"))
    }

    @Test
    fun `directory keeps injection sorting and machine-safe blank fallback`() {
        val directory = AiToolSelectionContract.toolDirectory(
            listOf(
                skill(id = "later", name = "Later", description = "On demand", injectionLevel = 2),
                skill(id = "always", name = "Always", description = "Always available", injectionLevel = 0),
            ),
        )
        val lines = directory.lines()

        assertTrue(lines[0].startsWith("- always:"))
        assertTrue(lines[1].startsWith("- blank_english: blank_english"))
        assertTrue(lines[1].contains("No English description available."))
        assertTrue(lines[2].startsWith("- later:"))
        assertFalse(directory.contains("ALT_NAME"))
    }

    @Test
    fun `prompt preserves selection semantics and English output contract`() {
        val input = ToolSelectionInput(
            goal = "Send the supplied Unicode text without changing it: مرحبا",
            taskType = TaskType.PHONE_CONTROL,
            primaryChannel = ChannelType.PHONE,
            roleSummary = "Phone operator",
            contextSummary = "A text field is focused.",
            preferredToolIds = listOf("input_text"),
            blockedToolIds = listOf("unsafe_tool"),
            routeToolHints = listOf("see_screen"),
            availableSkills = listOf(skill("input_text", "Input Text", "Enter text in an editable field.")),
        )

        val prompt = AiToolSelectionContract.buildPrompt(input)

        assertTrue(prompt.contains("smallest useful MobileClaw tool set"))
        assertTrue(prompt.contains("semantic fit, not keywords"))
        assertTrue(prompt.contains("generally 1-6 tools"))
        assertTrue(prompt.contains("preferred tools only when they are relevant"))
        assertTrue(prompt.contains("Never select blocked tools"))
        assertTrue(prompt.contains("empty selected_tool_ids array when no tool is needed"))
        assertTrue(prompt.contains("selected_tool_ids must contain exact IDs"))
        assertTrue(prompt.contains("Never translate or rewrite a tool ID"))
        assertTrue(prompt.contains("Write reason as concise English"))
        assertTrue(prompt.contains("execution_plan item as concise operational English"))
        assertTrue(prompt.contains("Return one JSON object only"))
        assertTrue(prompt.contains("مرحبا"))
    }

    @Test
    fun `parser filters unknown and duplicate ids and clamps confidence`() {
        val result = AiToolSelectionContract.parse(
            raw = """
                {
                  "selected_tool_ids": ["read_file", "unknown", "read_file", "create_file"],
                  "reason": "The task requires file updates.",
                  "execution_plan": ["Read the file", "Update it", "Verify the result"],
                  "confidence": 1.4
                }
            """.trimIndent(),
            knownIds = setOf("read_file", "create_file"),
        )

        requireNotNull(result)
        assertEquals(listOf("read_file", "create_file"), result.selectedToolIds)
        assertEquals("The task requires file updates.", result.reason)
        assertEquals(listOf("Read the file", "Update it", "Verify the result"), result.executionPlan)
        assertEquals(1f, result.confidence)
    }

    @Test
    fun `parser accepts fenced JSON and clamps low confidence`() {
        val result = AiToolSelectionContract.parse(
            raw = """
                ```json
                {"selected_tool_ids": ["see_screen"], "reason": "Screen inspection is required.", "execution_plan": ["Inspect the screen"], "confidence": -0.2}
                ```
            """.trimIndent(),
            knownIds = setOf("see_screen"),
        )

        requireNotNull(result)
        assertEquals(listOf("see_screen"), result.selectedToolIds)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun `primitive id fallback supports ASCII comma only`() {
        val result = AiToolSelectionContract.parse(
            raw = """{"selected_tool_ids":"read_file, create_file","reason":"Files are required.","execution_plan":"Read, Update","confidence":0.7}""",
            knownIds = setOf("read_file", "create_file"),
        )

        requireNotNull(result)
        assertEquals(listOf("read_file", "create_file"), result.selectedToolIds)
        assertEquals(listOf("Read", "Update"), result.executionPlan)
    }

    @Test
    fun `malformed output fails safely`() {
        assertNull(AiToolSelectionContract.parse("not JSON", setOf("read_file")))
        assertNull(AiToolSelectionContract.parse("```json\n{broken}\n```", setOf("read_file")))
    }

    private fun skill(
        id: String,
        name: String,
        description: String,
        type: SkillType = SkillType.NATIVE,
        injectionLevel: Int = 0,
        internalTool: Boolean = false,
        categories: List<SkillToolCategory> = listOf(SkillToolCategory.PHONE),
    ) = SkillMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        injectionLevel = injectionLevel,
        internalTool = internalTool,
        categories = categories,
    )
}
