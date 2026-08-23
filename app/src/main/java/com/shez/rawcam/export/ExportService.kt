package com.shez.rawcam.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.IBinder
import android.util.Log
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.audio.WavWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that runs [NativeBridge.nativeExportClip] on a background
 * thread and surfaces progress via a persistent notification.
 *
 * Exports are serialized on [exportExecutor], a single-thread executor: ClipsScreen
 * only disables the Export button per-clip, so tapping Export on two different clips
 * back to back is possible. [NOTIFICATION_ID] is a single, service-instance-wide
 * value only meaningful for the export currently running. A second Export request
 * while one is running queues behind it instead of racing it on a separate ad-hoc
 * thread.
 *
 * Cancel targets one clip by name ([cancelledClips]), not the whole service: a
 * cancelled clip's own progress callback -- invoked synchronously from the native
 * export loop, on the executor's worker thread -- sees its name in the set and
 * returns false, which unwinds exportClip() cleanly (the current frame's DNG is
 * already on disk; later frames are not written) without touching any other
 * queued or running export. [onDestroy] (user backing out entirely, or the system
 * reclaiming the service) is the only path that still cancels everything at once,
 * by sweeping [runningClipName] and [queuedClips] into [cancelledClips] itself.
 */
class ExportService : Service() {

    // Clip names whose export should stop at the next progress checkpoint. Per-clip
    // rather than one shared flag, so cancelling one export -- running or still
    // queued -- no longer aborts every other export sharing this service instance.
    private val cancelledClips: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val exportExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Clip names accepted by onStartCommand whose export task has NOT yet started
    // running. Needed by onDestroy: shutdownNow() discards queued (never-started)
    // tasks outright, so without this their status entries would stay RUNNING
    // forever -- ClipsScreen then shows a permanent "Exporting…" with a Cancel
    // button that no-ops against the already-dead service instance.
    private val queuedClips = java.util.concurrent.ConcurrentLinkedQueue<String>()

    // Name of whatever exportExecutor's single worker thread is currently running,
    // if any -- only that thread writes it (first statement of the execute block,
    // cleared before it returns), onDestroy only reads it. A cancelled RUNNING
    // export is no longer in queuedClips (removed the moment it started), so
    // onDestroy's sweep of queuedClips alone would miss it without this.
    @Volatile private var runningClipName: String? = null

