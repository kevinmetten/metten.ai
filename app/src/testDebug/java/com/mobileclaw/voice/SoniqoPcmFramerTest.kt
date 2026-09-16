package com.mobileclaw.voice

import org.junit.Assert.*
import org.junit.Test

class SoniqoPcmFramerTest {
    @Test fun `short reads and old 1600 cadence preserve every sample in complete VAD windows`() {
        val samples = FloatArray(512 * 100) { it.toFloat() }
        for (readSize in listOf(1, 127, 320, 512, 1600, 2048)) {
            val received = mutableListOf<Float>()
            val framer = SoniqoPcmFramer { block -> assertEquals(512, block.size); received.addAll(block.toList()) }
            samples.toList().chunked(readSize).forEach { framer.accept(it.toFloatArray()) }
            assertArrayEquals(samples, received.toFloatArray(), 0f)
        }
    }
}
