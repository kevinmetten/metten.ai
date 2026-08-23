package com.mobileclaw.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRecommendationSemanticsTest {
    @Test
    fun `request boilerplate is removed without consuming the topic`() {
        assertEquals(
            "search for Android automation",
            SmartRecommendationSemantics.normalizeTopic("Please help me search for Android automation"),
        )
        assertEquals(
            "look up agent frameworks",
            SmartRecommendationSemantics.normalizeTopic("Could you look up agent frameworks"),
        )
    }

    @Test
    fun `episode categories remain distinct`() {
        assertEquals(RecommendationCategory.SEARCH, SmartRecommendationSemantics.episodeCategory("Search for Android agents"))
        assertEquals(RecommendationCategory.ANALYSIS, SmartRecommendationSemantics.episodeCategory("Analyze the usage data"))
        assertEquals(RecommendationCategory.WRITING, SmartRecommendationSemantics.episodeCategory("Draft a launch announcement"))
        assertEquals(RecommendationCategory.LEARNING, SmartRecommendationSemantics.episodeCategory("Study dependency injection"))
        assertEquals(RecommendationCategory.TRANSLATION, SmartRecommendationSemantics.episodeCategory("Translate this document"))
    }

    @Test
    fun `app goals use complete canonical terms`() {
        listOf(
            "Improve the app",
            "Build a program",
            "Update this software",
            "Fix the HTML",
            "Create a native page",
        ).forEach { assertTrue(it, SmartRecommendationSemantics.isAppGoal(it)) }

        assertFalse(SmartRecommendationSemantics.isAppGoal("I am happy with the result"))
        assertFalse(SmartRecommendationSemantics.isAppGoal("Review the build quality report"))
    }

    @Test
    fun `arbitrary Unicode topics are preserved and fail open to exploration`() {
        val topic = "ابحث عن أفضل الأدوات"

        assertEquals(topic, SmartRecommendationSemantics.normalizeTopic(topic))
        assertEquals(RecommendationCategory.EXPLORE, SmartRecommendationSemantics.episodeCategory(topic))
    }

    @Test
    fun `unknown Unicode profile values do not activate unrelated categories`() {
        val value = "مهندس ومهتم بالقراءة"

        assertNull(SmartRecommendationSemantics.professionCategory(value))
        assertNull(SmartRecommendationSemantics.interestCategory(value))
        assertNull(SmartRecommendationSemantics.emotionCategory(emptyList(), mapOf("emotional_stability" to value)))
    }

    @Test
    fun `English emotion categories remain lightweight deterministic signals`() {
        assertEquals(
            EmotionRecommendationCategory.FATIGUE,
            SmartRecommendationSemantics.emotionCategory(listOf("I am exhausted"), emptyMap()),
        )
        assertEquals(
            EmotionRecommendationCategory.EMOTIONAL_SUPPORT,
            SmartRecommendationSemantics.emotionCategory(emptyList(), mapOf("emotional_stability" to "anxious")),
        )
    }
}
