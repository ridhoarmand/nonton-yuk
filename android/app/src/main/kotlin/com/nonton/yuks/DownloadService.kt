package com.nonton.yuks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground shell for the queue: keeps the process alive while yt-dlp runs and owns the progress
 * notification. The work itself lives in [DownloadManager].
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground well inside the 5 s window; initialization stays on the worker.
        startForeground(NOTIFICATION_ID, buildNotification(this, DownloadManager.active()))
        DownloadManager.pump()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /** API 34/35: a timed-out dataSync service is killed, so stop the work before that happens. */
    override fun onTimeout(startId: Int) {
        terminate()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        terminate()
    }

    private fun terminate() {
        DownloadManager.timeout()
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "nontonin.unduhan"
        private const val CHANNEL_NAME = "Unduhan"
        private const val NOTIFICATION_ID = 1001

        private var lastPublished = 0L

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Android 12+ can refuse a foreground service started from the background; run the
                // queue attached to the app process rather than dropping the download.
                DownloadManager.pump()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        /** At most one notification update per second; [force] bypasses it for state changes. */
        fun publish(context: Context, entry: Map<String, Any?>, force: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastPublished < 1_000L) return
            lastPublished = now
            // Notifications silently do nothing without the runtime permission; never crash here.
            if (!manager.areNotificationsEnabled()) return
            manager.notify(NOTIFICATION_ID, buildNotification(context, entry))
        }

        private fun buildNotification(context: Context, entry: Map<String, Any?>?): Notification {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
                )
            }
            val progress = (entry?.get("progress") as? Number)?.toFloat() ?: 0f
            val percent = (progress * 100).toInt()
            val title = (entry?.get("title") as? String)?.takeIf { it.isNotBlank() } ?: "Unduhan"
            val text = when (entry?.get("state")) {
                DownloadManager.STATE_QUEUED -> "Menunggu antrean"
                DownloadManager.STATE_DONE -> "Selesai"
                DownloadManager.STATE_FAILED -> "Gagal, buka aplikasi untuk mencoba lagi"
                DownloadManager.STATE_CANCELED -> "Dibatalkan"
                DownloadManager.STATE_PAUSED -> "Dijeda"
                else -> "$percent%"
            }
            @Suppress("DEPRECATION")
            val builder = Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, entry?.get("state") == DownloadManager.STATE_RUNNING)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                builder.setPriority(Notification.PRIORITY_LOW)
            }
            return builder.build()
        }
    }
}
