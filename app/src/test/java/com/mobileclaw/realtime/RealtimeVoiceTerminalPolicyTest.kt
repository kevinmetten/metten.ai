package com.mobileclaw.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceTerminalPolicyTest {
    @Test fun `auxiliary data channel closure is nonfatal while peer failure is authoritative once`() {
        assertFalse(RealtimeVoiceTransportPolicy.isFatal(RealtimeNativeTransportEvent.EVENTS_DATACHANNEL_CLOSED))
        assertTrue(RealtimeVoiceTransportPolicy.isFatal(RealtimeNativeTransportEvent.WEBRTC_PEER_FAILED))
        assertTrue(RealtimeVoiceTransportPolicy.isFatal(RealtimeNativeTransportEvent.WEBRTC_PEER_CLOSED))
        val gate = RealtimeVoiceTerminalGate()
        assertTrue(gate.claimRemoteTerminal())
        assertFalse(gate.claimRemoteTerminal())
    }

    @Test fun `local close suppresses later native peer and data channel terminal callbacks`() {
        val gate = RealtimeVoiceTerminalGate()
        assertTrue(gate.beginLocalClose())
        assertFalse(gate.beginLocalClose())
        assertFalse(gate.claimRemoteTerminal())
    }
}
