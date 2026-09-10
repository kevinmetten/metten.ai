package com.mobileclaw.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ProvisionedResourceFactoryTest {
    @Test fun `returned model directory is used before pipeline construction`() {
        val calls = mutableListOf<String>()
        val value = ProvisionedResourceFactory(
            ensureModels = { calls += "ensure"; "/validated/models" },
            construct = { directory -> calls += "construct:$directory"; directory.length },
        ).create()
        assertEquals(listOf("ensure", "construct:/validated/models"), calls)
        assertEquals("/validated/models".length, value)
    }
}
