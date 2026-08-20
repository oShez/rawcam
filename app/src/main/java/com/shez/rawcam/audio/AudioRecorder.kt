package com.shez.rawcam.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Peak levels for the on-screen meter. [SILENCE_DBFS] represents silence. */
data class MeterLevels(
    val peakDbfsL: Float = SILENCE_DBFS,
    val peakDbfsR: Float = SILENCE_DBFS,
    val clipped: Boolean = false,
) {
    companion object { const val SILENCE_DBFS = -160f }
}

/** Everything the `.rawv` header needs to know about this take's audio. */
data class AudioResult(
    val present: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val offsetNs: Long,
    val driftPpm: Int,
    val timestampSource: Int,
    val status: Int,
    val source: Int,
    val fileName: String,
)

/** Mirrors the kAudio* bits in core/include/rawcam/rawv.h. Keep the two in sync. */
object AudioStatus {
    const val PERMISSION_DENIED = 1 shl 0
    const val OPEN_FAILED = 1 shl 1
    const val ENDED_EARLY = 1 shl 2
    const val OVERRUNS = 1 shl 3
    const val SUSPENDED = 1 shl 4
    const val PADDED = 1 shl 5
    const val DRIFT_HIGH = 1 shl 6
    const val PROCESSED_SOURCE = 1 shl 7
    const val SYNC_INVALIDATING = OVERRUNS or SUSPENDED or PADDED
}

/**
 * Owns AudioRecord and the two-thread capture pipeline feeding [WavWriter].
 *
 * Head alignment happens at the START of a take, not at finalize: RIFF data
 * begins at a fixed offset, so trimming later would mean rewriting the file.
 * Captured audio is held in memory until [onFirstFrame] supplies frame 0's
 * sensor timestamp; the computed trim is applied to that prefix and everything
 * after streams straight to disk. Audio arms before the capture session is
 * configured, so the buffered prefix stays well under a second.
 *
 * Failure policy: video always wins. No method throws to the caller; every
 * failure sets a bit in [AudioResult.status] and the take continues.
 *
 * Single-writer contract on [WavWriter]: exactly one thread ever calls
 * append()/close() at a time. [preroll] doubles as the handoff lock between
 * the write thread (which owns the writer once armed) and [onFirstFrame]'s
 * caller (which owns it during the one-time preroll flush) -- see
 * [writeLoop] and [onFirstFrame] for how that hands off without a gap.
 */
class AudioRecorder(private val context: Context) {

    private val _meter = MutableStateFlow(MeterLevels())
    val meter: StateFlow<MeterLevels> = _meter.asStateFlow()

    private var record: AudioRecord? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private val running = AtomicBoolean(false)

