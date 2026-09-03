package com.mobileclaw.llm

import com.mobileclaw.config.ConfigSnapshot
import com.mobileclaw.config.GatewayCapabilityConfig

/** Provider-aware policy shared by the chat model picker and its focused tests. */
object ChatModelPolicy {
    suspend fun discover(
        localNativeOnly: Boolean,
        provider: EffectiveCloudProvider?,
        chatGptModels: suspend () -> List<String>,
        apiGatewayModels: suspend () -> List<String>,
        localModels: List<String>,
    ): List<String> {
        val cloudModels = if (localNativeOnly) emptyList() else when (provider) {
            EffectiveCloudProvider.CHATGPT_ACCOUNT -> chatGptModels()
            EffectiveCloudProvider.API_GATEWAY -> apiGatewayModels()
            null -> emptyList()
        }
        return (cloudModels + localModels).distinct()
    }

    fun select(snapshot: ConfigSnapshot, model: String, provider: EffectiveCloudProvider?): ConfigSnapshot {
        if (model.startsWith("local:")) {
            return snapshot.copy(localModelEnabled = true, localModelId = model.removePrefix("local:"))
        }
        return when (provider) {
            EffectiveCloudProvider.CHATGPT_ACCOUNT -> snapshot.copy(
                chatGptModel = model,
                localModelEnabled = false,
                localNativeOnly = false,
            )
            EffectiveCloudProvider.API_GATEWAY -> snapshot.copy(
                gateways = snapshot.gateways.map {
                    if (it.id == snapshot.activeGatewayId || (snapshot.activeGatewayId == null && it == snapshot.gateways.firstOrNull())) {
                        val capabilities = it.capabilities.filterNot { capability -> capability.type.equals("chat", true) }
                        it.copy(model = model, capabilities = capabilities + GatewayCapabilityConfig(type = "chat", model = model))
                    } else it
                },
                localModelEnabled = false,
                localNativeOnly = false,
            )
            null -> snapshot
        }
    }
}
