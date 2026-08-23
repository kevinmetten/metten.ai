package com.mobileclaw.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStepNarrationTest {
    @Test
    fun `sanitizer hides generation configuration identifiers`() {
        val image = sanitizeUserFacingNarration("image_api_key is missing")
        val video = sanitizeUserFacingNarration("video_api_endpoint is unavailable")

        assertEquals("Checking whether image generation is connected", image)
        assertEquals("Checking whether video generation is connected", video)
        assertFalse(image.contains("api_key"))
        assertFalse(video.contains("endpoint"))
    }

    @Test
    fun `sanitizer preserves unrelated arbitrary Unicode narration`() {
        val narration = "لوحة المشروع جاهزة"

        assertEquals(narration, sanitizeUserFacingNarration(narration))
    }

    @Test
    fun `generic thinking prefers the planned step and otherwise uses a fallback`() {
        assertEquals(
            "Inspect the current state",
            friendlyThinkingUpdate("Thinking complete", listOf("Inspect the current state")),
        )
        assertEquals(
            "Choosing the next step from the current result",
            friendlyThinkingUpdate("Analyzing next step", emptyList()),
        )
    }

    @Test
    fun `skill narration preserves arbitrary Unicode query and tap label`() {
        val query = "أفضل مسار للمشروع"
        val label = "افتح المشروع"

        assertEquals(
            "Searching for \"$query\"",
            userFacingSkillStart("", "web_search", mapOf("query" to query)),
        )
        assertEquals(
            "Tap the area related to \"$label\"",
            friendlySkillDescription("tap", mapOf("label" to label)),
        )
    }

    @Test
    fun `observation recognizes canonical English failure evidence`() {
        assertEquals(
            "The phone action did not work as expected; trying a better action",
            friendlyObservationDescription("tap", "Action failed", hasImage = false),
        )
        assertEquals(
            "This step did not work as expected; adjusting from the result",
            friendlyObservationDescription("unknown", "Error returned", hasImage = false),
        )
    }

    @Test
    fun `artifact observations preserve draft and validation semantics`() {
        val draft = """{"action":"create","saved_as_draft":"true"}"""
        val invalidPage = """{"action":"validate","runtime_issues":["missing state"]}"""

        assertEquals(
            "The MiniAPP was saved, but the first check failed; fixing startup or runtime issues",
            friendlyObservationDescription("app_manager", draft, hasImage = false),
        )
        assertEquals(
            "The page still has unresolved issues; fixing from the check result",
            friendlyObservationDescription("ui_builder", invalidPage, hasImage = false),
        )
    }

    @Test
    fun `artifact summary passes arbitrary Unicode through unchanged`() {
        val summary = "لوحة المشروع جاهزة"
        val payload = """{"action":"inspect","summary":"$summary"}"""

        assertEquals(summary, friendlyObservationDescription("ui_builder", payload, hasImage = false))
    }

    @Test
    fun `next step hints remain actionable for artifacts and phone actions`() {
        assertTrue(nextStepHint("app_manager", """{"action":"validate"}""").orEmpty().contains("validation result"))
        assertTrue(nextStepHint("app_manager", """{"action":"open"}""").orEmpty().contains("chat preview"))
        assertTrue(nextStepHint("tap", "ok").orEmpty().contains("Read the screen again"))
    }

    @Test
    fun `plan summary removes generic list prefixes and uses a neutral separator`() {
        assertEquals(
            "Inspect the state; Apply the fix; Verify the result",
            conciseUserPlanSummary("- Inspect the state\n2. Apply the fix\n• Verify the result"),
        )
    }
}