    // Bounded, so a stalled filesystem is counted as an overrun rather than
    // blocking the read thread or growing without limit.
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)

    @Volatile private var status = 0
    @Volatile private var sampleRate = SAMPLE_RATE
    @Volatile private var channels = 1
    @Volatile private var audioSource = MediaRecorder.AudioSource.UNPROCESSED
    @Volatile private var gainLinear = 1.0f
    @Volatile private var offsetNs = 0L
    @Volatile private var driftPpm = 0
    @Volatile private var frame0BootNs = 0L
    @Volatile private var firstBridge: ClockBridge? = null
    @Volatile private var sourceIsRealtime = false

    private val anchors = ArrayList<AudioAnchor>()

    // Guards both the preroll deque/counter AND the writer handoff: writeLoop
    // checks-and-buffers under this lock, onFirstFrame drains-and-publishes
    // under the same lock, so the two can never interleave and no chunk can
    // land in preroll after the drain has already run.
    private val preroll = ArrayDeque<FloatArray>()
    private var prerollSamples = 0L

    private var wavFile: File? = null
    @Volatile private var writer: WavWriter? = null

    /** Live input list, Bluetooth already filtered out. Safe to call any time. */
    fun listInputs(): List<AudioInputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map {
            AudioInputDevice(
                id = it.id,
                type = it.type,
                productName = it.productName?.toString() ?: "",
                channelCounts = if (it.channelCounts.isEmpty()) intArrayOf(1) else it.channelCounts,
            )
        }
        return AudioDeviceCatalog.selectable(devices)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opens the input and starts capturing into memory. Returns false when audio
     * could not start; the caller records video anyway and surfaces the reason
     * from [stop]'s status bits.
     */
    fun start(
        wavFile: File,
        deviceKey: String,
        gainDb: Float,
        cameraSourceIsRealtime: Boolean,
    ): Boolean {
        if (running.get()) return false
        status = 0
        synchronized(anchors) { anchors.clear() }
        synchronized(preroll) { preroll.clear(); prerollSamples = 0L }
        frame0BootNs = 0L
        firstBridge = null
        offsetNs = 0L
        driftPpm = 0
        writer = null
        this.wavFile = wavFile
        sourceIsRealtime = cameraSourceIsRealtime
        val clampedGainDb = gainDb.coerceIn(GAIN_DB_MIN, GAIN_DB_MAX)
        gainLinear = 10.0.pow(clampedGainDb.toDouble() / 20.0).toFloat()

        if (!hasPermission()) {
            status = status or AudioStatus.PERMISSION_DENIED
            return false
        }

        val target = AudioDeviceCatalog.resolve(listInputs(), deviceKey)
        channels = target?.preferredChannels ?: 1
        val channelMask =
            if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val rec = openRecord(channelMask)
        if (rec == null) {
            status = status or AudioStatus.OPEN_FAILED
            return false
        }
        if (target != null) {
            // Best effort: a device that vanished between listInputs() and here
            // falls back to the system default rather than failing the take.
            val info = try {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == target.id }
            } catch (e: Exception) {
                Log.w(TAG, "device relookup failed", e)
                null
            }
            if (info != null) {
                try {
                    rec.preferredDevice = info
                } catch (e: Exception) {
                    Log.w(TAG, "setPreferredDevice failed", e)
                }
            }
        }

        record = rec
        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                status = status or AudioStatus.OPEN_FAILED
                releaseRecord()
                false
            } else {
                running.set(true)
                startThreads()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            status = status or AudioStatus.OPEN_FAILED
            releaseRecord()
            false
        }
    }

    /**
     * Tries UNPROCESSED first -- the only source that disables AGC, noise
     * suppression and echo cancellation, all of which pump and mangle production
     * sound -- then falls back. The source actually opened is recorded, because
     * it materially changes how the clip sounds.
     */
    private fun openRecord(channelMask: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, ENCODING)
        if (minBuf <= 0) return null
        val bufBytes = maxOf(minBuf * BUFFER_MULTIPLIER, SAMPLE_RATE * 4)
        for (src in SOURCE_PREFERENCE) {
            val r = try {
                AudioRecord(src, SAMPLE_RATE, channelMask, ENCODING, bufBytes)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord rejected source $src", e)
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                audioSource = src
                if (src != MediaRecorder.AudioSource.UNPROCESSED) {
                    status = status or AudioStatus.PROCESSED_SOURCE
                }
                return r
            }
            r?.release()
        }
        return null
    }

    private fun startThreads() {
        readThread = Thread({ readLoop() }, "rawcam-audio-read").apply { start() }
        writeThread = Thread({ writeLoop() }, "rawcam-audio-write").apply { start() }
    }

    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val buf = FloatArray(READ_SAMPLES)
        val ts = AudioTimestamp()
        var nextAnchorAt = 0L
        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    Log.e(TAG, "AudioRecord.read error $n")
                    status = status or AudioStatus.ENDED_EARLY
                    break
                }
                if (n == 0) continue

                var peakL = 0f
                var peakR = 0f
                var clipped = false
                val g = gainLinear
                for (i in 0 until n) {
                    val v = buf[i] * g
                    buf[i] = v
                    val a = abs(v)
                    if (a >= 1.0f) clipped = true
                    if (channels == 2 && (i and 1) == 1) {
                        if (a > peakR) peakR = a
                    } else if (a > peakL) {
                        peakL = a
                    }
                }
                _meter.value = MeterLevels(
                    peakDbfsL = toDbfs(peakL),
                    peakDbfsR = toDbfs(if (channels == 2) peakR else peakL),
                    clipped = clipped,
                )

                if (!queue.offer(buf.copyOf(n))) status = status or AudioStatus.OVERRUNS

                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= nextAnchorAt) {
                    nextAnchorAt = now + ANCHOR_INTERVAL_NS
                    sampleClocks(rec, ts)
                }
            }
        } catch (e: Exception) {
            // Never let a read-path fault escape this thread -- it must not
            // touch the video path. Mark the take degraded and wind down.
            Log.e(TAG, "audio read loop failed", e)
            status = status or AudioStatus.ENDED_EARLY
        } finally {
            // Prompt shutdown of the write side even if stop() has not been
            // called yet; harmless if it already was.
            running.set(false)
        }
    }

    /**
     * One clock-bridge reading plus one converter anchor. Suspend shows up as a
     * moved bridge and invalidates the take's sync claim, rather than silently
     * shifting every timestamp by the sleep duration.
     */
    private fun sampleClocks(rec: AudioRecord, ts: AudioTimestamp) {
        val bridge = ClockBridge(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
        val first = firstBridge
        if (first == null) {
            firstBridge = bridge
        } else if (AvSync.suspendDetected(first, bridge)) {
            status = status or AudioStatus.SUSPENDED
        }
        if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
            synchronized(anchors) { anchors.add(AudioAnchor(ts.framePosition, ts.nanoTime)) }
        }
    }

    private fun writeLoop() {
        while (true) {
            val chunk = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (chunk == null) {
                if (!running.get() && queue.isEmpty()) break
                continue
            }

            // Decide preroll-vs-stream atomically with onFirstFrame's drain+publish
            // (same monitor), so a chunk can never be added to preroll after
            // onFirstFrame has already finished draining it.
            val target: WavWriter? = synchronized(preroll) {
                val w = writer
                if (w == null) {
                    addToPrerollLocked(chunk)
                    null
                } else {
                    w
                }
            }
            if (target != null) {
                try {
                    target.append(chunk, chunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "WAV append failed", e)
                    status = status or AudioStatus.ENDED_EARLY
                    running.set(false)
                    break
                }
            }
        }
    }

    /** Holds audio until frame 0's timestamp arrives. Capped, because an
     * onFirstFrame that never comes must not grow this without limit.
     * Caller must hold the [preroll] monitor. */
    private fun addToPrerollLocked(chunk: FloatArray) {
        preroll.addLast(chunk)
        prerollSamples += chunk.size
        while (prerollSamples > MAX_PREROLL_SAMPLES && preroll.isNotEmpty()) {
            prerollSamples -= preroll.removeFirst().size
            status = status or AudioStatus.OVERRUNS
        }
    }

    /**
     * Supplies frame 0's SENSOR_TIMESTAMP: computes the head trim, flushes the
     * buffered prefix through it, and switches to streaming. Only the first call
     * has any effect, since only the first frame defines t=0.
     *
     * Synchronized so two near-simultaneous calls can never both pass the
     * "not armed yet" guard and open two writers on the same file.
     */
    @Synchronized
    fun onFirstFrame(sensorTimestampNs: Long) {
        if (!running.get() || writer != null || frame0BootNs != 0L) return

        // AvSync is pure math with no internal guards (by design -- see its own
        // review notes): a degenerate sampleRate could throw or yield NaN/Infinity.
        // sampleRate is pinned to the 48000 constant so this should never happen,
        // but this method runs on the video capture path, so nothing from AvSync
        // may ever propagate out of it regardless.
        val trimFrames: Long
        try {
            val bridge = firstBridge
                ?: ClockBridge(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
            frame0BootNs = AvSync.toBootNs(sensorTimestampNs, sourceIsRealtime, bridge)

            // No anchor yet means getTimestamp has not succeeded on this device.
            // Fall back to "no correction" (offset 0) rather than a wrong one.
            val anchor = synchronized(anchors) { anchors.firstOrNull() }
            val sample0 =
                if (anchor != null) AvSync.sample0BootNs(anchor, sampleRate) else frame0BootNs
            offsetNs = frame0BootNs - sample0
            trimFrames = AvSync.trimSamples(frame0BootNs, sample0, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "AvSync computation failed", e)
            status = status or AudioStatus.ENDED_EARLY
            running.set(false)
            return
        }

        val f = wavFile ?: return
        val w = try {
            WavWriter(f, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "could not open WAV", e)
            status = status or AudioStatus.ENDED_EARLY
            running.set(false)
            return
        }

        // Every append() here can throw (disk full, I/O fault); none of it may
        // reach this method's caller, which is on the video capture path. On
        // failure writer is left null: writeLoop keeps buffering into preroll
        // (harmless, capped) until it observes running == false and exits.
        try {
            // trimFrames counts sample FRAMES; the buffers hold interleaved samples.
            var toDrop = trimFrames * channels
            synchronized(preroll) {
                if (toDrop < 0) {
                    status = status or AudioStatus.PADDED
                    var pad = -toDrop
                    val silence = FloatArray(PAD_CHUNK)
                    while (pad > 0) {
                        val n = minOf(pad, PAD_CHUNK.toLong()).toInt()
                        w.append(silence, n)
                        pad -= n
                    }
                    toDrop = 0
                }
                while (preroll.isNotEmpty()) {
                    val c = preroll.removeFirst()
                    when {
                        toDrop >= c.size -> toDrop -= c.size
                        toDrop > 0 -> {
                            val keep = c.copyOfRange(toDrop.toInt(), c.size)
                            w.append(keep, keep.size)
                            toDrop = 0
                        }
                        else -> w.append(c, c.size)
                    }
                }
                prerollSamples = 0
                // Publish while still holding the lock: from this point on
                // writeLoop, not this method, owns append() on w.
                writer = w
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV preroll flush failed", e)
            status = status or AudioStatus.ENDED_EARLY
            running.set(false)
            try {
                w.close(null)
            } catch (e2: Exception) {
                Log.w(TAG, "WAV close after failed flush also failed", e2)
            }
        }
    }

    /**
     * Stops capture, finalizes the WAV, and reports what happened. Never throws.
     * Must be called BEFORE nativeStopRecording(), which finalizes the header.
     */
    fun stop(): AudioResult {
        if (!running.getAndSet(false)) return result(present = false)
        readThread?.join(THREAD_JOIN_MS)
        writeThread?.join(THREAD_JOIN_MS)
        readThread = null
        writeThread = null

        val snapshot = synchronized(anchors) { ArrayList(anchors) }
        driftPpm = try {
            AvSync.driftPpm(snapshot, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "driftPpm computation failed", e)
            0
        }
        if (abs(driftPpm) > DRIFT_WARN_PPM) status = status or AudioStatus.DRIFT_HIGH

        // No first frame ever arrived: the take produced no aligned audio at all.
        if (writer == null) status = status or AudioStatus.ENDED_EARLY

        try {
            writer?.close(buildBext())
        } catch (e: Exception) {
            Log.e(TAG, "WAV close failed", e)
            status = status or AudioStatus.ENDED_EARLY
        }
        writer = null
        releaseRecord()
        _meter.value = MeterLevels()

        val produced = wavFile?.let { it.exists() && it.length() > WavWriter.HEADER_BYTES } ?: false
        return result(present = produced)
    }

    private fun buildBext(): BextInfo {
        val now = Date()
        return BextInfo(
            description = "RawCam offsetNs=$offsetNs driftPpm=$driftPpm status=$status " +
                "source=$audioSource rate=$sampleRate ch=$channels",
            originationDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
            originationTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(now),
            timeReferenceSamples = 0L,
        )
    }

    private fun result(present: Boolean) = AudioResult(
        present = present,
        sampleRate = sampleRate,
        channels = channels,
        offsetNs = offsetNs,
        driftPpm = driftPpm,
        timestampSource = if (sourceIsRealtime) 1 else 0,
        status = status,
        source = audioSource,
        fileName = wavFile?.name ?: "",
    )

    private fun releaseRecord() {
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        try {
            record?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release failed", e)
        }
        record = null
    }

    private fun toDbfs(peak: Float): Float =
        if (peak <= 0f) MeterLevels.SILENCE_DBFS
        else (20.0 * log10(peak.toDouble())).toFloat().coerceAtLeast(MeterLevels.SILENCE_DBFS)

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 48_000
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val BUFFER_MULTIPLIER = 4
        private const val READ_SAMPLES = 4096
        private const val QUEUE_CAPACITY = 32
        private const val POLL_TIMEOUT_MS = 100L
        private const val THREAD_JOIN_MS = 2_000L
        private const val ANCHOR_INTERVAL_NS = 1_000_000_000L
        private const val PAD_CHUNK = 4096
        private const val DRIFT_WARN_PPM = 100
        private const val GAIN_DB_MIN = -20.0f
        private const val GAIN_DB_MAX = 30.0f

        /** 2s at 48kHz stereo. Audio arms before session config, so a normal take
         * buffers a fraction of this before frame 0 arrives. */
        private const val MAX_PREROLL_SAMPLES = 2L * SAMPLE_RATE * 2

        private val SOURCE_PREFERENCE = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
