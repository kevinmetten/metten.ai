package com.mobileclaw.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLanguageTest {
    @Test
    fun `system instruction defaults to English without inferring from input language`() {
        val instruction = responseLanguageSystemInstruction()

        assertTrue(instruction.contains("English is the app's default response language"))
        assertTrue(instruction.contains("Do not infer another response language merely from the language used in the user's input"))
        assertFalse(instruction.contains("regardless of whether the user typed"))
    }

    @Test
    fun `system instruction honors explicit requested output languages`() {
        val instruction = responseLanguageSystemInstruction()

        assertTrue(instruction.contains("explicitly requests output or translation in another language"))
        assertTrue(instruction.contains("honor that requested output language"))
        assertTrue(instruction.contains("do not add an unwanted English explanation"))
        assertTrue(instruction.contains("code, file paths, JSON keys, schema keys, tool names"))
    }

    @Test
    fun `short instruction expresses the same default and exception`() {
        val instruction = responseLanguageShortInstruction()

        assertTrue(instruction.contains("Default user-visible output to English"))
        assertTrue(instruction.contains("unless the user explicitly requests another output language"))
    }
}
