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
    fun appBuildKeepsStructurallyRelevantToolLogs() {
        val messages = fillerMessages() + ChatMessage(
            role = MessageRole.AGENT,
            text = "تم التنفيذ بنجاح",
            logLines = listOf(LogLine(type = LogType.ACTION, text = "done", skillId = "ui_builder")),
        )

        val context = composer(messages).buildPriorContext("continue", TaskType.APP_BUILD)

        assertTrue(context.contains("تم التنفيذ بنجاح"))
    }

    @Test
    fun appBuildKeepsHtmlAndFileAttachmentsWithoutKeywordText() {
        val messages = fillerMessages() + listOf(
            ChatMessage(
                role = MessageRole.AGENT,
                text = "المرفق الأول",
                attachments = listOf(SkillAttachment.HtmlData("/tmp/a.html", "artifact")),
            ),
            ChatMessage(
                role = MessageRole.AGENT,
                text = "المرفق الثاني",
                attachments = listOf(SkillAttachment.FileData("/tmp/a.txt", "a.txt", "text/plain", 4)),
            ),
        )

        val context = composer(messages).buildPriorContext("continue", TaskType.APP_BUILD)

        assertTrue(context.contains("المرفق الأول"))
        assertTrue(context.contains("المرفق الثاني"))
    }

    @Test
    fun appBuildKeepsExactArbitraryUnicodeTargetTitle() {
        val targetTitle = "لوحة المشروع"
        val messages = fillerMessages() + ChatMessage(
            role = MessageRole.USER,
            text = "Please update $targetTitle",
        )
        val intent = ContextualTaskIntent(
            classificationGoal = "continue",
            aiPage = AiPageDef(id = "target-id", title = targetTitle),
        )

        val context = composer(messages).buildPriorContext("continue", TaskType.APP_BUILD, intent)

        assertTrue(context.contains("Please update $targetTitle"))
    }

    @Test
    fun englishArtifactKeywordsUseWholeUnicodeTokensAndPhrases() {
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("update the native settings page"))
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("fix the mini app"))
        assertTrue(ChatArtifactContextSemantics.isArtifactTextRelevant("the dashboard HTML needs another section"))
        assertFalse(ChatArtifactContextSemantics.isArtifactTextRelevant("happy with the result"))
        assertFalse(ChatArtifactContextSemantics.isArtifactTextRelevant("build a guide"))
    }

    @Test
    fun appBuildFallsBackToLastSixUnknownLanguageMessages() {
        val messages = (1..7).map { index ->
            ChatMessage(role = MessageRole.USER, text = "رسالة رقم $index")
        }

        val context = composer(messages).buildPriorContext("continue", TaskType.APP_BUILD)

        assertFalse(context.contains("رسالة رقم 1"))
        (2..7).forEach { index -> assertTrue(context.contains("رسالة رقم $index")) }
    }

    private fun fillerMessages(): List<ChatMessage> = (1..7).map { index ->
        ChatMessage(role = MessageRole.USER, text = "ملاحظة $index")
    }

    private fun composer(messages: List<ChatMessage>) = ChatContextComposer(
        effectiveMessages = { messages },
        summarizeAttachments = { "" },
        buildArtifactContext = { "" },
        buildWorkspaceContext = { "" },
        buildUserMemoryContext = { _, _ -> "" },
    )
}
