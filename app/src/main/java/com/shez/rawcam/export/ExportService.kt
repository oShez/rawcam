package com.shez.rawcam.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that runs [NativeBridge.nativeExportClip] on a background
 * thread and surfaces progress via a persistent notification.
 *
 * Exports are serialized on [exportExecutor], a single-thread executor: ClipsScreen
 * only disables the Export button per-clip, so tapping Export on two different clips
 * back to back is possible, and [cancelled] / [NOTIFICATION_ID] are single,
 * service-instance-wide values that are only meaningful for one export at a time. A
 * second Export request while one is running queues behind it instead of racing it on
 * a separate ad-hoc thread (the previous behavior, which let two exports share --
 * and clobber -- the same cancel flag and notification).
 *
 * Cancel = stop the service (user action, or the system reclaiming it): [onDestroy]
 * flips the volatile [cancelled] flag, and the very next progress callback --
 * invoked synchronously from the native export loop, on the executor's worker thread
 * -- returns false, which unwinds exportClip() cleanly (the current frame's DNG is
 * already on disk; later frames are not written). Any export still queued behind the
 * one that was running is abandoned along with the service instance; the next Export
 * tap starts a fresh instance with [cancelled] reset to false.
 */
class ExportService : Service() {

    private val cancelled = AtomicBoolean(false)
    private val exportExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rawvPath = intent?.getStringExtra(EXTRA_RAWV_PATH)
        val outDir = intent?.getStringExtra(EXTRA_OUT_DIR)
        val clipName = intent?.getStringExtra(EXTRA_CLIP_NAME) ?: "clip"
        // Read per-Intent, not stashed in a service field: exports are serialized on
        // exportExecutor below, so a second Export tap while one is running queues a
        // second onStartCommand call (and a second lambda captured here) behind it --
        // a single mutable service-level flag would be overwritten by the second
        // call before the first export's lambda got to read it. Capturing deleteAfter
        // in this call's local val, closed over by this call's own exportExecutor.execute
        // lambda, keeps it correctly scoped per export.
        val deleteAfter = intent?.getBooleanExtra(EXTRA_DELETE_AFTER, false) ?: false
        if (rawvPath == null || outDir == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(clipName, 0, 0))
        status[clipName] = ExportStatus.RUNNING

        exportExecutor.execute {
            File(outDir).mkdirs()
            val ok = try {
                NativeBridge.nativeExportClip(rawvPath, outDir) { done, total ->
                    if (!cancelled.get()) updateNotification(clipName, done, total)
                    !cancelled.get()
                }
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                false
            }
            status[clipName] = when {
                ok -> ExportStatus.DONE
                cancelled.get() -> ExportStatus.CANCELLED
                else -> ExportStatus.FAILED
            }
            // Delete the source .rawv only on a genuine successful, non-cancelled
            // completion -- never on FAILED or CANCELLED (the clip would otherwise
            // vanish with no exported DNGs to show for it).
            if (ok && deleteAfter) {
                try {
                    if (!File(rawvPath).delete()) {
                        Log.e(TAG, "deleteAfter: failed to delete $rawvPath")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "deleteAfter: failed to delete $rawvPath", e)
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled.set(true)
        exportExecutor.shutdownNow()
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
        const val EXTRA_DELETE_AFTER = "deleteAfter"
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 1001

        // Keyed by clip name; last-known status per export, polled by ClipsScreen
        // to refresh without needing a bound service connection.
        val status = ConcurrentHashMap<String, ExportStatus>()

        fun start(
            context: Context, rawvPath: String, outDir: String, clipName: String,
            deleteAfter: Boolean = false,
        ) {
            status[clipName] = ExportStatus.RUNNING
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_RAWV_PATH, rawvPath)
                putExtra(EXTRA_OUT_DIR, outDir)
                putExtra(EXTRA_CLIP_NAME, clipName)
                putExtra(EXTRA_DELETE_AFTER, deleteAfter)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.stopService(Intent(context, ExportService::class.java))
        }
    }
}
