package com.rutger.fastify.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.rutger.fastify.container
import com.rutger.fastify.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps the process alive (foreground, dataSync) while [com.rutger.fastify.data.Downloads] works. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(ID, notification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        lastStartId = startId
        if (!started) {
            started = true
            val d = container.downloads
            scope.launch {
                combine(d.active, d.progress) { active, progress -> active to progress }.collect { (active, progress) ->
                    if (active == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // Only stops if no newer start request came in meanwhile.
                        stopSelf(lastStartId)
                        return@collect
                    }
                    val done = progress.values.sumOf { it.done }
                    val total = progress.values.sumOf { it.total }
                    nm.notify(ID, notification(done, total))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync at 6 h a day; downloads resume on the next app start.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(done: Int, total: Int): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading music")
            .setContentText(if (total > 0) "$done of $total songs" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private companion object {
        const val CHANNEL = "downloads"
        const val ID = 2
    }
}
