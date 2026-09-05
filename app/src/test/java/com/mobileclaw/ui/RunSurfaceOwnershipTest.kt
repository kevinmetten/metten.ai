package com.mobileclaw.ui

import org.junit.Assert.*
import org.junit.Test

class RunSurfaceOwnershipTest {
    @Test fun `general cleanup cannot release unrelated phone Aurora owner`() {
        val ownership = RunSurfaceOwnership()
        ownership.claimPhone("phone-session", "phone-task")
        ownership.claimAgent("general-session", "general-task")
        assertTrue(ownership.releaseAgent("general-session", "general-task"))
        assertFalse(ownership.releasePhone("phone-session", "general-task"))
        assertTrue(ownership.releasePhone("phone-session", "phone-task"))
    }

    @Test fun `stale task cannot release newer ordinary overlay owner`() {
        val ownership = RunSurfaceOwnership()
        ownership.claimAgent("session", "old")
        ownership.claimAgent("session", "new")
        assertFalse(ownership.releaseAgent("session", "old"))
        assertTrue(ownership.releaseAgent("session", "new"))
    }
}
