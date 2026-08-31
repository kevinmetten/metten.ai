package com.mobileclaw.ui

import com.mobileclaw.agent.ChannelType
import com.mobileclaw.agent.TaskType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRoutingSemanticsTest {
    @Test
    fun `semantic image understanding uses direct chat`() {
        assertTrue(aiRoute(TaskType.GENERAL, ChannelType.CHAT).isDirectAttachedImageChatRoute())
    }

    @Test
    fun `image generation uses agent execution`() {
        assertFalse(aiRoute(TaskType.IMAGE_GENERATION, ChannelType.MEDIA).isDirectAttachedImageChatRoute())
    }

    @Test
    fun `phone action with image uses agent execution`() {
        assertFalse(aiRoute(TaskType.PHONE_CONTROL, ChannelType.PHONE).isDirectAttachedImageChatRoute())
    }

    @Test
    fun `route structure is independent of arbitrary language goal`() {
        val arabic = aiRoute(TaskType.GENERAL, ChannelType.CHAT, goal = "اشرح ما يظهر في الصورة")
        val japanese = aiRoute(TaskType.GENERAL, ChannelType.CHAT, goal = "画像に見えるものを説明して")

        assertTrue(arabic.isDirectAttachedImageChatRoute())
        assertTrue(japanese.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `classifier fallback remains conservative`() {
        val fallback = aiRoute(
            taskType = TaskType.GENERAL,
            channel = ChannelType.CHAT,
            source = TaskRouteSource.CLASSIFIER,
        )

        assertFalse(fallback.isDirectAttachedImageChatRoute())
    }

    private fun aiRoute(
        taskType: TaskType,
        channel: ChannelType,
        goal: String = "describe the image",
        source: TaskRouteSource = TaskRouteSource.AI_ROUTER,
    ) = TaskRoute(
        taskType = taskType,
        contextualIntent = ContextualTaskIntent(
            classificationGoal = goal,
            taskTypeOverride = taskType,
            aiPrimaryChannel = channel,
        ),
        goalForExecution = goal,
        source = source,
    )
}
