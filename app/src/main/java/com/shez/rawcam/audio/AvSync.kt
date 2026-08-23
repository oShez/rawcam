package com.shez.rawcam.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A back-to-back reading of the two Android clocks. [monotonicNs] is
 * `System.nanoTime()` (CLOCK_MONOTONIC, frozen while the device sleeps) and
 * [bootNs] is `SystemClock.elapsedRealtimeNanos()` (CLOCK_BOOTTIME, which keeps
 * counting). Their difference changes only when the device suspends, which is
 * exactly what makes a moved bridge a reliable suspend detector.
 */
data class ClockBridge(val monotonicNs: Long, val bootNs: Long) {
    val offsetNs: Long get() = bootNs - monotonicNs
}

/**
 * One `AudioRecord.getTimestamp()` reading: [framePosition] is the sample-frame
 * index that reached the converter at [bootNs] (TIMEBASE_BOOTTIME). This is a
 * converter timestamp, not the time a `read()` call happened to return.
 */
data class AudioAnchor(val framePosition: Long, val bootNs: Long)

/**
 * Pure plan for applying a head trim across a buffered preroll and, when the
 * preroll doesn't cover the whole trim, the chunks still to arrive after it.
 * See [AvSync.planPrerollTrim].
 */
data class PrerollTrimPlan(
    /** Silent interleaved samples to prepend before any preroll chunk.
     * Non-zero only when the trim was negative (audio started late). */
    val padSamples: Long,
    /** The first this many preroll chunks are dropped in full. */
    val dropChunkCount: Int,
    /** Interleaved samples to additionally drop from the START of the chunk
     * at index [dropChunkCount] (0 means that chunk, if any, is kept whole). */
    val partialDropSamples: Int,
    /**
     * Interleaved samples still owed after the WHOLE preroll has been consumed
     * by [dropChunkCount]/[partialDropSamples] -- i.e. the trim was larger than
     * everything buffered. The caller must keep dropping this many samples from
     * the start of subsequently streamed chunks before appending anything to
     * the writer. 0 in the ordinary case.
     */
    val residualSamples: Long,
)

/**
 * Pure A/V sync arithmetic. Deliberately free of Android dependencies so the
 * hard part of this feature is testable on the JVM without a device.
 *
 * Sign convention, used identically here and in the `.rawv` header's
 * `audioOffsetNs`: positive means audio started BEFORE video, which is the
 * normal case because AudioRecorder arms before the capture session.
 */
object AvSync {

    /** A bridge that moves by more than this is a real suspend rather than
     * sampling jitter between the two clock reads. */
    const val SUSPEND_TOLERANCE_NS = 5_000_000L

    /**
     * Converts a camera `SENSOR_TIMESTAMP` to CLOCK_BOOTTIME. When the camera
     * reports SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME the value is already
     * boottime and passes through untouched; otherwise it is monotonic and is
     * shifted by the measured bridge.
     */
    fun toBootNs(sensorTimestampNs: Long, sourceIsRealtime: Boolean, bridge: ClockBridge): Long =
        if (sourceIsRealtime) sensorTimestampNs else sensorTimestampNs + bridge.offsetNs

    /** Back-projects an anchor to the boottime instant of sample 0. */
    fun sample0BootNs(anchor: AudioAnchor, sampleRate: Int): Long =
        anchor.bootNs - anchor.framePosition * 1_000_000_000L / sampleRate

    /**
     * Sample frames to discard from the head so the first remaining frame
     * coincides with frame 0's start of exposure. Negative means audio started
     * late and the head must instead be padded with that many silent frames.
     */
    fun trimSamples(frame0BootNs: Long, audioSample0BootNs: Long, sampleRate: Int): Long =
        (frame0BootNs - audioSample0BootNs) * sampleRate / 1_000_000_000L

    /**
     * Mic clock error in parts per million, by least-squares slope of elapsed
     * wall time against elapsed time implied by the sample count. Positive means
     * wall time ran longer than the samples account for -- the mic clock is slow,
     * so audio gradually lags video. Returns 0 for fewer than two anchors.
     */
    fun driftPpm(anchors: List<AudioAnchor>, sampleRate: Int): Int {
        if (anchors.size < 2) return 0
        val base = anchors.first()
        var sxx = 0.0
        var sxy = 0.0
        for (a in anchors) {
            // Expected elapsed ns from the sample count alone.
            val x = (a.framePosition - base.framePosition).toDouble() * 1_000_000_000.0 / sampleRate
            // Actual elapsed ns on the boottime clock.
            val y = (a.bootNs - base.bootNs).toDouble()
            sxx += x * x
            sxy += x * y
        }
        if (sxx == 0.0) return 0
        return (((sxy / sxx) - 1.0) * 1_000_000.0).roundToInt()
    }

    /** True when the boottime/monotonic gap moved -- the device slept mid-take and
     * every correlation built on [first] is now suspect. */
    fun suspendDetected(first: ClockBridge, latest: ClockBridge): Boolean =
        abs(latest.offsetNs - first.offsetNs) > SUSPEND_TOLERANCE_NS

    /**
     * Splits [trimFrames] (as returned by [trimSamples], in sample FRAMES) into a
     * concrete plan for draining/padding a buffered preroll of chunk SIZES
     * (interleaved samples, i.e. frames * channels already folded in -- the same
     * unit as the FloatArray chunks AudioRecorder buffers).
     *
     * Never throws and never inspects actual sample data -- a pure transform over
     * sizes, so every branch (exact-boundary trim, a trim spanning several
     * chunks, a partial-chunk trim, negative trim/pad, zero trim, and a trim
     * larger than the whole preroll) is exhaustively testable on the JVM.
     */
    fun planPrerollTrim(chunkSizes: List<Int>, trimFrames: Long, channels: Int): PrerollTrimPlan {
        val trimSamples = trimFrames * channels
        if (trimSamples <= 0L) {
            return PrerollTrimPlan(
                padSamples = -trimSamples, dropChunkCount = 0,
                partialDropSamples = 0, residualSamples = 0L,
            )
        }
        var remaining = trimSamples
        for ((idx, size) in chunkSizes.withIndex()) {
            if (remaining >= size) {
                remaining -= size
            } else {
                return PrerollTrimPlan(0L, idx, remaining.toInt(), 0L)
            }
        }
        // Whole preroll consumed but the trim was not fully satisfied.
        return PrerollTrimPlan(0L, chunkSizes.size, 0, remaining)
    }
}
