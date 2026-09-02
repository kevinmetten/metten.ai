package com.mobileclaw.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.mobileclaw.ClawApplication
import com.mobileclaw.R
import com.mobileclaw.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground lifecycle/control surface only; execution remains in the registered controller Job. */
class AgentExecutionForegroundService : Service() {
    companion object {
        private const val TAG = "AgentExecutionFgs"
        private const val CHANNEL_ID = "mobileclaw_agent_execution"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_START = "com.mobileclaw.agent.START_PROTECTION"
        private const val ACTION_STOP = "com.mobileclaw.agent.STOP_TASK"
        private const val EXTRA_TASK_ID = "task_id"

        fun requestProtection(context: Context, taskId: String): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AgentExecutionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, taskId)
            })
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to request foreground protection for taskId=$taskId", t)
            false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as ClawApplication).agentTaskController
    private var representedTaskId: String? = null
    private val protectedTaskIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        scope.launch {
            controller.activeTasks.collectLatest {
                val target = controller.notificationTarget()
                if (target == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    representedTaskId = target.taskId
                    notifyForeground(target.taskId)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (intent?.action == ACTION_STOP) {
            taskId?.let { controller.cancelTask(it, AgentCancellationReason.SERVICE_REQUEST) }
            return START_NOT_STICKY
        }
        val task = taskId?.let(controller::task)
        if (intent?.action != ACTION_START || task == null || !task.foregroundRequested) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        representedTaskId = task.taskId
        notifyForeground(task.taskId)
        protectedTaskIds += task.taskId
        controller.markForegroundProtected(task.taskId, true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        protectedTaskIds.forEach { controller.markForegroundProtected(it, false) }
        protectedTaskIds.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyForeground(taskId: String) {
        val notification = buildNotification(taskId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(taskId: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, taskId.hashCode(), Intent(this, AgentExecutionForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TASK_ID, taskId)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MobileClaw is operating your phone")
            .setContentText("An agent task is active")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent tasks", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
