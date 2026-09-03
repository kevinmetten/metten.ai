package com.mobileclaw.permission

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mobileclaw.perception.ClawAccessibilityService

class AndroidDeviceReadinessSignalsProvider(
    private val context: Context,
    private val romTypeProvider: () -> RomType,
) : DeviceReadinessSignalsProvider {
    override fun snapshot(): DeviceReadinessSignals {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return DeviceReadinessSignals(
            accessibilityEnabled = ClawAccessibilityService.isEnabled(),
            overlayEnabled = Settings.canDrawOverlays(context),
            batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            systemPowerSaveMode = powerManager.isPowerSaveMode,
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager.isBackgroundRestricted,
            romType = romTypeProvider(),
        )
    }
}
