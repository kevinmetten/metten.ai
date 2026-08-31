package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRouterTest {
    private val router = ChannelRouter()

    @Test
    fun `memory requests route to memory while ordinary chat remains chat`() {
        assertEquals(ChannelType.MEMORY, decide("Remember this preference").primary)
        assertEquals(ChannelType.MEMORY, decide("Use this as the default").primary)
        assertEquals(ChannelType.CHAT, decide("How are you today?").primary)
    }

    @Test
    fun `self evolution requires an actual change to the agent`() {
        assertEquals(ChannelType.SELF_EVOLUTION, decide("Improve yourself so you verify results").primary)
        assertEquals(ChannelType.SELF_EVOLUTION, decide("Update your skills for code review").primary)
        assertEquals(ChannelType.CHAT, decide("Which tool is best for code review?").primary)
        assertEquals(ChannelType.CHAT, decide("Explain what a skill is").primary)
    }

    @Test
    fun `task types retain their stable primary channels`() {
        val expected = mapOf(
            TaskType.PHONE_CONTROL to ChannelType.PHONE,
            TaskType.WEB_RESEARCH to ChannelType.WEB,
            TaskType.FILE_CREATE to ChannelType.ARTIFACT,
            TaskType.APP_BUILD to ChannelType.ARTIFACT,
            TaskType.IMAGE_GENERATION to ChannelType.MEDIA,
            TaskType.VPN_CONTROL to ChannelType.VPN,
            TaskType.SKILL_MANAGEMENT to ChannelType.SKILL,
            TaskType.CODE_EXECUTION to ChannelType.CODE,
        )
        expected.forEach { (taskType, channel) ->
            assertEquals(channel, router.decide(taskType, goal = "ordinary request").primary)
        }
    }

    @Test
    fun `AI primary and supporting overrides remain authoritative`() {
        val decision = router.decide(
            taskType = TaskType.PHONE_CONTROL,
            goal = "Open Gmail",
            aiPrimary = ChannelType.INFO,
            aiSupporting = listOf(ChannelType.WEB),
            aiToolHints = listOf("custom_tool"),
        )

        assertEquals(ChannelType.INFO, decision.primary)
        assertTrue(ChannelType.WEB in decision.supporting)
        assertTrue(ChannelType.MEMORY in decision.supporting)
        assertTrue("custom_tool" in decision.toolHints)
    }

    @Test
    fun `supporting channels and summary remain deterministic English`() {
        val phone = router.decide(TaskType.PHONE_CONTROL, goal = "Open Gmail",)
        assertTrue(ChannelType.MEMORY in phone.supporting)
        assertTrue(ChannelType.PLAN in phone.supporting)
        assertTrue(phone.userVisibleSummary.startsWith("Primary channel: Phone control"))
        assertTrue(phone.userVisibleSummary.contains("supporting channels: Memory, Planning"))
        assertFalse(phone.userVisibleSummary.any { it.code in 0x4E00..0x9FFF })
    }

    private fun decide(goal: String): ChannelDecision =
        router.decide(TaskType.GENERAL, goal = goal)
}
