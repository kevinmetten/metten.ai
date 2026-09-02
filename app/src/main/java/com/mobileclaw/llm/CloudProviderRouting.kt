package com.mobileclaw.llm

import com.mobileclaw.config.AgentConfig
import com.mobileclaw.config.CloudProviderPreference
import com.mobileclaw.config.capabilityApiKey
import com.mobileclaw.config.capabilityEndpoint

enum class EffectiveCloudProvider { CHATGPT_ACCOUNT, API_GATEWAY }

data class ProviderReadiness(
    val localReady: Boolean,
    val apiGatewayReady: Boolean,
    val chatGptReady: Boolean,
    val effectiveCloudProvider: EffectiveCloudProvider?,
) {
    val overallReady: Boolean get() = localReady || effectiveCloudProvider != null
}

object CloudProviderResolver {
    fun hasEmbeddingCapability(snapshot: com.mobileclaw.config.ConfigSnapshot): Boolean {
        val gateway = snapshot.activeGateway ?: return false
        return gateway.capabilityEndpoint("embedding").isNotBlank() && gateway.capabilityApiKey("embedding").isNotBlank()
    }
    fun localRuntimeReady(enabled: Boolean, nativeOnly: Boolean, installed: Boolean): Boolean =
        (enabled || nativeOnly) && installed

    fun supportsCloudMultimodal(
        provider: EffectiveCloudProvider?,
        chatGptModel: ChatGptModel?,
        apiGatewaySupportsMultimodal: Boolean,
    ): Boolean = when (provider) {
        EffectiveCloudProvider.CHATGPT_ACCOUNT -> chatGptModel?.inputModalities?.any { it.equals("image", true) } ?: true
        EffectiveCloudProvider.API_GATEWAY -> apiGatewaySupportsMultimodal
        null -> false
    }

    fun resolve(
        preference: CloudProviderPreference,
        chatGptReady: Boolean,
        apiGatewayReady: Boolean,
        gatewayOverride: String? = null,
    ): EffectiveCloudProvider? {
        if (!gatewayOverride.isNullOrBlank()) return EffectiveCloudProvider.API_GATEWAY
        return when (preference) {
            CloudProviderPreference.CHATGPT_ACCOUNT -> EffectiveCloudProvider.CHATGPT_ACCOUNT.takeIf { chatGptReady }
            CloudProviderPreference.API_GATEWAY -> EffectiveCloudProvider.API_GATEWAY.takeIf { apiGatewayReady }
            CloudProviderPreference.AUTO -> when {
                chatGptReady -> EffectiveCloudProvider.CHATGPT_ACCOUNT
                apiGatewayReady -> EffectiveCloudProvider.API_GATEWAY
                else -> null
            }
        }
    }

    fun readiness(preference: CloudProviderPreference, localReady: Boolean, chatGptReady: Boolean, apiGatewayReady: Boolean) =
        ProviderReadiness(localReady, apiGatewayReady, chatGptReady, resolve(preference, chatGptReady, apiGatewayReady))
}

class CloudLlmRouter internal constructor(
    private val snapshotProvider: () -> com.mobileclaw.config.ConfigSnapshot,
    private val apiGateway: LlmGateway,
    private val chatGptGateway: LlmGateway,
    private val chatGptReady: () -> Boolean,
) : LlmGateway {
    constructor(config: AgentConfig, apiGateway: LlmGateway, chatGptGateway: LlmGateway, chatGptReady: () -> Boolean) :
        this(config::snapshot, apiGateway, chatGptGateway, chatGptReady)

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val snapshot = snapshotProvider()
        val apiReady = snapshot.chatEndpoint.isNotBlank() && snapshot.chatApiKey.isNotBlank()
        return when (CloudProviderResolver.resolve(snapshot.cloudProviderPreference, chatGptReady(), apiReady, request.callOptions.gatewayId)) {
            EffectiveCloudProvider.CHATGPT_ACCOUNT -> chatGptGateway.chat(request)
            EffectiveCloudProvider.API_GATEWAY -> apiGateway.chat(request)
            null -> throw IllegalStateException(if (snapshot.cloudProviderPreference == CloudProviderPreference.CHATGPT_ACCOUNT) "ChatGPT sign-in is required." else "Cloud provider is not configured.")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        val snapshot = snapshotProvider()
        if (!CloudProviderResolver.hasEmbeddingCapability(snapshot)) {
            throw UnsupportedOperationException("Embeddings require a configured API Gateway embedding capability.")
        }
        return apiGateway.embed(text)
    }
}
