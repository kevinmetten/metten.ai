package com.mobileclaw.llm

import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.GatewayCapabilityConfig
import com.mobileclaw.config.GatewayConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelPolicyTest {
    private val gateway = GatewayConfig(
        id = "gateway",
        name = "Gateway",
        endpoint = "https://example.test",
        apiKey = "key",
        model = "gateway-old",
        capabilities = listOf(GatewayCapabilityConfig(type = "chat", model = "gateway-old")),
    )
    private val snapshot = ConfigSnapshot(gateways = listOf(gateway), activeGatewayId = gateway.id, chatGptModel = "terra")

    @Test fun `ChatGPT discovery merges picker and local models`() = runBlocking {
        val result = ChatModelPolicy.discover(false, EffectiveCloudProvider.CHATGPT_ACCOUNT, { listOf("sol", "terra") }, { error("wrong provider") }, listOf("local:gemma"))
        assertEquals(listOf("sol", "terra", "local:gemma"), result)
    }

    @Test fun `gateway discovery retains gateway catalog`() = runBlocking {
        val result = ChatModelPolicy.discover(false, EffectiveCloudProvider.API_GATEWAY, { error("wrong provider") }, { listOf("gateway-new") }, emptyList())
        assertEquals(listOf("gateway-new"), result)
    }

    @Test fun `native only never fetches cloud models`() = runBlocking {
        val result = ChatModelPolicy.discover(true, EffectiveCloudProvider.CHATGPT_ACCOUNT, { error("cloud fetched") }, { error("cloud fetched") }, listOf("local:gemma"))
        assertEquals(listOf("local:gemma"), result)
    }

    @Test fun `ChatGPT selection changes only ChatGPT model`() {
        val updated = ChatModelPolicy.select(snapshot, "sol", EffectiveCloudProvider.CHATGPT_ACCOUNT)
        assertEquals("sol", updated.chatGptModel)
        assertEquals("gateway-old", updated.chatModel)
        assertFalse(updated.localModelEnabled)
    }

    @Test fun `gateway selection changes active gateway and preserves ChatGPT selection`() {
        val updated = ChatModelPolicy.select(snapshot, "gateway-new", EffectiveCloudProvider.API_GATEWAY)
        assertEquals("gateway-new", updated.chatModel)
        assertEquals("terra", updated.chatGptModel)
    }

    @Test fun `local selection preserves existing semantics`() {
        val updated = ChatModelPolicy.select(snapshot, "local:gemma", EffectiveCloudProvider.CHATGPT_ACCOUNT)
        assertTrue(updated.localModelEnabled)
        assertEquals("gemma", updated.localModelId)
        assertEquals("terra", updated.chatGptModel)
    }

    @Test fun `catalog refresh cannot overwrite persisted selection`() = runBlocking {
        val before = snapshot
        val models = ChatModelPolicy.discover(false, EffectiveCloudProvider.CHATGPT_ACCOUNT, { listOf("sol", "terra") }, { emptyList() }, emptyList())
        assertEquals(listOf("sol", "terra"), models)
        assertEquals("terra", before.chatGptModel)
    }
}
