package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicPhoneLaunchRoutingTest {
    @Test fun `phone launch starts directly with navigate app name`() {
        val call = DeterministicPhoneLaunchRouting.next(TaskType.PHONE_CONTROL, "Open Spotify", emptyList())!!
        assertEquals("navigate", call.skillId)
        assertEquals("Spotify", call.params["app_name"])
        assertFalse(call.params.containsKey("package_name"))
        assertTrue(call.finalAfterSuccess)
    }

    @Test fun `multi action launch continues after success`() {
        val call = DeterministicPhoneLaunchRouting.next(
            TaskType.PHONE_CONTROL,
            "Open Spotify and search for Daft Punk",
            emptyList(),
        )!!
        assertEquals("Spotify", call.params["app_name"])
        assertFalse(call.finalAfterSuccess)
        val completedLaunch = AgentStep(0, "", null, "navigate", call.params, "verified")
        assertNull(DeterministicPhoneLaunchRouting.next(TaskType.PHONE_CONTROL, "Open Spotify and search for Daft Punk", listOf(completedLaunch)))
    }

    @Test fun `non phone work does not route deterministic launch`() {
        assertNull(DeterministicPhoneLaunchRouting.next(TaskType.GENERAL, "Open Spotify", emptyList()))
    }
}
