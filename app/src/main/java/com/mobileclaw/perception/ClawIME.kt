package com.mobileclaw.perception

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.inputmethod.EditorInfo

/** Lightweight IME for reliable text injection bypassing clipboard. */
class ClawIME : InputMethodService() {

    companion object {
        var instance: ClawIME? = null
            private set

        fun isReady(): Boolean = instance?.currentInputConnection != null

        fun inputText(text: String): Boolean =
            instance?.currentInputConnection?.commitText(text, 1) == true

        fun statusSummary(): String {
            val service = instance
            val context = service
                ?: return "MobileClaw Keyboard is not active. Enable it in Android keyboard settings and select it as the current keyboard."
            return statusSummary(context)
        }

        fun statusSummary(context: Context): String {
            val service = instance
            val enabledInputMethods = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
            val defaultInputMethod = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            return when {
                !enabledInputMethods.contains(context.packageName) ->
                    "MobileClaw Keyboard is not enabled. Enable it in Android keyboard settings."
                !defaultInputMethod.contains(context.packageName) ->
                    "MobileClaw Keyboard is enabled but is not the current keyboard. Select MobileClaw from the keyboard switcher."
                service == null ->
                    "MobileClaw Keyboard is selected, but its service is not connected yet. Tap a text field or switch away and back to MobileClaw."
                currentConnectionUnavailable() ->
                    "MobileClaw Keyboard is current, but the focused field has no available InputConnection. Tap the text field to focus it first."
                else -> "MobileClaw Keyboard is ready."
            }
        }

        private fun currentConnectionUnavailable(): Boolean =
            instance?.currentInputConnection == null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onCreateInputView() = null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {}
}
