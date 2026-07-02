package com.xtunnel.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import bridge.Bridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TunnelService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))
                executor.execute {
                    try {
                        Bridge.stop()
                        Bridge.start(config)
                        updateNotification(getString(R.string.notification_running))
                    } catch (e: Exception) {
                        updateNotification("Start failed: ${e.message ?: "unknown error"}")
                        stopSelf()
                    }
                }
            }

            ACTION_STOP -> {
                executor.execute {
                    Bridge.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Bridge.stop()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xtunnel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.xtunnel.android.action.START"
        private const val ACTION_STOP = "com.xtunnel.android.action.STOP"
        private const val EXTRA_CONFIG = "config"

        fun startIntent(context: Context, config: String): Intent =
            Intent(context, TunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)

        fun stopIntent(context: Context): Intent =
            Intent(context, TunnelService::class.java)
                .setAction(ACTION_STOP)
    }
}
