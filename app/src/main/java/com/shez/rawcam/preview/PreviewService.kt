package com.shez.rawcam.preview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import com.shez.rawcam.NativeBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a clip's preview proxies in the background, one clip at a time.
 *
 * Never started while a recording is in flight: developing frames is CPU-heavy,
 * and competing with the capture pipeline is exactly how frames get dropped.
 * RecordViewModel enqueues on stop, which is what keeps this off the capture
 * path -- the service does not police that itself.
 */
class PreviewService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    // Only the executor thread touches this, so a plain var is fine.
    private var lastNotifyElapsedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_RAWV_PATH)
        val clipName = intent?.getStringExtra(EXTRA_CLIP_NAME)
        if (path == null || clipName == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(clipName, 0, 0))
        executor.execute {
            runCatching { generate(File(path), clipName) }
            progress.remove(clipName)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun generate(rawv: File, clipName: String) {
        val dir = ProxyStore.dirFor(this, clipName)
        val handle = NativeBridge.nativeOpenClip(rawv.absolutePath)
        if (handle == 0L) {
            // Unreadable clip: record an empty-but-complete set so the UI says
            // "unavailable" instead of spinning on a preview that never arrives.
            ProxyStore.writeIndex(dir, ProxyIndex(ProxyStore.MIN_STRIDE, 0, 0, complete = true))
            return
        }
        try {
            val frames = NativeBridge.nativeClipFrameCount(handle).toInt()
            val stride = ProxyStore.strideFor(frames)
            val total = ProxyStore.proxyCountFor(frames)
            ProxyStore.writeIndex(dir, ProxyIndex(stride, frames, total, complete = false))
            // Resume: ordinals are written in order, so what exists is a prefix.
            var ordinal = ProxyStore.completedCount(dir)
            while (ordinal < total && !cancelled.get()) {
                val src = ProxyStore.sourceIndexOf(ordinal, stride)
                val pixels = NativeBridge.nativeDecodeFrame(
                    handle, src, ProxyStore.PROXY_MAX_W, ProxyStore.PROXY_MAX_H,
                ) ?: break
                writeJpeg(pixels, ProxyStore.frameFile(dir, ordinal))
                ordinal++
                progress[clipName] = ordinal
                notifyProgress(clipName, ordinal, total)
            }
            ProxyStore.writeIndex(dir, ProxyIndex(stride, frames, total, complete = ordinal >= total))
        } finally {
            NativeBridge.nativeCloseClip(handle)
        }
    }

    /** `pixels` is [width, height, argb...] as returned by nativeDecodeFrame. */
    private fun writeJpeg(pixels: IntArray, out: File) {
        if (pixels.size < 3) return
        val w = pixels[0]
        val h = pixels[1]
        if (w <= 0 || h <= 0 || pixels.size < w * h + 2) return
        val bmp = Bitmap.createBitmap(pixels, 2, w, w, h, Bitmap.Config.ARGB_8888)
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, ProxyStore.JPEG_QUALITY, it) }
        bmp.recycle()
    }

    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdown()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Preview", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(clipName: String, done: Int, total: Int): Notification {
        val pct = if (total > 0) (done * 100) / total else 0
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Preparing preview")
            .setContentText(if (total > 0) "$pct% ($done/$total frames)" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .build()
    }

    // notify() is a Binder IPC that rebuilds the whole Notification. A proxy set
    // runs to 1200 frames, so notifying per frame would put a thousand IPCs in
    // the same loop that is supposed to be decoding. Always update on the last
    // one so the notification reaches 100%, otherwise at most once per interval.
    private fun notifyProgress(clipName: String, done: Int, total: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val isFinal = total > 0 && done >= total
        if (!isFinal && now - lastNotifyElapsedMs < NOTIFY_INTERVAL_MS) return
        lastNotifyElapsedMs = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(clipName, done, total))
    }

    companion object {
        const val EXTRA_RAWV_PATH = "rawvPath"
        const val EXTRA_CLIP_NAME = "clipName"
        private const val CHANNEL_ID = "preview"
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFY_INTERVAL_MS = 300L
        private val cancelled = AtomicBoolean(false)
        private val progress = ConcurrentHashMap<String, Int>()

        fun start(context: Context, rawvPath: String, clipName: String) {
            cancelled.set(false)
            progress[clipName] = 0
            val i = Intent(context, PreviewService::class.java)
                .putExtra(EXTRA_RAWV_PATH, rawvPath)
                .putExtra(EXTRA_CLIP_NAME, clipName)
            context.startForegroundService(i)
        }

        /** Proxies written so far for [clipName], or -1 when it is not queued. */
        fun progressFor(clipName: String): Int = progress[clipName] ?: -1
    }
}
