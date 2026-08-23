package com.mobileclaw.ui

import com.mobileclaw.agent.Role
import com.mobileclaw.agent.TaskType
import com.mobileclaw.skill.SkillAttachment
import com.mobileclaw.ui.aipage.AiPageDef
import com.mobileclaw.ui.chat.ChatMessage
import com.mobileclaw.ui.chat.LogLine
import com.mobileclaw.ui.chat.LogType
import com.mobileclaw.ui.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRouterTest {
    @Test
    fun `explicit English MiniAPP and native page intents keep distinct tool orientation`() {
        val router = router()

        val miniApp = router.resolve(
            goal = "build a mini app for expense tracking",
            effectiveGoal = "build a mini app for expense tracking",
            hasImage = false,
            hasFile = false,
            activeWorkflow = null,
        )
        val nativePage = router.resolve(
            goal = "create a native settings page",
            effectiveGoal = "create a native settings page",
            hasImage = false,
            hasFile = false,
            activeWorkflow = null,
        )

        assertEquals(TaskType.APP_BUILD, miniApp.taskType)
        assertTrue("app_manager" in miniApp.contextualIntent.aiToolHints)
        assertEquals(TaskType.APP_BUILD, nativePage.taskType)
        assertTrue("ui_builder" in nativePage.contextualIntent.aiToolHints)
    }

    @Test
    fun `continue resumes active work but a substantial new sentence does not`() {
        val workflow = ActiveWorkflow("build a dashboard", TaskType.APP_BUILD, "general")
        val router = router()

        val continuation = router.resolve("continue", "continue", false, false, workflow)
        val substantial = router.resolve(
            "Continue monitoring this service and create a new incident report",
            "Continue monitoring this service and create a new incident report",
            false,
            false,
            workflow,
        )

        assertEquals(TaskRouteSource.ACTIVE_WORKFLOW, continuation.source)
        assertTrue(substantial.source != TaskRouteSource.ACTIVE_WORKFLOW)
        assertTrue(substantial.source != TaskRouteSource.RECENT_CONTEXT)
    }

    @Test
    fun `direct arbitrary Unicode artifact title remains authoritative`() {
        val page = AiPageDef(id = "project-board", title = "لوحة المشروع")
        val route = router(pages = listOf(page)).resolve(
            "Please update لوحة المشروع",
            "Please update لوحة المشروع",
            false,
            false,
            null,
        )

        assertEquals(TaskType.APP_BUILD, route.taskType)
        assertEquals(page, route.contextualIntent.aiPage)
    }

    @Test
    fun `unknown language falls through without rejection`() {
        val goal = "اشرح لي النتيجة الحالية"
        val route = router().resolve(goal, goal, false, false, null)

        assertEquals(goal, route.goalForExecution)
        assertEquals(goal, route.contextualIntent.classificationGoal)
    }

    @Test
    fun `structural builder log drives recent continuation independent of prose`() {
        val message = ChatMessage(
            role = MessageRole.AGENT,
            text = "اكتملت الخطوة الأولى",
            logLines = listOf(LogLine(type = LogType.ACTION, text = "done", skillId = "ui_builder")),
        )
        val route = router(messages = listOf(message)).resolve("keep going", "keep going", false, false, null)

        assertEquals(TaskType.APP_BUILD, route.taskType)
        assertEquals(TaskRouteSource.RECENT_CONTEXT, route.source)
    }

    @Test
    fun `recent file attachment remains available to an English follow up`() {
        val file = SkillAttachment.FileData("/tmp/report.pdf", "report.pdf", "application/pdf", 42)
        val route = router(
            messages = listOf(ChatMessage(MessageRole.AGENT, "Finished", attachments = listOf(file))),
        ).resolve("edit this file", "edit this file", false, false, null)

        assertEquals(TaskType.FILE_CREATE, route.taskType)
        assertEquals(file, route.contextualIntent.fileAttachment)
    }

    @Test
    fun `short artifact tokens do not match inside unrelated words`() {
        val route = router().resolve("happy with the supply and build quality", "happy with the supply and build quality", false, false, null)

        assertEquals(TaskType.GENERAL, route.taskType)
        assertNull(route.contextualIntent.miniApp)
        assertNull(route.contextualIntent.aiPage)
    }

    private fun router(
        pages: List<AiPageDef> = emptyList(),
        messages: List<ChatMessage> = emptyList(),
    ) = TaskRouter(
        aiPagesProvider = { pages },
        miniAppsProvider = { emptyList() },
        messagesProvider = { messages },
        currentRoleProvider = { Role.DEFAULT },
        workspaceContextProvider = { null },
    )
}
