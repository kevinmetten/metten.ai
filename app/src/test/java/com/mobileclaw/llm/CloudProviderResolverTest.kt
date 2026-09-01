package com.mobileclaw.llm

import com.mobileclaw.config.CloudProviderPreference
import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.GatewayCapabilityConfig
import com.mobileclaw.config.GatewayConfig
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CloudProviderResolverTest {
    @Test fun `auto prefers a usable ChatGPT session`() {
        assertEquals(EffectiveCloudProvider.CHATGPT_ACCOUNT, CloudProviderResolver.resolve(CloudProviderPreference.AUTO, true, false))
        assertEquals(EffectiveCloudProvider.CHATGPT_ACCOUNT, CloudProviderResolver.resolve(CloudProviderPreference.AUTO, true, true))
    }

    @Test fun `auto falls back to API gateway`() =
        assertEquals(EffectiveCloudProvider.API_GATEWAY, CloudProviderResolver.resolve(CloudProviderPreference.AUTO, false, true))

    @Test fun `explicit unavailable providers do not silently fall back`() {
        assertNull(CloudProviderResolver.resolve(CloudProviderPreference.CHATGPT_ACCOUNT, false, true))
        assertNull(CloudProviderResolver.resolve(CloudProviderPreference.API_GATEWAY, true, false))
    }

    @Test fun `gateway override always selects API gateway`() =
        assertEquals(EffectiveCloudProvider.API_GATEWAY, CloudProviderResolver.resolve(CloudProviderPreference.AUTO, true, false, "role-gateway"))

    @Test fun `model override does not participate in provider identity`() {
        val withoutModel = CloudProviderResolver.resolve(CloudProviderPreference.AUTO, true, true)
        // The resolver intentionally has no model argument: model selection occurs after provider identity.
        assertEquals(EffectiveCloudProvider.CHATGPT_ACCOUNT, withoutModel)
    }

    @Test fun `local runtime independently makes application ready`() {
        val readiness = CloudProviderResolver.readiness(CloudProviderPreference.AUTO, localReady = true, chatGptReady = false, apiGatewayReady = false)
        assertTrue(readiness.overallReady)
        assertNull(readiness.effectiveCloudProvider)
    }

    @Test fun `enabled local runtime requires an installed model`() {
        assertFalse(CloudProviderResolver.localRuntimeReady(enabled = true, nativeOnly = false, installed = false))
        assertTrue(CloudProviderResolver.localRuntimeReady(enabled = true, nativeOnly = false, installed = true))
    }

    @Test fun `ChatGPT multimodal does not inherit a text-only API gateway`() {
        assertTrue(CloudProviderResolver.supportsCloudMultimodal(
            EffectiveCloudProvider.CHATGPT_ACCOUNT,
            chatGptModel = null,
            apiGatewaySupportsMultimodal = false,
        ))
    }

    @Test fun `embedding capability may use capability-specific credentials`() {
        val gateway = GatewayConfig(name = "Embedding", endpoint = "", apiKey = "", model = "chat", capabilities = listOf(
            GatewayCapabilityConfig("embedding", "embed", endpoint = "https://embed.example/v1", apiKey = "embedding-key"),
        ))
        assertTrue(CloudProviderResolver.hasEmbeddingCapability(ConfigSnapshot(gateways = listOf(gateway), activeGatewayId = gateway.id)))
    }

    @Test fun `restored session resolves first visible cached model without Settings`() {
        assertEquals("catalog-default", ChatGptModelResolver.resolve(null, "", listOf(ChatGptModel("catalog-default", visibility = "list"))))
    }

    @Test fun `router delegates explicit gateway override and auto ChatGPT correctly`() = runBlocking {
        class FakeGateway(private val label: String) : LlmGateway {
            var calls = 0
            override suspend fun chat(request: ChatRequest): ChatResponse { calls++; return ChatResponse(label) }
            override suspend fun embed(text: String) = floatArrayOf()
        }
        val api = FakeGateway("api")
        val chatGpt = FakeGateway("chatgpt")
        val snapshot = ConfigSnapshot(cloudProviderPreference = CloudProviderPreference.AUTO)
        val router = CloudLlmRouter({ snapshot }, api, chatGpt) { true }
        assertEquals("chatgpt", router.chat(ChatRequest(emptyList())).content)
        assertEquals("api", router.chat(ChatRequest(emptyList(), callOptions = LlmCallOptions(gatewayId = "role"))).content)
        assertEquals(1, api.calls); assertEquals(1, chatGpt.calls)
    }
}
