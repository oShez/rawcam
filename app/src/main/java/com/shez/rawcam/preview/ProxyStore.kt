package com.shez.rawcam.preview

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What a clip's proxy set is: [proxyCount] frames, [stride] source frames apart,
 * covering a clip of [sourceFrames] frames. [complete] is false while generation
 * is still running or was interrupted.
 */
data class ProxyIndex(
    val stride: Int,
    val sourceFrames: Int,
    val proxyCount: Int,
    val complete: Boolean,
)

/**
 * Layout of, and arithmetic over, the pre-rendered preview frames in cacheDir.
 *
 * Free of Android lifecycle on purpose: the sampling contract is the part with a
 * correctness claim, so it lives where JVM unit tests can drive it.
 */
object ProxyStore {
    const val MAX_PROXIES = 1200
    const val MIN_STRIDE = 5
    const val PROXY_MAX_W = 1024
    const val PROXY_MAX_H = 768
    const val JPEG_QUALITY = 80

    private const val INDEX_NAME = "index.json"

    /**
     * Frames between samples: 20% of the clip by default. Above the cap the
     * stride GROWS so the count stays bounded -- the sampled range still spans
     * the whole clip, it just gets coarser. It never truncates the take.
     */
    fun strideFor(frameCount: Int): Int {
        if (frameCount <= 0) return MIN_STRIDE
        val capped = (frameCount + MAX_PROXIES - 1) / MAX_PROXIES
        return maxOf(MIN_STRIDE, capped)
    }

    fun proxyCountFor(frameCount: Int): Int {
        if (frameCount <= 0) return 0
        val stride = strideFor(frameCount)
        return (frameCount + stride - 1) / stride
    }

    /** The source frame a proxy ordinal came from. */
    fun sourceIndexOf(proxyOrdinal: Int, stride: Int): Long = proxyOrdinal.toLong() * stride

    fun dirFor(context: Context, clipName: String): File =
        File(File(context.cacheDir, "proxies"), clipName)

    fun frameFile(dir: File, ordinal: Int): File = File(dir, "%06d.jpg".format(ordinal))

    fun readIndex(dir: File): ProxyIndex? {
        val f = File(dir, INDEX_NAME)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            ProxyIndex(
                stride = o.getInt("stride"),
                sourceFrames = o.getInt("sourceFrames"),
                proxyCount = o.getInt("proxyCount"),
                complete = o.getBoolean("complete"),
            )
        }.getOrNull()
    }

    fun writeIndex(dir: File, index: ProxyIndex) {
        dir.mkdirs()
        val o = JSONObject()
            .put("stride", index.stride)
            .put("sourceFrames", index.sourceFrames)
            .put("proxyCount", index.proxyCount)
            .put("complete", index.complete)
        File(dir, INDEX_NAME).writeText(o.toString())
    }

    /**
     * How many proxies are already on disk. Generation walks ordinals in order,
     * so what exists is always a prefix -- which makes this the resume point
     * after an interrupted run.
     */
    fun completedCount(dir: File): Int {
        if (!dir.isDirectory) return 0
        var n = 0
        while (frameFile(dir, n).isFile) n++
        return n
    }

    fun deleteFor(context: Context, clipName: String) {
        dirFor(context, clipName).deleteRecursively()
    }

    /** Drops proxy directories whose clip is gone. The cache owes nothing to anyone. */
    fun pruneOrphans(context: Context, liveClipNames: Set<String>) {
        val root = File(context.cacheDir, "proxies")
        if (!root.isDirectory) return
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name !in liveClipNames) d.deleteRecursively()
        }
    }
}
