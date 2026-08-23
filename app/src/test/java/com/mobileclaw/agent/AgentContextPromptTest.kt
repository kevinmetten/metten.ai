package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextPromptTest {
    private val filePrompt = buildSystemPrompt(
        skills = emptyList(),
        language = "en",
        taskType = TaskType.FILE_CREATE,
    )

    @Test
    fun `file channel and operating rules use contextual English follow-ups`() {
        assertTrue(filePrompt.contains("follow-ups such as \"continue\" or \"change it\""))
        assertTrue(filePrompt.contains("\"continue\", \"change it\", \"improve it\", \"not that one\", or \"try another approach\""))
        assertTrue(filePrompt.contains("when recent context supports that interpretation"))
        assertTrue(filePrompt.contains("do not start an unrelated new artifact"))
    }

    @Test
    fun `prompt explicitly preserves arbitrary Unicode task data`() {
        assertTrue(filePrompt.contains("Preserve user-supplied quoted text, names, messages, document content, code, URLs, and other task data"))
        assertTrue(filePrompt.contains("original Unicode form unless the user asks for translation or transformation"))
    }

    @Test
    fun `quick reply example is English and keeps wire syntax`() {
        assertTrue(filePrompt.contains("Task complete. What next? [[View|Again|Done]]"))
        assertTrue(filePrompt.contains("Place the tag at the very end"))
        assertTrue(filePrompt.contains("Use 2–4 short options"))
        assertTrue(filePrompt.contains("Do NOT add quick replies when calling a tool"))
    }

    @Test
    fun `embedded search and result examples use English labels and actions`() {
        assertTrue(filePrompt.contains("\"content\":\"Weather Search\""))
        assertTrue(filePrompt.contains("\"placeholder\":\"Enter a city\""))
        assertTrue(filePrompt.contains("\"label\":\"Search\""))
        assertTrue(filePrompt.contains("submit:Show today's weather for {city}"))
        assertTrue(filePrompt.contains("\"title\":\"Seattle Weather\""))
        assertTrue(filePrompt.contains("Good air quality"))
        assertTrue(filePrompt.contains("7-day forecast"))
        assertTrue(filePrompt.contains("Clothing tips"))
    }

    @Test
    fun `embedded dashboard and device examples use English labels`() {
        assertTrue(filePrompt.contains("\"content\":\"Weekly Activity\""))
        assertTrue(filePrompt.contains("\"label\":\"Total Distance\""))
        assertTrue(filePrompt.contains("\"label\":\"Best Day\""))
        assertTrue(filePrompt.contains("\"label\":\"Active Days\""))
        assertTrue(filePrompt.contains("\"labels\":[\"Mon\",\"Tue\",\"Wed\",\"Thu\",\"Fri\",\"Sat\",\"Sun\"]"))
        assertTrue(filePrompt.contains("\"title\":\"Device Information\""))
        assertTrue(filePrompt.contains("\"label\":\"Model\""))
        assertTrue(filePrompt.contains("\"label\":\"System\""))
        assertTrue(filePrompt.contains("\"label\":\"Storage\""))
        assertTrue(filePrompt.contains("\"label\":\"Battery\""))
    }

    @Test
    fun `native page and mini app routing identifiers remain intact`() {
        assertTrue(filePrompt.contains("ui_builder(action=create, title=\"My Page\""))
        assertTrue(filePrompt.contains("ui_builder(action=open, id=\"returned_id\")"))
        assertTrue(filePrompt.contains("ui_builder(action=analyze_change"))
        assertTrue(filePrompt.contains("app_manager(action=analyze_change"))
        assertTrue(filePrompt.contains("app_manager(action=validate"))
        assertTrue(filePrompt.contains("create_html"))
    }

    @Test
    fun `phone control architecture and tool ids remain intact`() {
        val phonePrompt = buildSystemPrompt(
            skills = emptyList(),
            language = "en",
            taskType = TaskType.PHONE_CONTROL,
        )

        listOf(
            "see_screen",
            "screenshot",
            "read_screen",
            "bg_screenshot",
            "bg_read_screen",
            "tap",
            "scroll",
            "long_click",
            "input_text",
            "navigate",
            "phone_status",
        ).forEach { id -> assertTrue("Missing phone-control ID $id", phonePrompt.contains(id)) }
    }

    @Test
    fun `controlled prompt contains no Han characters and keeps sticker tool reference`() {
        assertFalse(filePrompt.any { it.code in 0x3400..0x9FFF })
        assertTrue(filePrompt.contains("sticker_bqb"))
    }

    @Test
    fun `toMessages preserves non-English goal content exactly`() {
        val goal = "أرسل هذه الرسالة كما هي: مرحبا 👋"
        val context = AgentContext(taskId = "unicode-task", goal = goal, imageBase64 = "image-data")

        val messages = context.toMessages(systemPrompt = "System prompt")

        assertEquals(goal, messages[1].content)
        assertEquals("image-data", messages[1].imageBase64)
    }
}
