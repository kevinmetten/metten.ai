package com.mobileclaw.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Session-owned communication route. Mute stops capture, not the route used by ongoing speech. */
internal class AndroidDuplexAudio(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var acquired = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldDevice: AudioDeviceInfo? = null
    private var selectedDevice: Int? = null
    private var oldSpeaker = false

    @Synchronized fun acquire() {
        if (acquired) return
        oldMode = manager.mode
        check(oldMode != AudioManager.MODE_IN_CALL) { "Phone call currently owns communication audio." }
        if (Build.VERSION.SDK_INT >= 31) oldDevice = manager.communicationDevice
        @Suppress("DEPRECATION")
        run { oldSpeaker = manager.isSpeakerphoneOn }
        acquired = true
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = manager.availableCommunicationDevices
                val preferred = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: error("No duplex communication output is available.")
                check(manager.setCommunicationDevice(preferred)) { "Communication audio routing failed." }
                selectedDevice = preferred.id
            } else {
                @Suppress("DEPRECATION")
                run { manager.isSpeakerphoneOn = true }
            }
            VoiceDiagnostics.event("DUPLEX_ROUTE_ACQUIRED", "mode=${manager.mode} device=${selectedDevice ?: -1}")
        } catch (failure: Throwable) { release(); throw failure }
    }

    @Synchronized fun release() {
        if (!acquired) return
        acquired = false
        // Do not restore over an intervening phone call or a route selected by another owner.
        if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (Build.VERSION.SDK_INT >= 31) {
                if (manager.communicationDevice?.id == selectedDevice) {
                    val previous = oldDevice
                    if (previous != null) manager.setCommunicationDevice(previous) else manager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                run { manager.isSpeakerphoneOn = oldSpeaker }
            }
            manager.mode = oldMode
        }
        selectedDevice = null
        VoiceDiagnostics.event("DUPLEX_ROUTE_RELEASED", "mode=${manager.mode}")
    }
}
