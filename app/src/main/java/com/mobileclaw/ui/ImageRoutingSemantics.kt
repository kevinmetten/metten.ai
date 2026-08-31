package com.mobileclaw.ui

import com.mobileclaw.agent.ChannelType
import com.mobileclaw.agent.TaskType

/**
 * Direct image chat is an optimization reserved for an accepted semantic-router decision that
 * explicitly describes a tool-free conversation. Fallback routes stay conservative and use the
 * agent runtime, which can still answer ordinary image questions when semantic routing is offline.
 */
internal fun TaskRoute.isDirectAttachedImageChatRoute(): Boolean =
    source == TaskRouteSource.AI_ROUTER &&
        contextualIntent.aiRequiresExecution == false &&
        taskType in setOf(TaskType.CHAT, TaskType.GENERAL) &&
        contextualIntent.aiPrimaryChannel == ChannelType.CHAT &&
        contextualIntent.aiSupportingChannels.isEmpty() &&
        contextualIntent.aiToolHints.isEmpty()
