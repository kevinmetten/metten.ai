package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePackageNamingTest {
    @Test
    fun `ordinary names retain compatible separators`() {
        assertEquals("legal_assistant", packageName("Legal Assistant"))
        assertEquals("code---helper", packageName("  Code---Helper  "))
        assertEquals("role", packageName("  ?!.*  "))
    }

    @Test
    fun `Unicode letters are preserved consistently across scripts`() {
        assertEquals("résumé_helper", packageName("Résumé Helper"))
        assertEquals("مترجم", packageName("مترجم"))
        assertEquals("помощник", packageName("Помощник"))
        assertEquals("アシスタント", packageName("アシスタント"))
    }

    @Test
    fun `mixed scripts and decimal digits remain meaningful`() {
        assertEquals("research_مترجم_2026", packageName("Research مترجم 2026"))
        assertEquals("role_٢٠٢٦", packageName("Role ٢٠٢٦"))
    }

    @Test
    fun `unsafe path and extension punctuation cannot survive`() {
        val inputs = listOf(
            "../../My Role",
            "My/Role",
            "My\\Role",
            "My:Role?",
            "<Role>|Test",
            "My.Role.mobileclaw-role",
        )

        inputs.map(::packageName).forEach { sanitized ->
            assertTrue(sanitized.isNotBlank())
            assertFalse(sanitized.contains('/'))
            assertFalse(sanitized.contains('\\'))
            assertFalse(sanitized.contains(".."))
            assertFalse(sanitized.contains('.'))
            assertFalse(sanitized.any { it in listOf(':', '*', '?', '"', '<', '>', '|') })
        }
        assertEquals("my_role", packageName("My/Role"))
        assertEquals("my_role_mobileclaw-role", packageName("My.Role.mobileclaw-role"))
    }

    @Test
    fun `role ids retain their ASCII machine identifier policy`() {
        assertEquals("my_new_role", RolePackageNaming.sanitizeRoleId("My New Role"))
        assertEquals("", RolePackageNaming.sanitizeRoleId("مترجم"))
        assertEquals("role_2026", RolePackageNaming.sanitizeRoleId("Role 2026"))
    }

    private fun packageName(raw: String): String = RolePackageNaming.sanitizePackageName(raw)
}
