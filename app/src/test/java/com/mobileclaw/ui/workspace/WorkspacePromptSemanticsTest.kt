package com.mobileclaw.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePromptSemanticsTest {
    @Test
    fun `concise English resume prompts are recognized`() {
        assertTrue(WorkspacePromptSemantics.isResumePrompt("continue"))
        assertTrue(WorkspacePromptSemantics.isResumePrompt("keep going"))
        assertTrue(WorkspacePromptSemantics.isFollowUpPrompt("continue"))
        assertTrue(WorkspacePromptSemantics.isFollowUpPrompt("keep going"))
    }

    @Test
    fun `English artifact corrections are recognized as follow ups`() {
        assertTrue(WorkspacePromptSemantics.isFollowUpPrompt("change it"))
        assertTrue(WorkspacePromptSemantics.isFollowUpPrompt("update this page"))
        assertTrue(WorkspacePromptSemantics.isFollowUpPrompt("not this"))
    }

    @Test
    fun `substantial unrelated goals are not follow ups because of generic words`() {
        assertFalse(
            WorkspacePromptSemantics.isFollowUpPrompt(
                "Build a new inventory dashboard with this quarter's supplier data",
            ),
        )
        assertFalse(
            WorkspacePromptSemantics.isResumePrompt(
                "Continue monitoring the service and create a separate incident report",
            ),
        )
    }

    @Test
    fun `Unicode workspace content is preserved exactly`() {
        val goal = "أنشئ تقريراً عن المشروع 🚀"

        assertEquals(goal, WorkspacePromptSemantics.preserveContent(goal, 400))
    }

    @Test
    fun `Unicode letters remain meaningful goal tokens`() {
        val tokens = WorkspacePromptSemantics.goalTokens("أنشئ تقرير عن المشروع 🚀")

        assertEquals(setOf("أنشئ", "تقرير", "عن", "المشروع"), tokens)
    }
}
