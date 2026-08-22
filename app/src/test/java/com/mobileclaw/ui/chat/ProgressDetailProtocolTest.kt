package com.mobileclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressDetailProtocolTest {
    private val sender = AgentSenderMeta("role", "Agent", "role:general")

    @Test
    fun `stable fields round trip independently of display labels`() {
        val expected = mapOf(
            ProgressDetailKey.PURPOSE to "Inspect the current state",
            ProgressDetailKey.RESULT to "The state is healthy",
            ProgressDetailKey.NEXT to "Continue with validation",
            ProgressDetailKey.PLAN to "Inspect, update, validate",
            ProgressDetailKey.NOTE to "No retry was needed",
            ProgressDetailKey.NEXT_PLAN to "Publish after validation",
            ProgressDetailKey.FULL_RESULT to "raw:output:with:colons",
            ProgressDetailKey.DEBUG to "attempt=1",
        )
        val encoded = expected.map { (key, value) -> ProgressDetailProtocol.encode(key, value) }

        expected.forEach { (key, value) ->
            assertEquals(value, ProgressDetailProtocol.value(encoded, key))
        }
        assertTrue(encoded.all { ':' in it })
        assertFalse(encoded.any { it.startsWith("Purpose") || it.startsWith("Result") })
    }

    @Test
    fun `action and observation group uses purpose result and next fields`() {
        val lines = listOf(
            log(LogType.ACTION, "tool call", ProgressDetailKey.PURPOSE to "Inspect the project"),
            log(
                LogType.OBSERVATION,
                "raw observation",
                ProgressDetailKey.RESULT to "The failing file was found",
                ProgressDetailKey.NEXT to "Apply the focused fix",
            ),
        )

        val messages = buildNarrativeAgentMessages("", lines, emptyList(), sender)

        assertEquals(1, messages.size)
        assertEquals("Inspect the project\nThe failing file was found\nApply the focused fix", messages.single().text)
        assertEquals(lines, messages.single().logLines)
    }

    @Test
    fun `success error and missing optional fields render safely`() {
        val lines = listOf(
            log(LogType.SUCCESS, "Completed successfully"),
            log(LogType.ERROR, "Validation failed", ProgressDetailKey.RESULT to "A syntax error remains"),
            log(LogType.OBSERVATION, "Fallback observation"),
        )

        val messages = buildNarrativeAgentMessages("", lines, emptyList(), sender)

        assertTrue(messages.any { it.text == "Completed successfully" })
        assertTrue(messages.any { it.text == "Validation failed" })
        assertTrue(messages.any { it.text == "Fallback observation" })
    }

    @Test
    fun `malformed and unknown details are not interpreted as protocol fields`() {
        assertNull(ProgressDetailProtocol.parse("purpose without separator"))
        assertNull(ProgressDetailProtocol.parse("unknown:value"))
        assertEquals("", ProgressDetailProtocol.value(listOf("Purpose: display prose"), ProgressDetailKey.PURPOSE))
    }

    @Test
    fun `long running progress emits a checkpoint without changing action grouping`() {
        val lines = (1..10).flatMap { index ->
            listOf(
                log(LogType.ACTION, "action $index", ProgressDetailKey.PURPOSE to "Run step $index"),
                log(LogType.OBSERVATION, "observation $index", ProgressDetailKey.RESULT to "Finished step $index"),
            )
        }

        val messages = buildNarrativeAgentMessages("", lines, emptyList(), sender, isRunning = true)

        assertTrue(messages.any { it.text.contains("Completed 10 steps") })
        assertTrue(messages.flatMap { it.logLines }.any { it.type == LogType.ACTION })
        assertTrue(messages.flatMap { it.logLines }.any { it.type == LogType.OBSERVATION })
    }

    private fun log(type: LogType, text: String, vararg details: Pair<ProgressDetailKey, String>): LogLine =
        LogLine(
            type = type,
            text = text,
            details = details.map { (key, value) -> ProgressDetailProtocol.encode(key, value) },
        )
}
