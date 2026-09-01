package com.mobileclaw.llm

import com.mobileclaw.config.CloudProviderPreference
import org.junit.Assert.*
import org.junit.Test

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
}
