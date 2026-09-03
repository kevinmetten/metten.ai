package com.mobileclaw.llm

import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.GatewayCapabilityConfig
import com.mobileclaw.config.GatewayConfig
import com.mobileclaw.config.CloudProviderPreference
import com.mobileclaw.config.TargetedConfigMutation
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
        val configured = snapshot.copy(cloudProviderPreference = CloudProviderPreference.CHATGPT_ACCOUNT)
        val updated = TargetedConfigMutation.ChatGptModel("sol").applyTo(configured)
        assertEquals("sol", updated.chatGptModel)
        assertEquals("gateway-old", updated.chatModel)
        assertEquals(CloudProviderPreference.CHATGPT_ACCOUNT, updated.cloudProviderPreference)
        assertFalse(updated.localModelEnabled)
    }

    @Test fun `provider selection preserves Terra`() {
        val automatic = snapshot.copy(cloudProviderPreference = CloudProviderPreference.AUTO, chatGptModel = "terra")
        val updated = TargetedConfigMutation.CloudProvider(CloudProviderPreference.CHATGPT_ACCOUNT).applyTo(automatic)
        assertEquals(CloudProviderPreference.CHATGPT_ACCOUNT, updated.cloudProviderPreference)
        assertEquals("terra", updated.chatGptModel)
    }

    @Test fun `separate provider and model mutations compose without stale snapshot replacement`() {
        val initial = snapshot.copy(cloudProviderPreference = CloudProviderPreference.AUTO, chatGptModel = "sol")
        val mutations = listOf(
            TargetedConfigMutation.CloudProvider(CloudProviderPreference.CHATGPT_ACCOUNT),
            TargetedConfigMutation.ChatGptModel("terra"),
        )
        val updated = mutations.fold(initial) { current, mutation -> mutation.applyTo(current) }
        assertEquals(CloudProviderPreference.CHATGPT_ACCOUNT, updated.cloudProviderPreference)
        assertEquals("terra", updated.chatGptModel)
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
