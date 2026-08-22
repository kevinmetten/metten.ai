package com.mobileclaw.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitUserFactExtractorTest {
    @Test
    fun `extracts explicit identity location and profession`() {
        assertFact("My name is Kevin.", "profile.name", "Kevin")
        assertFact("You can call me Kevin", "profile.name", "Kevin")
        assertFact("I'm Kevin", "profile.name", "Kevin")
        assertFact("I live in Orange, California.", "profile.location", "Orange, California")
        assertFact("I'm from Chicago", "profile.location", "Chicago")
        assertFact("I'm a lawyer", "profile.profession", "lawyer")
        assertFact("I work as a software engineer", "profile.profession", "software engineer")
    }

    @Test
    fun `extracts preferences style and explicit memory requests`() {
        assertFact("I prefer concise answers", "profile.preferences", "concise answers")
        assertFact("I like dark mode", "profile.preferences", "dark mode")
        assertFact(
            "I don't like unnecessary confirmation dialogs",
            "profile.dislikes",
            "unnecessary confirmation dialogs",
        )
        assertFact(
            "From now on, keep your answers short",
            "profile.preferred_style",
            "keep your answers short",
        )
        assertFact("Remember that I prefer Firefox.", "profile.note", "I prefer Firefox")
    }

    @Test
    fun `extracts durable rules corrections and requirements with hashed keys`() {
        val doNot = ExplicitUserFactExtractor.extract("Never switch roles automatically")
        assertPrefixFact(doNot, "rule.user_do_not.", "Never switch roles automatically")

        val stop = ExplicitUserFactExtractor.extract("Stop switching roles automatically")
        assertPrefixFact(stop, "rule.user_do_not.", "Stop switching roles automatically")

        val must = ExplicitUserFactExtractor.extract("You must ask before deleting files")
        assertPrefixFact(must, "rule.user_must.", "Always ask before deleting files")

        val correction = ExplicitUserFactExtractor.extract("You keep searching the web unnecessarily")
        assertPrefixFact(
            correction,
            "correction.user_reported_behavior.",
            "You keep searching the web unnecessarily",
        )

        val requirement = ExplicitUserFactExtractor.extract("I want you to always verify actions")
        assertPrefixFact(requirement, "preference.user_requirement.", "always verify actions")
    }

    @Test
    fun `extracts special image UI and role policies only from negative intent`() {
        assertFact(
            "Don't search the web when I ask what's in an image.",
            "tool.policy.image_understanding.no_web_search",
            "Inspect user-provided images directly. Do not search the web unless the user explicitly requests online research",
        )
        assertFact(
            "Don't use UI Builder just because I'm asking a normal question.",
            "tool.policy.general.no_unrequested_ui_build",
            "Do not use UI Builder for ordinary chat or follow-ups; create or modify pages only when the user explicitly asks",
        )
        assertFact(
            "Stop switching roles automatically",
            "agent.behavior.keep_current_role",
            "Keep the current role unless the user explicitly requests a role change",
        )

        assertFalse(
            ExplicitUserFactExtractor.extract("Use UI Builder to create a dashboard")
                .containsKey("tool.policy.general.no_unrequested_ui_build"),
        )
        assertFalse(
            ExplicitUserFactExtractor.extract("Search the web for image generation tools")
                .containsKey("tool.policy.image_understanding.no_web_search"),
        )
    }

    @Test
    fun `rejects one-time tasks temporary states and informational discussion`() {
        listOf(
            "I'm tired",
            "I'm using Gmail",
            "I'm at the airport",
            "I want you to open Gmail",
            "I want a PDF",
            "I need a weather forecast",
            "I'd like an image of a dog",
            "Open Reddit",
            "Create a settings page",
            "Use UI Builder to create a dashboard",
            "Search the web for image generation tools",
            "You always help me",
            "You often give good answers",
            "What is a lawyer?",
            "Explain what dark mode is",
        ).forEach { text ->
            assertTrue("Unexpected durable facts for: $text", ExplicitUserFactExtractor.extract(text).isEmpty())
        }
    }

    @Test
    fun `preserves internal punctuation and trims sentence punctuation`() {
        assertFact("My name is Anne-Marie.", "profile.name", "Anne-Marie")
        assertFact("My name is O'Connor!", "profile.name", "O'Connor")
        assertFact("I live in Orange, California.", "profile.location", "Orange, California")
    }

    private fun assertFact(text: String, key: String, value: String) {
        assertEquals(value, ExplicitUserFactExtractor.extract(text)[key])
    }

    private fun assertPrefixFact(facts: Map<String, String>, prefix: String, value: String) {
        val matches = facts.filterKeys { it.startsWith(prefix) }
        assertEquals(1, matches.size)
        assertEquals(value, matches.values.single())
    }
}
