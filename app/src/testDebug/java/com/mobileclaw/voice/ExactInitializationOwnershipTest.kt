package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class ExactInitializationOwnershipTest {
    @Test fun `one initialization installs exactly one resource`() {
        val state = ExactInitializationOwnership<String>()
        assertEquals(InitializationAdmission.START, state.begin())
        assertEquals(InitializationAdmission.ALREADY_IN_FLIGHT, state.begin())
        assertTrue(state.accept("pipeline"))
        assertEquals("pipeline", state.current())
        assertEquals(InitializationAdmission.ALREADY_READY, state.begin())
    }

    @Test fun `resource arriving after release is rejected for immediate close`() {
        val state = ExactInitializationOwnership<String>()
        assertEquals(InitializationAdmission.START, state.begin())
        assertNull(state.release())
        assertFalse(state.accept("late pipeline"))
        assertNull(state.current())
        assertEquals(InitializationAdmission.RELEASED, state.begin())
    }

    @Test fun `release after construction returns exact resource once`() {
        val state = ExactInitializationOwnership<String>(); state.begin(); assertTrue(state.accept("pipeline"))
        assertEquals("pipeline", state.release())
        assertNull(state.release())
    }
}
