package com.mobileclaw.config

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseLanguageTest {
    @Test
    fun `normalization always resolves to English`() {
        listOf("en", "zh", "auto", "fr", "", "EN").forEach { language ->
            assertEquals("en", normalizedResponseLanguage(language))
            assertEquals("English", responseLanguageName(language))
        }
    }

    @Test
    fun `system instruction requires English while preserving structured content`() {
        val instruction = responseLanguageSystemInstruction("zh")

        assertTrue(instruction.contains("MUST write all user-visible assistant text in English"))
        assertTrue(instruction.contains("code, file paths, JSON keys, tool names"))
        assertFalse(instruction.contains("Simplified Chinese"))
    }

    @Test
    fun `configuration snapshot defaults to English`() {
        assertEquals("en", ConfigSnapshot().language)
    }

    @Test
    fun `legacy serialized Chinese value remains readable but resolves to English`() {
        val restored = Gson().fromJson(
            """{"language":"zh","darkTheme":true}""",
            ConfigSnapshot::class.java,
        )

        assertEquals("zh", restored.language)
        assertTrue(restored.darkTheme)
        assertEquals("en", normalizedResponseLanguage(restored.language))
    }
}
