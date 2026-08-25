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
import java.util.concurrent.atomic.AtomicInteger
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
    // Alignment was not applied, or applying it could not be trusted: either the
    // no-anchor fallback (getTimestamp() never succeeded, so offset/trim are
    // both 0 by policy, not by measurement) or a computed trim implausible
    // enough that applying it was refused outright. Distinct from
    // PADDED/OVERRUNS, which mean a trim WAS applied but the take is otherwise
    // degraded.
    const val ALIGNMENT_UNVERIFIED = 1 shl 8
    const val SYNC_INVALIDATING = OVERRUNS or SUSPENDED or PADDED or ALIGNMENT_UNVERIFIED
}

/**
 * Owns AudioRecord and the two-thread capture pipeline feeding [WavWriter].
 *
 * Head alignment happens at the START of a take, not at finalize: RIFF data
 * begins at a fixed offset, so trimming later would mean rewriting the file.
 * Captured audio is held in memory until [onFirstFrame] supplies frame 0's
 * sensor timestamp. [onFirstFrame] itself only computes the trim -- it runs on
 * the Camera2 capture-callback thread and must stay cheap -- and publishes it
 * via [frame0BootNs]; the write thread notices that publish, opens the
 * WavWriter, and flushes the buffered prefix through [AvSync.planPrerollTrim]
 * (see [flushFirstFrame]). Everything after streams straight to disk. Audio
 * arms before the capture session is configured, so the buffered prefix stays
 * well under a second.
 *
 * Failure policy: video always wins. No method throws to the caller; every
 * failure sets a bit in [AudioResult.status] (via [addStatus], which is safe
 * across the four threads that touch it) and the take continues.
 *
 * Single-writer contract on [WavWriter]: exactly one thread -- the write
 * thread -- ever calls append()/close(). [preroll] doubles as the lock around
 * that handoff: [writeLoop] checks-and-buffers under it, [flushFirstFrame]
 * drains-and-publishes under the same lock, so a chunk can never land in
 * preroll after the drain has already run.
 */
class AudioRecorder(private val context: Context) {

    private val _meter = MutableStateFlow(MeterLevels())
    val meter: StateFlow<MeterLevels> = _meter.asStateFlow()

    // Live mirror of [status], for the on-screen AUDIO DEGRADED indicator
    // (see AudioMeter.kt) -- unlike the AudioResult returned by [stop], the UI
    // needs to see status bits while the take is still recording, not only
    // after it ends. Updated by [addStatus]; reset alongside status in [start].
    private val _liveStatus = MutableStateFlow(0)
    val liveStatus: StateFlow<Int> = _liveStatus.asStateFlow()

    private var record: AudioRecord? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null

