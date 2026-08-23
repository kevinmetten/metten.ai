package com.mobileclaw.memory

import com.mobileclaw.memory.db.ConversationEntity
import com.mobileclaw.memory.db.EpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileExtractorTest {
    @Test
    fun `conversation snippet uses English role labels and preserves recent content`() {
        val messages = (1..18).map { message(it, "user", "user message $it") } + listOf(
            message(19, "agent", "assistant response"),
            message(20, "tool", "tool observation"),
            message(21, "user", "recent user content"),
            message(22, "assistant", "recent assistant content"),
        )

        val snippet = UserProfileExtractionSupport.buildConversationSnippet(
            messages = messages,
            goal = "Prepare the report",
            summary = "Draft completed",
        )

        assertTrue(snippet.contains("Current task: Prepare the report"))
        assertTrue(snippet.contains("Task result: Draft completed"))
        assertTrue(snippet.contains("Recent conversation:"))
        assertTrue(snippet.contains("User: recent user content"))
        assertTrue(snippet.contains("Assistant: assistant response"))
        assertTrue(snippet.contains("Assistant: recent assistant content"))
        assertTrue(snippet.contains("Observation: tool observation"))
        assertFalse(snippet.lineSequence().any { it == "User: user message 1" })
        assertFalse(snippet.lineSequence().any { it == "User: user message 2" })
        assertTrue(snippet.lineSequence().any { it == "User: user message 3" })
        assertFalse(containsHan(snippet))
    }

    @Test
    fun `episode analysis reports quantitative evidence and observed skill usage`() {
        val episodes = listOf(
            episode("1", "Research release notes", true, "web_search", "shell"),
            episode("2", "Inspect the uploaded screenshot", true, "see_screen", "screenshot"),
            episode("3", "Retry deployment", false, "shell", "navigate"),
            episode("4", "Collect references", true, "web_browse", "memory"),
        )

        val analysis = UserProfileExtractionSupport.buildEpisodeAnalysis(episodes)

        assertTrue(analysis.contains("Task statistics: 4 total; 3 successful; 75% success rate"))
        assertTrue(analysis.contains("Average task length:"))
        assertTrue(analysis.contains("Web-related tasks: 2"))
        assertTrue(analysis.contains("Technical operations: 2"))
        assertTrue(analysis.contains("Visual-analysis tasks: 1"))
        assertTrue(analysis.contains("Skill usage:"))
        assertTrue(analysis.contains("technical command-line usage"))
        assertTrue(analysis.contains("visual screen-analysis usage"))
        assertTrue(analysis.contains("Recent task examples:"))
        assertTrue(analysis.contains("SUCCESS: Research release notes"))
        assertTrue(analysis.contains("FAILURE: Retry deployment"))
        assertFalse(containsHan(analysis))
    }

    @Test
    fun `conversation prompt defines conservative durable English JSON contract`() {
        val prompt = UserProfileExtractionSupport.conversationPrompt("Recent conversation:\nUser: Always cite sources.")

        assertTrue(prompt.contains("durable information"))
        assertTrue(prompt.contains("Do not guess"))
        assertTrue(prompt.contains("Explicit user corrections"))
        assertTrue(prompt.contains("Durable preferences, prohibitions, requirements, and tool-use policies"))
        assertTrue(prompt.contains("Do not infer a durable fact from a one-off request"))
        assertTrue(prompt.contains("at most 8 facts"))
        assertTrue(prompt.contains("JSON array only"))
        assertTrue(prompt.contains("concise factual English value"))
        assertTrue(prompt.contains("preference.chat.style"))
        assertTrue(prompt.contains("tool.policy.image_understanding.no_web_search"))
        assertFalse(containsHan(prompt))
    }

    @Test
    fun `episode prompt requires repeated evidence without personality inference`() {
        val prompt = UserProfileExtractionSupport.episodePrompt("Task statistics: 3 total")

        assertTrue(prompt.contains("repeated task-history patterns"))
        assertTrue(prompt.contains("Require sufficient evidence across repeated episodes"))
        assertTrue(prompt.contains("repeated failure patterns, reusable lessons"))
        assertTrue(prompt.contains("not as personality"))
        assertTrue(prompt.contains("Do not infer unsupported sensitive or psychological conclusions"))
        assertTrue(prompt.contains("factual English value"))
        assertTrue(prompt.contains("JSON array only"))
        assertFalse(containsHan(prompt))
    }

    @Test
    fun `JSON parser accepts raw and deliberately fenced arrays`() {
        val raw = """[{"key":"preference.chat.style","value":" Concise answers. ","confidence":0.9,"ignored":true}]"""
        val fenced = """
            ```json
            [{"key":"lesson.web.verify","value":"Verify source dates."}]
            ```
        """.trimIndent()

        assertEquals(
            listOf(ProfileFact("preference.chat.style", "Concise answers.", 0.9f)),
            UserProfileExtractionSupport.parseFactsJson(raw),
        )
        assertEquals(
            listOf(ProfileFact("lesson.web.verify", "Verify source dates.", 0.5f)),
            UserProfileExtractionSupport.parseFactsJson(fenced),
        )
    }

    @Test
    fun `JSON parser skips incomplete items and keeps multiple valid facts`() {
        val response = """
            [
              {"key":"preference.chat.style","value":"Direct answers.","confidence":0.8},
              {"value":"Missing key."},
              {"key":"lesson.web.verify"},
              {"key":"tool.policy.web.sources","value":"Use primary sources.","confidence":1.2}
            ]
        """.trimIndent()

        val facts = UserProfileExtractionSupport.parseFactsJson(response)

        assertEquals(2, facts.size)
        assertEquals("preference.chat.style", facts[0].key)
        assertEquals("tool.policy.web.sources", facts[1].key)
        assertEquals(1.2f, facts[1].confidence)
    }

    @Test
    fun `JSON parser rejects blank malformed prose and incomplete fences`() {
        assertTrue(UserProfileExtractionSupport.parseFactsJson("").isEmpty())
        assertTrue(UserProfileExtractionSupport.parseFactsJson("not json").isEmpty())
        assertTrue(UserProfileExtractionSupport.parseFactsJson("Here is JSON: []").isEmpty())
        assertTrue(UserProfileExtractionSupport.parseFactsJson("```json\n[]").isEmpty())
        assertTrue(UserProfileExtractionSupport.parseFactsJson("{} ").isEmpty())
    }

    private fun message(index: Int, role: String, content: String) = ConversationEntity(
        id = "message-$index",
        role = role,
        content = content,
        createdAt = index.toLong(),
    )

    private fun episode(id: String, goal: String, success: Boolean, vararg skills: String) = EpisodeEntity(
        id = id,
        goalText = goal,
        goalEmbedding = "[]",
        reflexionSummary = "",
        skillsUsed = skills.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]"),
        success = success,
        durationMs = 100,
        createdAt = id.toLong(),
    )

    private fun containsHan(value: String): Boolean = value.any { it.code in 0x3400..0x9FFF }
}