    // notify() is a Binder IPC to system_server that rebuilds a Notification --
    // calling it on every single exported frame (thousands per clip) made the
    // notification plumbing a bottleneck in its own right, serialized into the
    // same loop that's supposed to be writing frames as fast as possible. Only
    // the thread running exportExecutor's task touches this, so a plain var is
    // fine -- no cross-thread access.
    private var lastNotifyElapsedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Control message, not a new export: mark one clip cancelled and retire
            // this start request immediately. stopSelf(startId) here only tears the
            // service down if this was the LAST outstanding request (same mechanism
            // already relied on below for the real export tasks) -- if a genuine
            // export is running/queued, its own outstanding request keeps the
            // instance alive; if the export had already finished before this cancel
            // was delivered (stale tap after startService spun up a fresh, otherwise
            // idle instance), this retires it instead of leaving a zombie service.
            intent.getStringExtra(EXTRA_CLIP_NAME)?.let { cancelledClips.add(it) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        // Clear any stale flag from an earlier cancel that outlived the export it
        // targeted (e.g. a cancel tap that arrived after that export had already
        // finished, or for a clip that was never actually running/queued) -- a new
        // export request for this clip name must always start un-cancelled.
        cancelledClips.remove(clipName)

        queuedClips.add(clipName)
        exportExecutor.execute {
            // Set before queuedClips.remove so onDestroy's runningClipName/queuedClips
            // sweep can never observe a gap where this clip is in neither.
            runningClipName = clipName
            queuedClips.remove(clipName) // now running, not queued; onDestroy's sweep must skip it
            if (cancelledClips.remove(clipName)) {
                // Cancelled while still queued behind another export -- never ran.
                runningClipName = null
                status[clipName] = ExportStatus.CANCELLED
                stopSelf(startId)
                return@execute
            }
            File(outDir).mkdirs()
            val ok = try {
                NativeBridge.nativeExportClip(rawvPath, outDir) { done, total ->
                    val thisCancelled = cancelledClips.contains(clipName)
                    if (!thisCancelled) maybeUpdateNotification(clipName, done, total)
                    !thisCancelled
                }
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                false
            }
            runningClipName = null
            // Consumed unconditionally (not just on the FAILED branch) so a cancel
            // that arrived too late to actually stop a run that finished on its own
            // (ok == true) doesn't linger in cancelledClips and poison a later,
            // unrelated export of a clip with this same name.
            val wasCancelled = cancelledClips.remove(clipName)
            status[clipName] = when {
                ok -> ExportStatus.DONE
                wasCancelled -> ExportStatus.CANCELLED
                else -> ExportStatus.FAILED
            }
            // Copy the sidecar WAV next to the DNGs. Best effort by design: the DNGs
            // are the irreplaceable asset, so a failure here warns and leaves the
            // export successful. Repair first -- a WAV from a killed process still
            // has zeroed RIFF size fields.
            var wavCopied: File? = null
            if (ok) {
                val srcWav = File(rawvPath.removeSuffix(".rawv") + ".wav")
                if (srcWav.exists()) {
                    try {
                        WavWriter.repairIfTruncated(srcWav)
                        val dst = File(outDir, "$clipName.wav")
                        srcWav.copyTo(dst, overwrite = true)
                        wavCopied = dst
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to copy sidecar WAV for $rawvPath", e)
                    }
                }
            }
            // Indexes the just-written DNGs (and, if copied, the sidecar WAV) into
            // MediaStore's generic Files collection so they show up over MTP/USB
            // without waiting on the system's periodic scan. Only meaningful when
            // they were written under the public ExportPaths root -- skipped
            // otherwise, since a private-storage path isn't something the scanner
            // can do anything useful with. Gated on outDir itself (where this export
            // actually landed), not a live hasAllFilesAccess() check: exports
            // are serialized on a single-thread executor, so a queued export
            // can sit behind a running one long enough for the user to flip
            // the permission in Settings between enqueue (when outDir was
            // resolved) and completion (now) -- a live permission check here
            // would either skip scanning a public-root export whose permission
            // was since revoked, or scan a private-root export whose
            // permission was since granted.
            if (ok && ExportPaths.isPublicRoot(this, File(outDir))) {
                val dngPaths = File(outDir).listFiles { f -> f.name.endsWith(".dng") }
                    ?.map { it.absolutePath }?.toTypedArray() ?: emptyArray()
                val paths = (dngPaths.toList() + listOfNotNull(wavCopied?.absolutePath)).toTypedArray()
                if (paths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(this, paths, null, null)
                }
            }
            // Delete the source .rawv only on a genuine successful, non-cancelled
            // completion -- never on FAILED or CANCELLED (the clip would otherwise
            // vanish with no exported DNGs to show for it).
            if (ok && deleteAfter) {
                val rawvDeleted = try {
                    val deleted = File(rawvPath).delete()
                    if (!deleted) Log.e(TAG, "deleteAfter: failed to delete $rawvPath")
                    deleted
                } catch (e: Exception) {
                    Log.e(TAG, "deleteAfter: failed to delete $rawvPath", e)
                    false
                }
                // Pairing: never delete the WAV if the .rawv delete above did not
                // actually succeed -- a surviving .rawv must never leave its WAV
                // deleted, or vice versa.
                if (rawvDeleted) {
                    val srcWav = File(rawvPath.removeSuffix(".rawv") + ".wav")
                    if (srcWav.exists()) {
                        try {
                            if (!srcWav.delete()) Log.e(TAG, "deleteAfter: failed to delete $srcWav")
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteAfter: failed to delete $srcWav", e)
                        }
                    }
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Whole-instance teardown (system reclaim, or nothing left outstanding):
        // nothing can be targeted per-clip once the service itself is going away,
        // so mark the one export actually running as cancelled too -- its own
        // progress callback observes cancelledClips on its next checkpoint, same
        // as a normal per-clip cancel.
        runningClipName?.let { cancelledClips.add(it) }
        exportExecutor.shutdownNow()
        // shutdownNow() silently discards tasks still in the executor's queue --
        // their exports never ran and never will on this instance, so move their
        // status off RUNNING or ClipsScreen shows "Exporting…" forever.
        while (true) {
            val name = queuedClips.poll() ?: break
            status[name] = ExportStatus.CANCELLED
        }
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

    // Always updates on the final frame (so the notification reaches 100%),
    // otherwise at most once per NOTIFY_INTERVAL_MS regardless of how many
    // frames complete in between.
    private fun maybeUpdateNotification(clipName: String, done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val isFinal = total > 0 && done >= total
        if (!isFinal && now - lastNotifyElapsedMs < NOTIFY_INTERVAL_MS) return
        lastNotifyElapsedMs = now
        updateNotification(clipName, done, total)
    }

    enum class ExportStatus { RUNNING, DONE, FAILED, CANCELLED }

    companion object {
        const val EXTRA_RAWV_PATH = "rawvPath"
        const val EXTRA_OUT_DIR = "outDir"
        const val EXTRA_CLIP_NAME = "clipName"
        const val EXTRA_DELETE_AFTER = "deleteAfter"
        const val ACTION_CANCEL = "com.shez.rawcam.export.ACTION_CANCEL"
        private const val TAG = "ExportService"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_INTERVAL_MS = 300L

        // Keyed by clip name; last-known status per export, polled by ClipsScreen
        // to refresh without needing a bound service connection.
        val status = ConcurrentHashMap<String, ExportStatus>()

        fun start(
            context: Context, rawvPath: String, outDir: String, clipName: String,
            deleteAfter: Boolean,
        ) {
            // Bound the process-lifetime status map: finished entries only ever
            // matter briefly for UI suffixes, so once the map grows past a
            // handful, evict everything not currently RUNNING.
            if (status.size > 16) status.entries.removeIf { it.value != ExportStatus.RUNNING }
            status[clipName] = ExportStatus.RUNNING
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra(EXTRA_RAWV_PATH, rawvPath)
                putExtra(EXTRA_OUT_DIR, outDir)
                putExtra(EXTRA_CLIP_NAME, clipName)
                putExtra(EXTRA_DELETE_AFTER, deleteAfter)
            }
            context.startForegroundService(intent)
        }

        /** Cancels exactly one export by clip name. Queued exports behind it, and
         * exports of any other clip, are unaffected. Delivered as a plain (non-
         * foreground) start command -- a no-op if no export with this name is
         * currently running or queued. */
        fun cancel(context: Context, clipName: String) {
            context.startService(Intent(context, ExportService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_CLIP_NAME, clipName)
            })
        }
    }
}
