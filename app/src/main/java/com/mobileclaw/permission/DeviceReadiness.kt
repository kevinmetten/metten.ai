package com.mobileclaw.permission

enum class DeviceCapability {
    CHAT,
    CHATGPT_AUTH,
    PHONE_CONTROL,
    LONG_RUNNING_PHONE_CONTROL,
    FLOATING_OVERLAY,
    NOTIFICATION_STOP_CONTROL,
}

enum class ReadinessLevel { READY, DEGRADED, BLOCKED }

enum class ReadinessIssueImpact { RELIABILITY, EXECUTION, USER_CONTROL, ADVISORY }

enum class RemediationAction {
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_OVERLAY_SETTINGS,
    REQUEST_BATTERY_OPTIMIZATION_EXEMPTION,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_BATTERY_SAVER_SETTINGS,
    OPEN_APP_DETAILS,
    OPEN_ROM_BACKGROUND_SETTINGS,
}

/** Vendor background controls are not exposed by a stable Android API. */
enum class VendorBackgroundState { NOT_DIRECTLY_OBSERVABLE }

data class DeviceReadinessSignals(
    val accessibilityEnabled: Boolean,
    val overlayEnabled: Boolean,
    val batteryOptimizationExempt: Boolean,
    val notificationGranted: Boolean,
    val systemPowerSaveMode: Boolean,
    val backgroundRestricted: Boolean,
    val romType: RomType,
    val vendorBackgroundState: VendorBackgroundState = VendorBackgroundState.NOT_DIRECTLY_OBSERVABLE,
)

fun interface DeviceReadinessSignalsProvider {
    fun snapshot(): DeviceReadinessSignals
}

data class ReadinessIssue(
    val id: String,
    val level: ReadinessLevel,
    val impact: ReadinessIssueImpact,
    val capability: DeviceCapability,
    val summary: String,
    val technicalReason: String,
    val remediation: RemediationAction,
)

data class DeviceReadiness(
    val capability: DeviceCapability,
    val level: ReadinessLevel,
    val issues: List<ReadinessIssue>,
)

class DeviceReadinessEngine(private val signalsProvider: DeviceReadinessSignalsProvider) {
    fun evaluate(capability: DeviceCapability): DeviceReadiness = evaluate(capability, signalsProvider.snapshot())

    fun snapshot(): DeviceReadinessSignals = signalsProvider.snapshot()

    fun evaluate(capability: DeviceCapability, signals: DeviceReadinessSignals): DeviceReadiness {
        val issues = buildList {
            if (capability in setOf(DeviceCapability.PHONE_CONTROL, DeviceCapability.LONG_RUNNING_PHONE_CONTROL) &&
                !signals.accessibilityEnabled
            ) add(issue("accessibility_disabled", ReadinessLevel.BLOCKED, ReadinessIssueImpact.EXECUTION,
                capability, "Accessibility is unavailable", "Screen inspection and input require the accessibility service.",
                RemediationAction.OPEN_ACCESSIBILITY_SETTINGS))

            if (capability == DeviceCapability.FLOATING_OVERLAY && !signals.overlayEnabled) {
                add(issue("overlay_disabled", ReadinessLevel.BLOCKED, ReadinessIssueImpact.EXECUTION,
                    capability, "Floating overlay is unavailable", "Android has not granted display-over-other-apps access.",
                    RemediationAction.OPEN_OVERLAY_SETTINGS))
            }

            if (capability == DeviceCapability.NOTIFICATION_STOP_CONTROL && !signals.notificationGranted) {
                add(issue("notifications_disabled", ReadinessLevel.BLOCKED, ReadinessIssueImpact.USER_CONTROL,
                    capability, "Notification Stop control is unavailable", "Notification permission is denied on this Android version.",
                    RemediationAction.OPEN_NOTIFICATION_SETTINGS))
            }

            if (capability in setOf(DeviceCapability.CHATGPT_AUTH, DeviceCapability.LONG_RUNNING_PHONE_CONTROL)) {
                if (!signals.batteryOptimizationExempt) add(issue("battery_optimization_active", ReadinessLevel.DEGRADED,
                    ReadinessIssueImpact.RELIABILITY, capability, "Background lifetime may be shortened",
                    "Android battery optimization can stop work while another app is visible.",
                    RemediationAction.REQUEST_BATTERY_OPTIMIZATION_EXEMPTION))
                if (signals.systemPowerSaveMode) add(issue("power_save_mode_active", ReadinessLevel.DEGRADED,
                    ReadinessIssueImpact.RELIABILITY, capability, "Power Saving may reduce reliability",
                    "The system reports that Power Saving is active.", RemediationAction.OPEN_BATTERY_SAVER_SETTINGS))
                if (signals.backgroundRestricted) add(issue("background_restricted", ReadinessLevel.DEGRADED,
                    ReadinessIssueImpact.RELIABILITY, capability, "Background execution is restricted",
                    "Android reports this app as background restricted.", RemediationAction.OPEN_APP_DETAILS))
            }

            if (capability == DeviceCapability.LONG_RUNNING_PHONE_CONTROL && !signals.notificationGranted) {
                add(issue("notification_stop_hidden", ReadinessLevel.DEGRADED, ReadinessIssueImpact.USER_CONTROL,
                    capability, "Notification Stop control may not be visible",
                    "Foreground execution may continue, but denied notifications can hide the ordinary drawer Stop action.",
                    RemediationAction.OPEN_NOTIFICATION_SETTINGS))
            }

            if (capability in setOf(DeviceCapability.CHATGPT_AUTH, DeviceCapability.LONG_RUNNING_PHONE_CONTROL) &&
                signals.romType != RomType.AOSP
            ) add(issue("vendor_background_state_unknown", ReadinessLevel.DEGRADED, ReadinessIssueImpact.ADVISORY,
                capability, "Additional vendor controls are not directly observable",
                "Android does not expose a supported API for ${signals.romType.displayName} vendor-specific background controls.",
                RemediationAction.OPEN_ROM_BACKGROUND_SETTINGS))
        }.sortedWith(compareBy<ReadinessIssue>({ levelRank(it.level) }, { it.id }))

        val level = when {
            issues.any { it.level == ReadinessLevel.BLOCKED } -> ReadinessLevel.BLOCKED
            issues.any { it.level == ReadinessLevel.DEGRADED } -> ReadinessLevel.DEGRADED
            else -> ReadinessLevel.READY
        }
        return DeviceReadiness(capability, level, issues)
    }

    private fun issue(id: String, level: ReadinessLevel, impact: ReadinessIssueImpact,
        capability: DeviceCapability, summary: String, reason: String, remediation: RemediationAction) =
        ReadinessIssue(id, level, impact, capability, summary, reason, remediation)

    private fun levelRank(level: ReadinessLevel) = when (level) {
        ReadinessLevel.BLOCKED -> 0
        ReadinessLevel.DEGRADED -> 1
        ReadinessLevel.READY -> 2
    }
}

/** Small pure seam used before any task lifecycle resources are created. */
fun runIfDeviceReady(readiness: DeviceReadiness, start: () -> Unit): Boolean {
    if (readiness.level == ReadinessLevel.BLOCKED) return false
    start()
    return true
}
