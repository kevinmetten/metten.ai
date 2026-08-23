package com.mobileclaw.ui.profile

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAiGenerationTest {
    @Test
    fun `personality prompt is English owned and preserves unicode facts`() {
        val unicodeFact = "interest: التصوير والسفر"
        val prompt = ProfileAiGeneration.buildPersonalitySummaryPrompt(unicodeFact, "Prefers quiet mornings")

        assertFalse(HAN.containsMatchIn(prompt))
        assertFalse(prompt.contains("str(R.string"))
        assertTrue(prompt.contains("second person"))
        assertTrue(prompt.contains(unicodeFact))
        assertTrue(prompt.contains("tentative"))
        assertTrue(prompt.contains("Do not invent biographical facts"))
    }

    @Test
    fun `quiz prompt has a strict contract and valid JSON example`() {
        val unicodeFact = "interest: التصوير والسفر"
        val prompt = ProfileAiGeneration.buildDimensionQuizPrompt(
            dimensionId = "interests",
            dimensionTitle = "Interests",
            relevantFacts = unicodeFact,
            foundationalMemory = "Enjoys learning",
        )

        assertFalse(HAN.containsMatchIn(prompt))
        assertFalse(prompt.contains("str(R.string"))
        assertTrue(prompt.contains("exactly 5"))
        assertTrue(prompt.contains("exactly 4"))
        assertTrue(prompt.contains("Return ONLY a JSON array"))
        assertTrue(prompt.contains("Interests"))
        assertTrue(prompt.contains(unicodeFact))
        val example = prompt.substringAfter("Valid JSON shape example:\n")
        JsonParser.parseString(example).asJsonArray
    }

    @Test
    fun `valid five question response is accepted`() {
        val parsed = ProfileAiGeneration.parseDimensionQuiz(validQuiz(), "interests")
        assertEquals(5, parsed.size)
        assertTrue(parsed.all { it.answers.size == 4 && it.factKey.startsWith("profile.interests.") })
    }

    @Test
    fun `malformed or invalid quiz responses are rejected`() {
        assertTrue(ProfileAiGeneration.parseDimensionQuiz("not json", "interests").isEmpty())
        assertTrue(ProfileAiGeneration.parseDimensionQuiz(validQuiz().replace("profile.interests.topic1", "profile.other.foo"), "interests").isEmpty())
        assertTrue(ProfileAiGeneration.parseDimensionQuiz(validQuiz().replace("\"D1\"", "\"D1\", \"E1\""), "interests").isEmpty())
        assertTrue(ProfileAiGeneration.parseDimensionQuiz(validQuiz().replace(", \"D1\"", ""), "interests").isEmpty())
    }

    private fun validQuiz(): String = (1..5).joinToString(prefix = "[", postfix = "]") { index ->
        """{"question":"Question $index","hint":"Hint $index","answers":["A$index","B$index","C$index","D$index"],"factKey":"profile.interests.topic$index"}"""
    }

    private companion object {
        val HAN = Regex("[\\p{IsHan}]")
    }
}
