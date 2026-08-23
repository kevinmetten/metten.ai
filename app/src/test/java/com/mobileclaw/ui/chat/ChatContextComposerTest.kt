package com.mobileclaw.ui.chat

import com.mobileclaw.agent.TaskType
import com.mobileclaw.skill.SkillAttachment
import com.mobileclaw.ui.ContextualTaskIntent
import com.mobileclaw.ui.aipage.AiPageDef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextComposerTest {
    @Test
    fun `builder log is authoritative without an English keyword`() {
        val message = userMessage(
            text = "أكمل التغييرات المطلوبة",
            logLines = listOf(LogLine(type = LogType.ACTION, text = "started", skillId = "ui_builder")),
        )

        val context = composer(listOf(userMessage("older conversation"), message))
            .buildPriorContext("continue", TaskType.APP_BUILD)

        assertTrue(context.contains(message.text))
        assertFalse(context.contains("older conversation"))
    }

    @Test
    fun `html and file attachments are authoritative independent of message language`() {
        val htmlMessage = userMessage(
            "التغيير الأول",
            attachments = listOf(SkillAttachment.HtmlData("/tmp/page.html", "Preview")),
        )
        val fileMessage = userMessage(
            "التغيير الثاني",
            attachments = listOf(SkillAttachment.FileData("/tmp/data.json", "data.json", "application/json", 12)),
        )

        val context = composer(listOf(userMessage("unrelated message"), htmlMessage, fileMessage))
            .buildPriorContext("continue", TaskType.APP_BUILD)

        assertTrue(context.contains(htmlMessage.text))
        assertTrue(context.contains(fileMessage.text))
        assertFalse(context.contains("unrelated message"))
    }

    @Test
    fun `exact arbitrary Unicode target title remains authoritative`() {
        val targetTitle = "لوحة المشروع"
        val targetMessage = userMessage("Please update $targetTitle")
        val intent = ContextualTaskIntent(
            classificationGoal = "continue",
            aiPage = AiPageDef(id = "project-page", title = targetTitle),
        )

        val context = composer(listOf(userMessage("unrelated message"), targetMessage))
            .buildPriorContext("continue", TaskType.APP_BUILD, intent)

        assertTrue(context.contains(targetMessage.text))
        assertFalse(context.contains("unrelated message"))
    }

    @Test
    fun `English artifact keywords match as complete Unicode tokens and phrases`() {
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("Update the native settings page"))
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("Fix the mini app"))
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("The dashboard HTML needs another section"))
        assertFalse(ChatArtifactContextSemantics.isArtifactTextRelevant("Happy with the result"))
        assertFalse(ChatArtifactContextSemantics.isArtifactTextRelevant("Build quality is excellent"))
    }

    @Test
    fun `unknown-language APP_BUILD context falls back to the last six messages`() {
        val messages = (1..7).map { userMessage("محادثة رقم $it") }

        val context = composer(messages).buildPriorContext("تابع", TaskType.APP_BUILD)

        assertFalse(context.contains(messages.first().text))
        messages.takeLast(6).forEach { assertTrue(context.contains(it.text)) }
    }

    private fun composer(messages: List<ChatMessage>) = ChatContextComposer(
        effectiveMessages = { messages },
        summarizeAttachments = { attachments -> "${attachments.size} attachment(s)" },
        buildArtifactContext = { "" },
        buildWorkspaceContext = { "" },
        buildUserMemoryContext = { _, _ -> "" },
    )

    private fun userMessage(
        text: String,
        logLines: List<LogLine> = emptyList(),
        attachments: List<SkillAttachment> = emptyList(),
    ) = ChatMessage(
        role = MessageRole.USER,
        text = text,
        logLines = logLines,
        attachments = attachments,
    )
}
