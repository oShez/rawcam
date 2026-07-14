package com.shez.rawcam.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service that runs [NativeBridge.nativeExportClip] on a background
 * thread and surfaces progress via a persistent notification.
 *
 * Cancel = stop the service (user action, or the system reclaiming it): [onDestroy]
 * flips the volatile [cancelled] flag, and the very next progress callback --
 * invoked synchronously from the native export loop, on this same worker thread --
 * returns false, which unwinds exportClip() cleanly (the current frame's DNG is
 * already on disk; later frames are not written).
 */
class ExportService : Service() {

    private val cancelled = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawvPath = intent?.getStringExtra(EXTRA_RAWV_PATH)
        val outDir = intent?.getStringExtra(EXTRA_OUT_DIR)
        val clipName = intent?.getStringExtra(EXTRA_CLIP_NAME) ?: "clip"
        if (rawvPath == null || outDir == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(clipName, 0, 0))
        status[clipName] = ExportStatus.RUNNING

        thread(name = "export-$clipName") {
            File(outDir).mkdirs()
            val ok = try {
                NativeBridge.nativeExportClip(rawvPath, outDir) { done, total ->
                    if (!cancelled.get()) updateNotification(clipName, done, total)
                    !cancelled.get()
                }
            } catch (e: Exception) {
                false
            }
            status[clipName] = when {
                ok -> ExportStatus.DONE
                cancelled.get() -> ExportStatus.CANCELLED
                else -> ExportStatus.FAILED
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(clipName: String, done: Long, total: Long): Notification {
        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting $clipName")
            .setContentText(if (total > 0) "$pct% ($done/$total frames)" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(clipName: String, done: Long, total: Long) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(clipName, done, total))
    }

    enum class ExportStatus { RUNNING, DONE, FAILED, CANCELLED }

    companion object {
        const val EXTRA_RAWV_PATH = "rawvPath"
        const val EXTRA_OUT_DIR = "outDir"
        const val EXTRA_CLIP_NAME = "clipName"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 1001

        // Keyed by clip name; last-known status per export, polled by ClipsScreen
        // to refresh without needing a bound service connection.
        val status = ConcurrentHashMap<String, ExportStatus>()

        fun start(context: Context, rawvPath: String, outDir: String, clipName: String) {
            status[clipName] = ExportStatus.RUNNING
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_RAWV_PATH, rawvPath)
                putExtra(EXTRA_OUT_DIR, outDir)
                putExtra(EXTRA_CLIP_NAME, clipName)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.stopService(Intent(context, ExportService::class.java))
        }
    }
}
