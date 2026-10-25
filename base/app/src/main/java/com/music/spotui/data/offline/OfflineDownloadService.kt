package com.music.spotui.data.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.myspoti.engine.R

/** Downloads have their own lifecycle, independent of playback and the visible activity. */
class OfflineDownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val update = object : Runnable {
        override fun run() {
            val remaining = OfflineDownloader.pendingCount()
            if (remaining == 0) { stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(ID, notification(remaining))
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.collection_downloaded), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(OfflineDownloader.pendingCount()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            OfflineDownloader.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        // A system restart restores the persisted queue before testing whether it is empty.
        if (intent == null) OfflineDownloader.resumePending()
        handler.removeCallbacks(update)
        handler.post(update)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // Android 15 limits data-sync foreground services to six hours per day. Keep the
    // persisted queue for a later resume instead of clearing it or triggering an ANR.
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(remaining: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, app.myspoti.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OfflineDownloadService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.collection_downloaded))
            .setContentText(resources.getQuantityString(R.plurals.local_files_count, remaining, remaining))
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.downloads_stop_all_title), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "offline_downloads"
        private const val ID = 4102
        private const val STOP = "net.yexe.myspoti.STOP_DOWNLOADS"
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, OfflineDownloadService::class.java)) }
            catch (e: Exception) { Log.w("Offline", "Could not start download service: ${e.javaClass.simpleName}") }
        }
    }
}
