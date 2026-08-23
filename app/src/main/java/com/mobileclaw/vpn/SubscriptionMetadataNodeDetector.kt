package com.mobileclaw.vpn

/** Classifies informational pseudo-nodes emitted by external subscription providers. */
internal object SubscriptionMetadataNodeDetector {
    private val legacyPrefixes = listOf(
        legacy(0x7F51, 0x5740),
        legacy(0x5269, 0x4F59, 0x6D41, 0x91CF),
        legacy(0x8FC7, 0x671F, 0x65F6, 0x95F4),
        legacy(0x5B98, 0x7F51),
        legacy(0x8BA2, 0x9605),
        legacy(0x56DE, 0x5BB6, 0x9875),
    )

    private val englishLabels = listOf(
        "remaining traffic",
        "remaining data",
        "traffic remaining",
        "data remaining",
        "expires",
        "expiration",
        "expiry",
        "official website",
        "provider website",
        "subscription info",
    )

    fun isMetadataNode(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isEmpty()) return false
        if (legacyPrefixes.any(normalized::startsWith)) return true

        val englishName = normalized.lowercase().replace(Regex("\\s+"), " ")
        return englishLabels.any { label ->
            if (!englishName.startsWith(label)) return@any false
            val remainder = englishName.removePrefix(label).trimStart()
            remainder.isEmpty() || remainder.first() in METADATA_DELIMITERS
        }
    }

    private fun legacy(vararg codePoints: Int): String =
        codePoints.joinToString("") { String(Character.toChars(it)) }

    private val METADATA_DELIMITERS = charArrayOf(':', '-', '|')
}
