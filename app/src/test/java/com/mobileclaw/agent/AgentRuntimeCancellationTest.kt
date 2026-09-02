package com.mobileclaw.agent

import com.mobileclaw.llm.ChatRequest
import com.mobileclaw.llm.ChatResponse
import com.mobileclaw.llm.LlmGateway
import com.mobileclaw.llm.ToolCall
import com.mobileclaw.skill.Skill
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillRegistry
import com.mobileclaw.skill.SkillResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeCancellationTest {
    @Test fun `skill cancellation propagates and no second action begins`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val registry = SkillRegistry().apply { register(object : Skill {
            override val meta = SkillMeta("fake", "Fake", "Fake")
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                calls++
                started.complete(Unit)
                awaitCancellation()
            }
        }) }
        val runtime = AgentRuntime(toolGateway(), registry)
        val run = async { runtime.run("operate", TaskType.PHONE_CONTROL) }
        started.await()
        run.cancel()
        assertThrowsCancellation { run.await() }
        assertEquals(1, calls)
    }

    @Test fun `llm cancellation propagates without starting a tool`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var toolCalls = 0
        val gateway = object : LlmGateway {
            override suspend fun chat(request: ChatRequest): ChatResponse {
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun embed(text: String) = FloatArray(0)
        }
        val registry = SkillRegistry().apply { register(countingSkill { toolCalls++ }) }
        val run = async { AgentRuntime(gateway, registry).run("operate", TaskType.PHONE_CONTROL) }
        started.await()
        run.cancel()
        assertThrowsCancellation { run.await() }
        assertEquals(0, toolCalls)
    }

    @Test fun `cancelled context checkpoint prevents late follow-up action`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val registry = SkillRegistry().apply { register(object : Skill {
            override val meta = SkillMeta("fake", "Fake", "Fake")
            override suspend fun execute(params: Map<String, Any>): SkillResult {
                calls++
                started.complete(Unit)
                return try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    SkillResult(true, "late callback result")
                }
            }
        }) }
        val run = async { AgentRuntime(toolGateway(), registry).run("operate", TaskType.PHONE_CONTROL) }
        started.await()
        run.cancel()
        assertThrowsCancellation { run.await() }
        assertEquals(1, calls)
    }

    private fun toolGateway() = object : LlmGateway {
        override suspend fun chat(request: ChatRequest) = ChatResponse(
            content = "act", toolCall = ToolCall("call", "fake", emptyMap()),
        )
        override suspend fun embed(text: String) = FloatArray(0)
    }

    private fun countingSkill(onCall: () -> Unit) = object : Skill {
        override val meta = SkillMeta("fake", "Fake", "Fake")
        override suspend fun execute(params: Map<String, Any>): SkillResult {
            onCall()
            return SkillResult(true, "ok")
        }
    }

    private suspend fun assertThrowsCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
