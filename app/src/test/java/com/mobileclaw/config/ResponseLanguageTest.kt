package com.mobileclaw.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLanguageTest {
    @Test
    fun `system instruction requires English while preserving structured content`() {
        val instruction = responseLanguageSystemInstruction()

        assertTrue(instruction.contains("MUST write all user-visible assistant text in English"))
        assertTrue(instruction.contains("code, file paths, JSON keys, tool names"))
        assertFalse(instruction.contains("Simplified Chinese"))
    }
}
