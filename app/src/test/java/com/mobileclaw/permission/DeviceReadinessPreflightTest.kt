package com.mobileclaw.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReadinessPreflightTest {
    private fun readiness(level: ReadinessLevel) = DeviceReadiness(DeviceCapability.PHONE_CONTROL, level, emptyList())

    @Test fun `blocked readiness does not start execution`() {
        var started = false
        assertFalse(runIfDeviceReady(readiness(ReadinessLevel.BLOCKED)) { started = true })
        assertFalse(started)
    }

    @Test fun `degraded readiness starts execution`() {
        var started = false
        assertTrue(runIfDeviceReady(readiness(ReadinessLevel.DEGRADED)) { started = true })
        assertTrue(started)
    }

    @Test fun `ready readiness starts execution`() {
        var started = false
        assertTrue(runIfDeviceReady(readiness(ReadinessLevel.READY)) { started = true })
        assertTrue(started)
    }
}
