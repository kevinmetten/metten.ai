package com.mobileclaw.skill

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SkillDefinitionCompatibilityTest {
    private val gson = Gson()

    @Test
    fun `legacy bilingual metadata is ignored while canonical unicode metadata survives`() {
        val json = """
            {
              "meta": {
                "id": "unicode_weather",
                "name": "天気 ☀️",
                "description": "Météo — الطقس",
                "nameZh": "obsolete name",
                "descriptionZh": "obsolete description",
                "type": "http",
                "isBuiltin": false
              },
              "httpConfig": { "url": "https://example.com/weather" }
            }
        """.trimIndent()

        val definition = gson.fromJson(json, SkillDefinition::class.java)

        assertEquals("天気 ☀️", definition.meta.name)
        assertEquals("Météo — الطقس", definition.meta.description)
        val persisted = gson.toJson(definition)
        assertFalse(persisted.contains("nameZh"))
        assertFalse(persisted.contains("descriptionZh"))
    }
}