    // running is the workers' "keep looping" signal: it is flipped false both
    // by stop() and by internal failure paths (read error, append failure,
    // AvSync fault) so a broken pipeline winds itself down promptly. It must
    // NOT be used to decide whether stop()'s cleanup (joins/close/release)
    // still needs to run -- an internal failure ending the take early is
    // exactly the case that most needs that cleanup. armed tracks that
    // instead: start() sets it true only once threads are actually running,
    // and only stop() ever clears it (once, via getAndSet, which also makes
    // stop() idempotent -- a second call sees it already false and no-ops).
    private val running = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)

    // "The read side will produce nothing more." Set by readLoop's finally, AFTER its
    // post-stop drain, and by stop() as a fallback once the read thread has been given
    // its chance to terminate.
    //
    // The write loop used to exit on `!running && queue.isEmpty()`, which is a race
    // with the drain: stop() clears `running` first, so the writer could poll an empty
    // queue and break while the read thread was still finishing its in-flight read and
    // draining. Those chunks were then enqueued with no consumer left and silently
    // dropped -- which made the drain look like it had no effect at all.
    private val readDone = AtomicBoolean(false)

    // Bounded, so a stalled filesystem is counted as an overrun rather than
    // blocking the read thread or growing without limit.
    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)

    // status is mutated from four different threads (read, write, camera
    // capture-callback, and stop()'s caller). AtomicInteger + addStatus's
    // getAndUpdate/updateAndGet is what actually makes `or` atomic here --
    // @Volatile alone only makes each individual read/write atomic, not the
    // read-modify-write `or` a plain `status = status or X` performs, so two
    // concurrent sets could otherwise lose one bit. These bits ARE the entire
    // "warn loudly" mechanism (the toast, SYNC_INVALIDATING, the header's
    // permanent record), so losing one silently is not acceptable.
    private val status = AtomicInteger(0)
    @Volatile private var sampleRate = SAMPLE_RATE
    @Volatile private var channels = 1
    @Volatile private var audioSource = MediaRecorder.AudioSource.UNPROCESSED
    @Volatile private var gainLinear = 1.0f
    @Volatile private var offsetNs = 0L
    @Volatile private var driftPpm = 0
    @Volatile private var frame0BootNs = 0L
    @Volatile private var firstBridge: ClockBridge? = null
    // First out-of-tolerance clock-bridge reading, awaiting a second consecutive
    // confirmation before latching SUSPENDED -- see [sampleClocks].
    @Volatile private var pendingSuspectBridge: ClockBridge? = null
    @Volatile private var sourceIsRealtime = false

    // Set once by onFirstFrame (pure math, cheap) and consumed once by
    // flushFirstFrame on the write thread, which does the actual I/O. Valid
    // only once frame0BootNs != 0L.
    @Volatile private var firstFrameTrimFrames = 0L
    // Guards flushFirstFrame so it runs at most once per take, regardless of
    // whether it succeeds. Only ever touched by the write thread.
    @Volatile private var firstFrameHandled = false
    // Residual trim (Critical 1) that the buffered preroll could not fully
    // absorb -- carried forward instead of being silently discarded, and
    // consumed from the start of subsequently streamed chunks by
    // [appendTrimmed]. Set once by flushFirstFrame (under the preroll lock,
    // before writer is published); read/decremented afterwards only by the
    // write thread, so no further synchronization is needed.
    @Volatile private var pendingTrimSamples = 0L

    private val anchors = ArrayList<AudioAnchor>()

    // Guards both the preroll deque/counter AND the writer handoff: writeLoop
    // checks-and-buffers under this lock, flushFirstFrame drains-and-publishes
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
        // Not atomic with the setup below -- this assumes start()/stop() are
        // only ever invoked serially from a single (UI) thread, same as the
        // rest of this class's public surface. Two concurrent start() calls
        // could both pass this check.
        if (running.get()) return false
        status.set(0)
        _liveStatus.value = 0
        armed.set(false)
        synchronized(anchors) { anchors.clear() }
        synchronized(preroll) { preroll.clear(); prerollSamples = 0L }
        frame0BootNs = 0L
        firstBridge = null
        pendingSuspectBridge = null
        firstFrameTrimFrames = 0L
        firstFrameHandled = false
        pendingTrimSamples = 0L
        offsetNs = 0L
        driftPpm = 0
        writer = null
        this.wavFile = wavFile
        sourceIsRealtime = cameraSourceIsRealtime
        // A corrupt persisted float (NaN) would otherwise survive coerceIn
        // (NaN.coerceIn(..) is NaN) and silently produce an all-zero, never-
        // clipping WAV -- fall back to unity gain instead.
        val safeGainDb = if (gainDb.isFinite()) gainDb else 0f
        val clampedGainDb = safeGainDb.coerceIn(GAIN_DB_MIN, GAIN_DB_MAX)
        gainLinear = 10.0.pow(clampedGainDb.toDouble() / 20.0).toFloat()

        if (!hasPermission()) {
            addStatus(AudioStatus.PERMISSION_DENIED)
            return false
        }

        val target = AudioDeviceCatalog.resolve(listInputs(), deviceKey)
        channels = target?.preferredChannels ?: 1
        val channelMask =
            if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val rec = openRecord(channelMask)
        if (rec == null) {
            addStatus(AudioStatus.OPEN_FAILED)
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
                addStatus(AudioStatus.OPEN_FAILED)
                releaseRecord()
                false
            } else {
                readDone.set(false)
                running.set(true)
                armed.set(true)
                startThreads()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            addStatus(AudioStatus.OPEN_FAILED)
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
                    addStatus(AudioStatus.PROCESSED_SOURCE)
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
        var readFailed = false
        try {
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    Log.e(TAG, "AudioRecord.read error $n")
                    addStatus(AudioStatus.ENDED_EARLY)
                    readFailed = true
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
                    if (a >= CLIP_THRESHOLD) clipped = true
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

                if (!queue.offer(buf.copyOf(n))) addStatus(AudioStatus.OVERRUNS)

                val now = SystemClock.elapsedRealtimeNanos()
                if (now >= nextAnchorAt) {
                    nextAnchorAt = now + ANCHOR_INTERVAL_NS
                    sampleClocks(rec, ts)
                }
            }
            // Drain whatever the framework captured but never handed over.
            //
            // This is the tail-truncation fix. The loop above exits the moment
            // `running` goes false, but AudioRecord is still recording at that
            // instant and still holds everything it has captured since the last
            // full read. stop() then calls AudioRecord.stop(), which discards it.
            // With READ_SAMPLES at 4096 the orphaned amount is anything up to one
            // chunk -- 85ms at 48kHz -- which is why a take lost ~46ms and its
            // final video frame came out silent.
            //
            // Non-blocking on purpose: this runs while the device is still live,
            // so a blocking read would sit waiting for audio that is never coming
            // and turn a clean stop into a join timeout. NON_BLOCKING returns what
            // is there and 0 when the buffer is empty, which is the exit condition.
            //
            // Skipped when the loop exited on an error: a recorder that just
            // failed a read is not one to keep reading from.
            if (!readFailed) {
                var guard = 0
                while (guard++ < DRAIN_MAX_READS) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                    if (n <= 0) break
                    val g = gainLinear
                    for (i in 0 until n) buf[i] = buf[i] * g
                    if (!queue.offer(buf.copyOf(n))) {
                        addStatus(AudioStatus.OVERRUNS)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a read-path fault escape this thread -- it must not
            // touch the video path. Mark the take degraded and wind down.
            Log.e(TAG, "audio read loop failed", e)
            addStatus(AudioStatus.ENDED_EARLY)
        } finally {
            // Prompt shutdown of the write side even if stop() has not been
            // called yet; harmless if it already was. readDone must be set after
            // the drain above, never before, or the writer can leave early again.
            running.set(false)
            readDone.set(true)
        }
    }

    /**
     * One clock-bridge reading plus one converter anchor. Suspend shows up as a
     * moved bridge and invalidates the take's sync claim, rather than silently
     * shifting every timestamp by the sleep duration.
     *
     * Latching requires TWO consecutive out-of-tolerance readings (one second
     * apart, at [ANCHOR_INTERVAL_NS]) rather than one: sampleClocks' own
     * back-to-back System.nanoTime()/elapsedRealtimeNanos() reads can be
     * preempted by the scheduler between them, which looks identical to a real
     * suspend for exactly one sample -- and this project pins five worker
     * threads to big cores during 4K capture, making that preemption plausible
     * over a multi-minute take. A genuine suspend keeps the gap moved on every
     * later sample (it does not self-correct), so requiring one confirming
     * reading before latching filters the scheduler-jitter false positive
     * without meaningfully delaying detection of a real one.
     */
    private fun sampleClocks(rec: AudioRecord, ts: AudioTimestamp) {
        val bridge = ClockBridge(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
        val first = firstBridge
        if (first == null) {
            firstBridge = bridge
        } else if (AvSync.suspendDetected(first, bridge)) {
            if (pendingSuspectBridge != null) {
                addStatus(AudioStatus.SUSPENDED)
            } else {
                pendingSuspectBridge = bridge
            }
        } else {
            pendingSuspectBridge = null
        }
        if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
            synchronized(anchors) { anchors.add(AudioAnchor(ts.framePosition, ts.nanoTime)) }
        }
    }

    private fun writeLoop() {
        while (true) {
            // First-frame handoff: onFirstFrame (camera capture-callback thread)
            // only computes the trim and publishes frame0BootNs; the actual
            // WavWriter creation and the (potentially ~576 KB) preroll flush run
            // here instead, off the camera thread. Checked every iteration so it
            // fires promptly even if the queue happens to be momentarily empty.
            if (!firstFrameHandled && frame0BootNs != 0L) flushFirstFrame()

            val chunk = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (chunk == null) {
                // readDone, not !running: `running` goes false at the START of shutdown,
                // while the read thread still has an in-flight read and a drain to go.
                // Waiting on readDone is what lets the tail actually reach the file.
                if (readDone.get() && queue.isEmpty()) break
                continue
            }

            // Decide preroll-vs-stream atomically with flushFirstFrame's drain+
            // publish (same monitor), so a chunk can never be added to preroll
            // after the drain has already run.
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
                    appendTrimmed(target, chunk)
                } catch (e: Exception) {
                    Log.e(TAG, "WAV append failed", e)
                    addStatus(AudioStatus.ENDED_EARLY)
                    running.set(false)
                    break
                }
            }
        }
    }

    /** Applies any still-owed [pendingTrimSamples] (Critical 1's carried
     * residual -- a trim larger than the whole buffered preroll) to the front
     * of [chunk] before appending what's left to [target]. Only ever called
     * from writeLoop: pendingTrimSamples is set once by [flushFirstFrame]
     * (under the preroll lock, before writer is published) and only read or
     * decremented afterwards, by this same thread, so no locking is needed
     * here. */
    private fun appendTrimmed(target: WavWriter, chunk: FloatArray) {
        val toDrop = pendingTrimSamples
        if (toDrop <= 0L) {
            target.append(chunk, chunk.size)
            return
        }
        if (toDrop >= chunk.size) {
            pendingTrimSamples = toDrop - chunk.size
            return
        }
        val keep = chunk.copyOfRange(toDrop.toInt(), chunk.size)
        pendingTrimSamples = 0L
        target.append(keep, keep.size)
    }

    /** Holds audio until frame 0's timestamp arrives. Capped, because an
     * onFirstFrame that never comes must not grow this without limit.
     * Caller must hold the [preroll] monitor. */
    private fun addToPrerollLocked(chunk: FloatArray) {
        preroll.addLast(chunk)
        prerollSamples += chunk.size
        while (prerollSamples > MAX_PREROLL_SAMPLES && preroll.isNotEmpty()) {
            prerollSamples -= preroll.removeFirst().size
            addStatus(AudioStatus.OVERRUNS)
        }
    }

    /**
     * Supplies frame 0's SENSOR_TIMESTAMP and computes the head trim. Only the
     * first call has any effect, since only the first frame defines t=0.
     *
     * Deliberately cheap: this runs on the Camera2 capture-callback thread (see
     * CameraController.captureCallback) and must stay a near-no-op after the
     * first frame -- one boolean/field read, no allocation, no logging on the
     * success path. All the actual I/O (opening the WavWriter and flushing the
     * buffered preroll) happens on the audio write thread instead; see
     * [flushFirstFrame], which [writeLoop] invokes once it observes
     * [frame0BootNs] published below.
     *
     * Synchronized so two near-simultaneous calls can never both pass the
     * "not armed yet" guard and publish two different trims.
     */
    @Synchronized
    fun onFirstFrame(sensorTimestampNs: Long) {
        if (!running.get() || frame0BootNs != 0L) return

        // AvSync is pure math with no internal guards (by design -- see its own
        // review notes): a degenerate sampleRate could throw or yield NaN/Infinity.
        // sampleRate is pinned to the 48000 constant so this should never happen,
        // but this method runs on the video capture path, so nothing from AvSync
        // may ever propagate out of it regardless.
        try {
            val bridge = firstBridge
                ?: ClockBridge(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
            val f0 = AvSync.toBootNs(sensorTimestampNs, sourceIsRealtime, bridge)

            val anchor = synchronized(anchors) { anchors.firstOrNull() }
            val trimFrames: Long
            if (anchor != null) {
                val sample0 = AvSync.sample0BootNs(anchor, sampleRate)
                offsetNs = f0 - sample0
                trimFrames = AvSync.trimSamples(f0, sample0, sampleRate)
            } else {
                // No anchor yet means getTimestamp() has never succeeded on this
                // device. Applying no correction is the right POLICY (fall back
                // to offset 0 rather than a guessed-wrong one) -- but asserting a
                // sync we did not earn is not, so flag it (Important 10).
                offsetNs = 0L
                trimFrames = 0L
                addStatus(AudioStatus.ALIGNMENT_UNVERIFIED)
            }

            // A trim this large cannot be real: the preroll physically cannot
            // hold more than MAX_PREROLL_SAMPLES / channels frames. Anything
            // past that points at a bad clock-bridge assumption (e.g.
            // SENSOR_INFO_TIMESTAMP_SOURCE reporting UNKNOWN when the vendor
            // actually emits boottime, adding a multi-hour uptime offset) --
            // applying it would silently discard the whole preroll with no sign
            // anything went wrong. No trim beats a nonsense one.
            firstFrameTrimFrames = if (abs(trimFrames) > MAX_PREROLL_SAMPLES / channels) {
                addStatus(AudioStatus.ALIGNMENT_UNVERIFIED)
                // offsetNs was set above from the same bad clock-bridge
                // assumption that produced this implausible trimFrames, and
                // is written verbatim to the header's audioOffsetNs -- a
                // consumer that checks the status bit is fine, but one that
                // just applies the offset would get a multi-hour shift.
                // Zero it here so the header carries a harmless value rather
                // than a trap.
                offsetNs = 0L
                0L
            } else {
                trimFrames
            }
            // Publish LAST: this is writeLoop's signal that the trim decision is
            // ready and it may proceed to open the writer and flush the preroll.
            frame0BootNs = f0
        } catch (e: Exception) {
            Log.e(TAG, "AvSync computation failed", e)
            addStatus(AudioStatus.ENDED_EARLY)
            running.set(false)
        }
    }

    /**
     * Runs on the audio write thread, exactly once per take, as soon as
     * [onFirstFrame] has published its trim decision: opens the WavWriter and
     * flushes the buffered preroll through [AvSync.planPrerollTrim]. Never runs
     * on the camera capture-callback thread -- see [onFirstFrame]'s kdoc for why
     * that matters (frame 0's metadata push must not race a ~576 KB disk write).
     */
    private fun flushFirstFrame() {
        firstFrameHandled = true
        val f = wavFile ?: return
        val w = try {
            WavWriter(f, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "could not open WAV", e)
            addStatus(AudioStatus.ENDED_EARLY)
            running.set(false)
            return
        }

        // Every append() here can throw (disk full, I/O fault); none of it may
        // escape this thread. On failure writer is left null: the rest of
        // writeLoop keeps buffering into preroll (harmless, capped) until it
        // observes running == false and exits.
        try {
            synchronized(preroll) {
                val plan = AvSync.planPrerollTrim(preroll.map { it.size }, firstFrameTrimFrames, channels)
                if (plan.padSamples > 0) {
                    addStatus(AudioStatus.PADDED)
                    var pad = plan.padSamples
                    val silence = FloatArray(PAD_CHUNK)
                    while (pad > 0) {
                        val n = minOf(pad, PAD_CHUNK.toLong()).toInt()
                        w.append(silence, n)
                        pad -= n
                    }
                }
                repeat(plan.dropChunkCount) { preroll.removeFirst() }
                if (plan.partialDropSamples > 0) {
                    val c = preroll.removeFirst()
                    val keep = c.copyOfRange(plan.partialDropSamples, c.size)
                    w.append(keep, keep.size)
                }
                while (preroll.isNotEmpty()) {
                    val c = preroll.removeFirst()
                    w.append(c, c.size)
                }
                prerollSamples = 0
                // Carry any trim the preroll itself couldn't cover (Critical 1):
                // previously this was silently discarded, leaving the WAV
                // effectively untrimmed with no sign anything was wrong.
                // appendTrimmed drops it from the start of subsequently
                // streamed chunks instead.
                pendingTrimSamples = plan.residualSamples
                // Publish while still holding the lock: from this point on the
                // rest of writeLoop, not this method, owns append() on w.
                writer = w
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV preroll flush failed", e)
            addStatus(AudioStatus.ENDED_EARLY)
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
        // armed is only true once start() has actually started the pipeline,
        // and only stop() ever clears it -- see the field comment. This is
        // deliberately NOT `running`: an internal failure (read error, append
        // failure, AvSync fault) flips running false long before stop() is
        // called, and that is exactly the case that most needs the cleanup
        // below (joins, close, release) to still run.
        if (!armed.getAndSet(false)) {
            _meter.value = MeterLevels()
            return result(present = false)
        }
        running.set(false)

        // Ordering here is a compromise between two requirements that pull
        // opposite ways.
        //
        // AudioRecord.stop() is the framework's documented way to unblock a
        // thread parked in a blocking read(), so calling it first keeps a wedged
        // HAL from holding the join open. But it also throws away everything the
        // device has captured and not yet handed over -- which is precisely the
        // tail this take needs, and precisely what used to go missing.
        //
        // So: give the read thread a bounded window to finish its in-flight read
        // and run its drain while the device is still live, and only reach for
        // stop() if it is still parked after that. The healthy path keeps its
        // tail; the wedged path still gets unblocked.
        readThread?.join(DRAIN_JOIN_MS)
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }

        readThread?.join(THREAD_JOIN_MS)
        // Fallback: if the read thread is wedged and never reached its finally, the
        // writer would otherwise wait on readDone forever. Setting it here bounds that,
        // and is a no-op on every healthy stop.
        readDone.set(true)
        writeThread?.join(THREAD_JOIN_MS)
        // Thread.join(timeout) returns whether or not the thread actually
        // terminated -- it only carries a happens-before guarantee when it
        // did. Must check before treating either thread as safely dead.
        val readStuck = readThread?.isAlive == true
        val writeStuck = writeThread?.isAlive == true
        readThread = null
        writeThread = null

        val snapshot = synchronized(anchors) { ArrayList(anchors) }
        driftPpm = try {
            AvSync.driftPpm(snapshot, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "driftPpm computation failed", e)
            0
        }
        if (abs(driftPpm) > DRIFT_WARN_PPM) addStatus(AudioStatus.DRIFT_HIGH)

        // No first frame ever arrived: the take produced no aligned audio at all.
        if (writer == null) addStatus(AudioStatus.ENDED_EARLY)

        if (writeStuck) {
            // The write thread never returned from a blocked append()/close()
            // within the join timeout. WavWriter has no internal
            // synchronization, so calling close() here would be an unguarded
            // concurrent access racing that still-live append() on the same
            // BufferedOutputStream/RandomAccessFile. Leave the file with its
            // placeholder RIFF/data sizes instead -- WavWriter.repairIfTruncated
            // exists precisely to recover a file left in this state, which
            // beats risking corruption.
            Log.e(TAG, "audio write thread did not terminate within ${THREAD_JOIN_MS}ms; skipping close to avoid racing a live append()")
            addStatus(AudioStatus.ENDED_EARLY)
        } else {
            try {
                writer?.close(buildBext())
            } catch (e: Exception) {
                Log.e(TAG, "WAV close failed", e)
                addStatus(AudioStatus.ENDED_EARLY)
            }
        }
        writer = null

        if (readStuck) {
            // The read thread never returned even after AudioRecord.stop() --
            // the framework's documented unblock mechanism for a pending
            // read(). That points to a wedged HAL/driver, not an ordinary
            // blocked read waiting on the next buffer. There is no further
            // safe mechanism available here: calling release() while a
            // read() may genuinely still be executing in native code risks a
            // process fault, not just a catchable exception, so it is not
            // called. record is dropped from this field (so no other path
            // touches it again) but the underlying AudioRecord is never
            // released -- it leaks for this take, and the mic may report
            // busy on the next start(). This tradeoff (leak over crash) is
            // deliberate; see task-6-report.md.
            Log.e(TAG, "audio read thread did not terminate after AudioRecord.stop(); leaking AudioRecord rather than racing release() against a possibly-live read()")
            addStatus(AudioStatus.ENDED_EARLY)
            record = null
        } else {
            try {
                record?.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.release failed", e)
            }
            record = null
        }
        _meter.value = MeterLevels()

        val produced = wavFile?.let { it.exists() && it.length() > WavWriter.HEADER_BYTES } ?: false
        return result(present = produced)
    }

    /** Sets [bit] in [status] atomically and republishes it via [liveStatus].
     * status is mutated from four different threads (read, write, camera
     * capture-callback, and stop()'s caller); getAndUpdate/updateAndGet is what
     * actually makes the `or` atomic -- see the [status] field comment. */
    private fun addStatus(bit: Int) {
        _liveStatus.value = status.updateAndGet { it or bit }
    }

    private fun buildBext(): BextInfo {
        val now = Date()
        return BextInfo(
            description = "RawCam offsetNs=$offsetNs driftPpm=$driftPpm status=${status.get()} " +
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
        status = status.get(),
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

        /** Bound on the post-stop drain. Each pass takes at most one buffer's
         *  worth, so this cannot spin: it exists only so a misbehaving HAL that
         *  keeps returning data forever cannot hold the stop path open. */
        private const val DRAIN_MAX_READS = 8

        /** How long stop() lets the read thread finish its in-flight read and
         *  drain before falling back to AudioRecord.stop() to unblock it. One
         *  blocking read is READ_SAMPLES/sampleRate -- 85ms at 48kHz -- so this
         *  clears the ordinary case several times over. */
        private const val DRAIN_JOIN_MS = 400L
        private const val ANCHOR_INTERVAL_NS = 1_000_000_000L
        private const val PAD_CHUNK = 4096
        private const val DRIFT_WARN_PPM = 100
        private const val GAIN_DB_MIN = -20.0f
        private const val GAIN_DB_MAX = 30.0f

        // Spec: latch the clip indicator at or above -0.1 dBFS, not only at a
        // literal 1.0f. With ENCODING_PCM_FLOAT, a 16-bit-backed source at
        // digital full scale normalizes to 32767/32768 = 0.99997f -- under
        // 1.0f, so a >= 1.0f comparison never latches on genuinely clipped
        // 16-bit-sourced input. 0.98855f == 10^(-0.1/20).
        private const val CLIP_THRESHOLD = 0.98855f

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
