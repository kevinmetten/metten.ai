package com.mobileclaw.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mobileclaw.ClawApplication
import com.mobileclaw.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Privacy-safe microphone foreground lifecycle anchor; media remains application-owned. */
class VoiceSessionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Live Voice", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            (application as ClawApplication).realtimeVoiceController.state.collectLatest {
                if (it.phase == RealtimeVoicePhase.IDLE || it.phase == RealtimeVoicePhase.FAILED) stopSelf()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Metten Live Voice is active").setContentText("Microphone session active").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, notification)
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "mobileclaw_live_voice"
        private const val ID = 7302
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, VoiceSessionForegroundService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, VoiceSessionForegroundService::class.java))
    }
}
