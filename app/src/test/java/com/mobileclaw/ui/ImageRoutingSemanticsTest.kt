package com.mobileclaw.ui

import com.mobileclaw.agent.AiTaskRouteDecision
import com.mobileclaw.agent.ChannelType
import com.mobileclaw.agent.Role
import com.mobileclaw.agent.TaskType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRoutingSemanticsTest {
    private val router = TaskRouter(
        aiPagesProvider = { emptyList() }, miniAppsProvider = { emptyList() },
        messagesProvider = { emptyList() }, currentRoleProvider = { Role.DEFAULT },
        workspaceContextProvider = { null },
    )

    @Test
    fun `production route makes non-execution image understanding direct chat`() {
        val route = resolveAiRoute("Explain what is visible", TaskType.GENERAL, false, ChannelType.CHAT)

        assertTrue(route.contextualIntent.userVisibleSteps.isNotEmpty())
        assertTrue(route.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `image generation uses agent execution`() {
        val route = resolveAiRoute("Create a variation", TaskType.IMAGE_GENERATION, true, ChannelType.MEDIA)
        assertFalse(route.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `phone action with image uses agent execution`() {
        val route = resolveAiRoute("Use this image in another app", TaskType.PHONE_CONTROL, true, ChannelType.PHONE)
        assertFalse(route.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `executable general chat route remains conservative`() {
        val route = resolveAiRoute("Perform the requested operation", TaskType.GENERAL, true, ChannelType.CHAT)
        assertFalse(route.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `route structure is independent of arbitrary language goal`() {
        val arabic = resolveAiRoute("اشرح ما يظهر في الصورة", TaskType.GENERAL, false, ChannelType.CHAT)
        val japanese = resolveAiRoute("画像に見えるものを説明して", TaskType.GENERAL, false, ChannelType.CHAT)

        assertTrue(arabic.isDirectAttachedImageChatRoute())
        assertTrue(japanese.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `classifier fallback without semantic execution status remains conservative`() {
        val fallback = TaskRoute(
            taskType = TaskType.GENERAL,
            contextualIntent = ContextualTaskIntent(
                classificationGoal = "Describe the image",
                taskTypeOverride = TaskType.GENERAL,
                aiPrimaryChannel = ChannelType.CHAT,
            ),
            goalForExecution = "Describe the image",
            source = TaskRouteSource.CLASSIFIER,
        )
        assertFalse(fallback.isDirectAttachedImageChatRoute())
    }

    @Test
    fun `AI agent fallback without semantic execution status remains conservative`() {
        val fallback = router.resolveAsAgentFallback(
            goal = "Describe the image", effectiveGoal = "Describe the image",
            reason = "Semantic router unavailable",
        )
        assertFalse(fallback.isDirectAttachedImageChatRoute())
    }

    private fun resolveAiRoute(
        goal: String,
        taskType: TaskType,
        requiresExecution: Boolean,
        channel: ChannelType,
    ): TaskRoute = requireNotNull(
        router.resolveWithAiDecision(
            goal = goal,
            effectiveGoal = goal,
            hasImage = true,
            hasFile = false,
            activeWorkflow = null,
            decision = AiTaskRouteDecision(
                taskType = taskType,
                requiresExecution = requiresExecution,
                confidence = 0.95f,
                reason = "Test semantic decision",
                normalizedGoal = goal,
                targetApp = "",
                primaryChannel = channel,
                supportingChannels = emptyList(),
                toolHints = emptyList(),
                userVisibleSteps = emptyList(),
            ),
        ),
    )
}
