package com.mobileclaw.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionMetadataNodeDetectorTest {
    @Test
    fun `recognizes every legacy external metadata prefix`() {
        val legacyNames = listOf(
            legacy(0x7F51, 0x5740) + ": example.com",
            legacy(0x5269, 0x4F59, 0x6D41, 0x91CF) + ": 12 GB",
            legacy(0x8FC7, 0x671F, 0x65F6, 0x95F4) + ": 2026-12-31",
            legacy(0x5B98, 0x7F51) + ": example.com",
            legacy(0x8BA2, 0x9605) + ": premium",
            legacy(0x56DE, 0x5BB6, 0x9875) + ": example.com",
        )

        legacyNames.forEach { name ->
            assertTrue("Expected legacy metadata name to be filtered", SubscriptionMetadataNodeDetector.isMetadataNode(name))
        }
    }

    @Test
    fun `recognizes conservative English metadata labels`() {
        val metadataNames = listOf(
            "Remaining traffic: 12 GB",
            "Remaining data: 8 GB",
            "Traffic remaining: 6 GB",
            "Data remaining: 4 GB",
            "Expiration: 2026-12-31",
            "Expires: 2026-12-31",
            "Expiry: 2026-12-31",
            "Official website: example.com",
            "Provider website: example.com",
            "Subscription info",
            "  remaining TRAFFIC : 2 GB  ",
        )

        metadataNames.forEach { name ->
            assertTrue("Expected metadata name to be filtered: $name", SubscriptionMetadataNodeDetector.isMetadataNode(name))
        }
    }

    @Test
    fun `does not filter legitimate proxy names containing generic words`() {
        val proxyNames = listOf(
            "US Subscription Route",
            "Website Accelerator",
            "Home Page US",
            "Official Singapore",
            "Data Center Tokyo",
            "Expiry Test Node",
            "Subscription info relay",
            "Official website accelerator",
            "Remaining data center Tokyo",
            "Expires Soon US",
            "",
        )

        proxyNames.forEach { name ->
            assertFalse("Expected real proxy name to remain accepted: $name", SubscriptionMetadataNodeDetector.isMetadataNode(name))
        }
    }

    @Test
    fun `Clash parser excludes metadata pseudo-node and retains real proxies`() {
        val yaml = """
            proxies:
              - name: "Remaining traffic: 12 GB"
                type: http
                server: metadata.example.com
                port: 8080
              - name: "US Subscription Route"
                type: http
                server: us.example.com
                port: 8080
              - name: "Tokyo Data Center"
                type: socks5
                server: tokyo.example.com
                port: 1080
        """.trimIndent()

        val proxies = ClashParser.parse(yaml)

        assertEquals(listOf("US Subscription Route", "Tokyo Data Center"), proxies.map { it.name })
    }

    private fun legacy(vararg codePoints: Int): String =
        codePoints.joinToString("") { String(Character.toChars(it)) }
}
