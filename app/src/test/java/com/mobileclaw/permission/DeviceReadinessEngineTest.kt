package com.mobileclaw.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReadinessEngineTest {
    private val healthy = DeviceReadinessSignals(
        accessibilityEnabled = true,
        overlayEnabled = true,
        batteryOptimizationExempt = true,
        notificationGranted = true,
        systemPowerSaveMode = false,
        backgroundRestricted = false,
        romType = RomType.AOSP,
    )
    private val engine = DeviceReadinessEngine { healthy }

    private fun readiness(capability: DeviceCapability, signals: DeviceReadinessSignals = healthy) =
        engine.evaluate(capability, signals)

    @Test fun `chat ignores accessibility overlay and power conditions`() {
        val result = readiness(DeviceCapability.CHAT, healthy.copy(
            accessibilityEnabled = false, overlayEnabled = false,
            batteryOptimizationExempt = false, systemPowerSaveMode = true,
        ))
        assertEquals(ReadinessLevel.READY, result.level)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun `auth ignores accessibility and overlay`() {
        val result = readiness(DeviceCapability.CHATGPT_AUTH,
            healthy.copy(accessibilityEnabled = false, overlayEnabled = false))
        assertEquals(ReadinessLevel.READY, result.level)
    }

    @Test fun `power saving degrades rather than blocks auth`() {
        assertEquals(ReadinessLevel.DEGRADED,
            readiness(DeviceCapability.CHATGPT_AUTH, healthy.copy(systemPowerSaveMode = true)).level)
    }

    @Test fun `phone control requires accessibility but not overlay`() {
        assertEquals(ReadinessLevel.BLOCKED,
            readiness(DeviceCapability.PHONE_CONTROL, healthy.copy(accessibilityEnabled = false)).level)
        assertEquals(ReadinessLevel.READY,
            readiness(DeviceCapability.PHONE_CONTROL, healthy.copy(overlayEnabled = false)).level)
    }

    @Test fun `floating overlay requires overlay access`() {
        val result = readiness(DeviceCapability.FLOATING_OVERLAY, healthy.copy(overlayEnabled = false))
        assertEquals(ReadinessLevel.BLOCKED, result.level)
        assertEquals(RemediationAction.OPEN_OVERLAY_SETTINGS, result.issues.single().remediation)
    }

    @Test fun `long phone work reports each reliability condition as degraded`() {
        listOf(
            healthy.copy(batteryOptimizationExempt = false) to "battery_optimization_active",
            healthy.copy(systemPowerSaveMode = true) to "power_save_mode_active",
            healthy.copy(backgroundRestricted = true) to "background_restricted",
        ).forEach { (signals, issueId) ->
            val result = readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL, signals)
            assertEquals(ReadinessLevel.DEGRADED, result.level)
            assertTrue(result.issues.any { it.id == issueId })
        }
    }

    @Test fun `missing notifications hide stop UX but do not claim execution failure`() {
        val longRun = readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL,
            healthy.copy(notificationGranted = false))
        assertEquals(ReadinessLevel.DEGRADED, longRun.level)
        assertTrue(longRun.issues.single().technicalReason.contains("execution may continue", ignoreCase = true))
        assertFalse(longRun.issues.single().technicalReason.contains("cannot run", ignoreCase = true))

        val stopControl = readiness(DeviceCapability.NOTIFICATION_STOP_CONTROL,
            healthy.copy(notificationGranted = false))
        assertEquals(ReadinessLevel.BLOCKED, stopControl.level)
    }

    @Test fun `healthy signals are ready for every capability`() {
        DeviceCapability.entries.forEach { assertEquals(it.name, ReadinessLevel.READY, readiness(it).level) }
    }

    @Test fun `issues and remediation are deterministically ordered`() {
        val signals = healthy.copy(
            accessibilityEnabled = false,
            batteryOptimizationExempt = false,
            notificationGranted = false,
            systemPowerSaveMode = true,
            backgroundRestricted = true,
        )
        val first = readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL, signals).issues
        val second = readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL, signals).issues
        assertEquals(first, second)
        assertEquals("accessibility_disabled", first.first().id)
        assertEquals(first.drop(1).map { it.id }.sorted(), first.drop(1).map { it.id })
        assertEquals(RemediationAction.OPEN_ACCESSIBILITY_SETTINGS, first.first().remediation)
    }

    @Test fun `vendor state is explicitly unknown rather than asserted`() {
        val result = readiness(DeviceCapability.LONG_RUNNING_PHONE_CONTROL, healthy.copy(romType = RomType.ONE_UI))
        val issue = result.issues.single { it.id == "vendor_background_state_unknown" }
        assertEquals(ReadinessIssueImpact.ADVISORY, issue.impact)
        assertTrue(issue.technicalReason.contains("does not expose"))
        assertEquals(VendorBackgroundState.NOT_DIRECTLY_OBSERVABLE,
            healthy.vendorBackgroundState)
    }
}
