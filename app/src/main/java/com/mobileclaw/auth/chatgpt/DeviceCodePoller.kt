package com.mobileclaw.auth.chatgpt

import kotlinx.coroutines.delay

internal class DeviceCodePoller(
    private val service: ChatGptAuthService,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun poll(device: DeviceCode): DeviceToken {
        while (true) {
            service.pollDevice(device.deviceAuthId, device.userCode)?.let { return it }
            wait(device.interval * 1000L)
        }
    }
}
