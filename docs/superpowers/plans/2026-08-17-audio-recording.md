# Audio Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record production-quality audio alongside RAW video, delivered as a sidecar WAV whose sample 0 aligns with frame 0's sensor timestamp, and copied next to the DNG sequence on export.

**Architecture:** A self-contained Kotlin audio subsystem (`app/src/main/java/com/shez/rawcam/audio/`) owns `AudioRecord`, a two-thread read/write pipeline, and its own file descriptor. It never touches `capture.cpp`, so the bandwidth-bound frame path is unchanged. The `.rawv` header bumps to v5 with audio fields carved from `reserved[284]`; the record layout is untouched, and the only native reader change is relaxing a strict version gate.

**Tech Stack:** Kotlin, Jetpack Compose, `android.media.AudioRecord` / `AudioManager`, DataStore preferences, JUnit 4 (`app/src/test`), C++17 + doctest under ctest (`core/tests`), CMake, JNI.

**Spec:** `docs/superpowers/specs/2026-08-17-audio-recording-design.md` (commit `29977b1`)

## Global Constraints

- Sample rate is **pinned at 48000 Hz**. No user-facing option.
- Channels: **stereo when the selected device reports 2 channels, else mono**. No user-facing option.
- On-disk audio is **24-bit little-endian PCM**, interleaved. Gain is applied in float before conversion.
- Audio source preference order: `MediaRecorder.AudioSource.UNPROCESSED` -> `VOICE_RECOGNITION` -> `MIC`.
- **Bluetooth inputs are excluded** from the device picker (`TYPE_BLUETOOTH_SCO`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET`).
- **Video always wins.** No audio failure may stop, fail, or shorten a RAW recording.
- `capture.cpp` is **not modified**, except for the single pass-through method in Task 4. Any other change to it is a plan violation.
- `.rawv` version becomes **5**; the reader accepts **4 or 5**.
- `audioGainDb` clamps to **-20.0 .. +30.0**.
- Existing code style: ASCII only (`--` for em-dashes in comments), 4-space Kotlin indent, 2-space C++ indent.

---

## File Structure

**Create:**
- `app/src/main/java/com/shez/rawcam/audio/AvSync.kt` -- pure clock/trim/drift math
- `app/src/main/java/com/shez/rawcam/audio/WavWriter.kt` -- streaming RIFF writer + bext + repair
- `app/src/main/java/com/shez/rawcam/audio/AudioDeviceCatalog.kt` -- input enumeration and persisted-key resolution
- `app/src/main/java/com/shez/rawcam/audio/AudioRecorder.kt` -- AudioRecord ownership, threads, gain, meter, orchestration
- `app/src/main/java/com/shez/rawcam/ui/AudioMeter.kt` -- meter composable
- `app/src/test/java/com/shez/rawcam/audio/AvSyncTest.kt`
- `app/src/test/java/com/shez/rawcam/audio/WavWriterTest.kt`
- `app/src/test/java/com/shez/rawcam/audio/AudioDeviceCatalogTest.kt`
- `app/src/test/java/com/shez/rawcam/settings/AudioSettingsDefaultsTest.kt`
- `core/tests/test_rawv_audio_header.cpp`

**Modify:**
- `core/include/rawcam/rawv.h` -- v5 fields, `kVersion` 4->5, `AudioInfo`, status bits
- `core/include/rawcam/rawv_writer.h`, `core/src/rawv_writer.cpp` -- `setAudioInfo`
- `core/src/rawv_reader.cpp:84` -- version gate
- `app/src/main/cpp/capture.h`, `capture.cpp` -- pass-through only (Task 4)
- `app/src/main/cpp/jni_bridge.cpp` -- `nativeSetAudioInfo`
- `app/src/main/java/com/shez/rawcam/NativeBridge.kt` -- declaration
- `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt` -- 3 fields
- `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt` -- AUDIO section
- `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt` -- meter wiring, KEEP_SCREEN_ON, settings pass-through
- `app/src/main/java/com/shez/rawcam/camera/CameraController.kt` -- start/stop integration, first-frame hook
- `app/src/main/java/com/shez/rawcam/export/ExportService.kt` -- WAV copy + paired delete
- `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt` -- badge, paired delete/share
- `app/src/main/java/com/shez/rawcam/MainActivity.kt` -- permission launcher, input supplier
- `app/src/main/AndroidManifest.xml` -- `RECORD_AUDIO`

> **Two corrections to the spec, both discovered while reading the real code.**
>
> **1. The head trim happens at START, not at finalize.** Spec section 3 shows the WAV being trimmed when recording stops. That is not implementable without rewriting the file, because RIFF data begins at a fixed offset. Instead `AudioRecorder` buffers audio in memory until frame 0's `SENSOR_TIMESTAMP` arrives, applies the trim to that prefix, then streams to disk. Audio arms before session configuration, so the buffered prefix stays well under a second (~300 KB).
>
> **2. `nativeSetAudioInfo` must precede `nativeStopRecording`.** The latter finalizes the header, so audio provenance published after it would be silently discarded. Camera quiesce still happens first, unchanged.
>
> Amend the spec to match before executing.

---

### Task 1: A/V sync math (pure)

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/audio/AvSync.kt`
- Test: `app/src/test/java/com/shez/rawcam/audio/AvSyncTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ClockBridge(monotonicNs: Long, bootNs: Long)` with `offsetNs: Long`; `AudioAnchor(framePosition: Long, bootNs: Long)`; object `AvSync` with `toBootNs(sensorTimestampNs: Long, sourceIsRealtime: Boolean, bridge: ClockBridge): Long`, `sample0BootNs(anchor: AudioAnchor, sampleRate: Int): Long`, `trimSamples(frame0BootNs: Long, audioSample0BootNs: Long, sampleRate: Int): Long`, `driftPpm(anchors: List<AudioAnchor>, sampleRate: Int): Int`, `suspendDetected(first: ClockBridge, latest: ClockBridge): Boolean`, `const val SUSPEND_TOLERANCE_NS = 5_000_000L`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/audio/AvSyncTest.kt`:

```kotlin
package com.shez.rawcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncTest {

    // Monotonic reads 1e9; boottime reads 6e9 -- the device spent 5s suspended
    // at some point before this take began.
    private val bridge = ClockBridge(monotonicNs = 1_000_000_000L, bootNs = 6_000_000_000L)

    @Test
    fun `offsetNs is boot minus monotonic`() {
        assertEquals(5_000_000_000L, bridge.offsetNs)
    }

    @Test
    fun `REALTIME sensor timestamps are already boottime`() {
        assertEquals(6_500_000_000L, AvSync.toBootNs(6_500_000_000L, true, bridge))
    }

    @Test
    fun `UNKNOWN sensor timestamps are monotonic and get bridged`() {
        assertEquals(6_500_000_000L, AvSync.toBootNs(1_500_000_000L, false, bridge))
    }

    @Test
    fun `sample0 is anchor time minus the anchor's own position`() {
        // Sample 48000 hit the converter at boot=7e9; at 48kHz that is 1s after sample 0.
        val anchor = AudioAnchor(framePosition = 48_000L, bootNs = 7_000_000_000L)
        assertEquals(6_000_000_000L, AvSync.sample0BootNs(anchor, 48_000))
    }

    @Test
    fun `audio started first yields a positive trim`() {
        // Audio sample 0 at 6.0s, frame 0 at 6.25s -> discard 0.25s = 12000 frames.
        assertEquals(12_000L, AvSync.trimSamples(6_250_000_000L, 6_000_000_000L, 48_000))
    }

    @Test
    fun `audio started late yields a negative trim meaning pad`() {
        assertEquals(-12_000L, AvSync.trimSamples(6_000_000_000L, 6_250_000_000L, 48_000))
    }

    @Test
    fun `perfect clock has zero drift`() {
        val anchors = listOf(
            AudioAnchor(0L, 0L),
            AudioAnchor(48_000L, 1_000_000_000L),
            AudioAnchor(96_000L, 2_000_000_000L),
        )
        assertEquals(0, AvSync.driftPpm(anchors, 48_000))
    }

    @Test
    fun `slow mic clock yields positive ppm`() {
        // Wall time runs 100ppm longer than the sample count implies.
        val anchors = listOf(
            AudioAnchor(0L, 0L),
            AudioAnchor(48_000L, 1_000_100_000L),
            AudioAnchor(96_000L, 2_000_200_000L),
        )
        assertEquals(100, AvSync.driftPpm(anchors, 48_000))
    }

    @Test
    fun `fewer than two anchors reports no drift`() {
        assertEquals(0, AvSync.driftPpm(listOf(AudioAnchor(0L, 0L)), 48_000))
        assertEquals(0, AvSync.driftPpm(emptyList(), 48_000))
    }

    @Test
    fun `a stable bridge is not a suspend`() {
        val later = ClockBridge(monotonicNs = 2_000_000_000L, bootNs = 7_000_000_000L)
        assertFalse(AvSync.suspendDetected(bridge, later))
    }

    @Test
    fun `a moved bridge is a suspend`() {
        // 3s of sleep: boottime advanced 3s more than monotonic did.
        val later = ClockBridge(monotonicNs = 2_000_000_000L, bootNs = 10_000_000_000L)
        assertTrue(AvSync.suspendDetected(bridge, later))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.AvSyncTest"`
Expected: FAIL -- unresolved reference `ClockBridge`, `AudioAnchor`, `AvSync`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/shez/rawcam/audio/AvSync.kt`:

```kotlin
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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.AvSyncTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/audio/AvSync.kt app/src/test/java/com/shez/rawcam/audio/AvSyncTest.kt
git commit -m "feat: A/V sync math -- clock bridge, head trim, drift ppm"
```

---

### Task 2: Streaming WAV writer

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/audio/WavWriter.kt`
- Test: `app/src/test/java/com/shez/rawcam/audio/WavWriterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class BextInfo(description: String, originationDate: String, originationTime: String, timeReferenceSamples: Long)`; `class WavWriter(file: File, sampleRate: Int, channels: Int) : Closeable` with `append(samples: FloatArray, count: Int)`, `close(bext: BextInfo?)`, `override fun close()`; companion `repairIfTruncated(file: File): Boolean`, `HEADER_BYTES: Int = 654`, `BEXT_PAYLOAD_BYTES: Int = 602`.

Fixed byte layout -- every chunk sits at a known offset, which is what makes close-time patching possible:

| Offset | Size | Content |
|---|---|---|
| 0 | 4 | `RIFF` |
| 4 | 4 | riff size = fileLength - 8 (patched at close) |
| 8 | 4 | `WAVE` |
| 12 | 4 | `fmt ` |
| 16 | 4 | 16 |
| 20 | 2 | audioFormat = 1 (PCM) |
| 22 | 2 | channels |
| 24 | 4 | sampleRate |
| 28 | 4 | byteRate = sampleRate * channels * 3 |
| 32 | 2 | blockAlign = channels * 3 |
| 34 | 2 | bitsPerSample = 24 |
| 36 | 4 | `bext` |
| 40 | 4 | 602 |
| 44 | 602 | BWF v0 payload (zeroed at open, patched at close) |
| 646 | 4 | `data` |
| 650 | 4 | data size (patched at close) |
| 654 | ... | 24-bit LE interleaved PCM |

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/audio/WavWriterTest.kt`:

```kotlin
package com.shez.rawcam.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(f: File) = f.readBytes()

    private fun u32(b: ByteArray, at: Int): Long =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun u16(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)

    private val bext = BextInfo(
        description = "RawCam test",
        originationDate = "2026-08-17",
        originationTime = "12:00:00",
        timeReferenceSamples = 48_000L,
    )

    @Test
    fun `header fields land at the documented offsets`() {
        val f = tmp.newFile("a.wav")
        WavWriter(f, 48_000, 2).use { it.append(FloatArray(4), 4) }
        val b = bytes(f)
        assertEquals("RIFF", ascii(b, 0, 4))
        assertEquals("WAVE", ascii(b, 8, 4))
        assertEquals("fmt ", ascii(b, 12, 4))
        assertEquals(16L, u32(b, 16))
        assertEquals(1, u16(b, 20))
        assertEquals(2, u16(b, 22))
        assertEquals(48_000L, u32(b, 24))
        assertEquals(48_000L * 2 * 3, u32(b, 28))
        assertEquals(6, u16(b, 32))
        assertEquals(24, u16(b, 34))
        assertEquals("bext", ascii(b, 36, 4))
        assertEquals(602L, u32(b, 40))
        assertEquals("data", ascii(b, 646, 4))
        assertEquals(654, WavWriter.HEADER_BYTES)
    }

    @Test
    fun `sizes are patched on close`() {
        val f = tmp.newFile("b.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(6), 6) }
        val b = bytes(f)
        assertEquals(18L, u32(b, 650))
        assertEquals((b.size - 8).toLong(), u32(b, 4))
        assertEquals(WavWriter.HEADER_BYTES + 18, b.size)
    }

    @Test
    fun `full scale positive and negative encode to 24-bit LE`() {
        val f = tmp.newFile("c.wav")
        WavWriter(f, 48_000, 1).use { it.append(floatArrayOf(1.0f, -1.0f, 0.0f), 3) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 9)
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x7F,   // +8388607
                0x00, 0x00, 0x80.toByte(),            // -8388608
                0x00, 0x00, 0x00,
            ),
            pcm,
        )
    }

    @Test
    fun `out of range input is clamped not wrapped`() {
        val f = tmp.newFile("d.wav")
        WavWriter(f, 48_000, 1).use { it.append(floatArrayOf(4.0f, -4.0f), 2) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 6)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte()),
            pcm,
        )
    }

    @Test
    fun `stereo samples stay interleaved in submission order`() {
        val f = tmp.newFile("e.wav")
        WavWriter(f, 48_000, 2).use { it.append(floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f), 4) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 12)
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x7F,
            ),
            pcm,
        )
    }

    @Test
    fun `count shorter than the array writes only count samples`() {
        val f = tmp.newFile("f.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(100), 5) }
        assertEquals(15L, u32(bytes(f), 650))
    }

    @Test
    fun `bext payload is written at the documented offsets`() {
        val f = tmp.newFile("g.wav")
        WavWriter(f, 48_000, 1).use {
            it.append(FloatArray(2), 2)
            it.close(bext)
        }
        val b = bytes(f)
        assertEquals("RawCam test", ascii(b, 44, 11))
        assertEquals("2026-08-17", ascii(b, 44 + 320, 10))
        assertEquals("12:00:00", ascii(b, 44 + 330, 8))
        assertEquals(
            48_000L,
            ByteBuffer.wrap(b, 44 + 338, 8).order(ByteOrder.LITTLE_ENDIAN).long,
        )
        assertEquals(0, u16(b, 44 + 346))
        assertEquals(602, WavWriter.BEXT_PAYLOAD_BYTES)
    }

    @Test
    fun `an oversized description is truncated rather than overflowing`() {
        val f = tmp.newFile("h.wav")
        WavWriter(f, 48_000, 1).use {
            it.append(FloatArray(2), 2)
            it.close(bext.copy(description = "x".repeat(400)))
        }
        val b = bytes(f)
        // Description is 256 bytes; byte 256 starts Originator and must be untouched by it.
        assertEquals("x".repeat(256), ascii(b, 44, 256))
        assertEquals("RawCam", ascii(b, 44 + 256, 6))
    }

    @Test
    fun `repair recovers a truncated file's sizes`() {
        val f = tmp.newFile("i.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(10), 10) }
        // Simulate a process kill: both size fields left zero, as a never-closed file has.
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(4); raf.write(ByteArray(4))
            raf.seek(650); raf.write(ByteArray(4))
        }
        assertTrue(WavWriter.repairIfTruncated(f))
        val b = bytes(f)
        assertEquals(30L, u32(b, 650))
        assertEquals((b.size - 8).toLong(), u32(b, 4))
    }

    @Test
    fun `repair leaves an already valid file alone`() {
        val f = tmp.newFile("j.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(10), 10) }
        val before = bytes(f)
        assertTrue(WavWriter.repairIfTruncated(f))
        assertArrayEquals(before, bytes(f))
    }

    @Test
    fun `repair rejects a file that is not one of ours`() {
        val f = tmp.newFile("k.wav")
        f.writeBytes(ByteArray(32))
        assertFalse(WavWriter.repairIfTruncated(f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.WavWriterTest"`
Expected: FAIL -- unresolved reference `WavWriter`, `BextInfo`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/shez/rawcam/audio/WavWriter.kt`:

```kotlin
package com.shez.rawcam.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contents of the BWF `bext` chunk. Written so a WAV separated from its `.rawv`
 * still carries its own sync provenance -- the sidecar design's one real
 * weakness. Strings are ASCII and are truncated (never overflowed) into their
 * fixed-width fields.
 */
data class BextInfo(
    val description: String,
    val originationDate: String,   // YYYY-MM-DD
    val originationTime: String,   // HH:MM:SS
    val timeReferenceSamples: Long,
)

/**
 * Streaming 24-bit PCM RIFF/WAVE writer.
 *
 * Every chunk sits at a fixed offset, so the two size fields and the whole
 * `bext` payload can be patched in place at close. That is what lets the caller
 * supply provenance only known once recording has ended, without buffering the
 * audio or rewriting the file.
 *
 * Head trimming is deliberately NOT this class's job: RIFF data begins at a
 * fixed offset, so removing leading samples afterwards would mean rewriting the
 * whole file. AudioRecorder applies the trim before the first [append] instead.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val out = BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)
    private var dataBytes = 0L
    private var closed = false
    private val scratch = ByteArray(SCRATCH_SAMPLES * 3)

    init {
        out.write(buildHeader())
    }

    /**
     * Appends the first [count] entries of [samples] as interleaved 24-bit LE.
     * Input is nominal -1.0..+1.0 and is clamped, so post-gain overshoot
     * saturates the way an ADC would rather than wrapping to the opposite rail.
     */
    fun append(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(SCRATCH_SAMPLES, count - i)
            var b = 0
            for (k in 0 until n) {
                val v = samples[i + k]
                val clamped = if (v > 1.0f) 1.0f else if (v < -1.0f) -1.0f else v
                // Asymmetric full scale: +1.0 -> 0x7FFFFF, -1.0 -> -0x800000.
                val s = if (clamped >= 0f) (clamped * 8_388_607.0f).toInt()
                        else (clamped * 8_388_608.0f).toInt()
                scratch[b++] = (s and 0xFF).toByte()
                scratch[b++] = ((s shr 8) and 0xFF).toByte()
                scratch[b++] = ((s shr 16) and 0xFF).toByte()
            }
            out.write(scratch, 0, b)
            dataBytes += b
            i += n
        }
    }

    override fun close() = close(null)

    /** Flushes, then patches the RIFF/data sizes and, when [bext] is non-null,
     * the `bext` payload. Idempotent. */
    fun close(bext: BextInfo?) {
        if (closed) return
        closed = true
        try {
            out.flush()
        } finally {
            out.close()
        }
        RandomAccessFile(file, "rw").use { raf ->
            if (bext != null) {
                raf.seek(BEXT_PAYLOAD_OFFSET.toLong())
                raf.write(encodeBext(bext))
            }
            raf.seek(4)
            raf.write(le32((file.length() - 8).toInt()))
            raf.seek(DATA_SIZE_OFFSET.toLong())
            raf.write(le32(dataBytes.toInt()))
        }
    }

    private fun buildHeader(): ByteArray {
        val h = ByteArray(HEADER_BYTES)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(0)                       // patched at close
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                     // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 3)
        bb.putShort((channels * 3).toShort())
        bb.putShort(24)
        bb.put("bext".toByteArray(Charsets.US_ASCII))
        bb.putInt(BEXT_PAYLOAD_BYTES)
        bb.position(BEXT_PAYLOAD_OFFSET + BEXT_PAYLOAD_BYTES)  // payload stays zeroed
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(0)                       // patched at close
        return h
    }

    private fun encodeBext(info: BextInfo): ByteArray {
        val p = ByteArray(BEXT_PAYLOAD_BYTES)
        putAscii(p, 0, 256, info.description)
        putAscii(p, 256, 32, "RawCam")
        putAscii(p, 320, 10, info.originationDate)
        putAscii(p, 330, 8, info.originationTime)
        ByteBuffer.wrap(p, 338, 8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(info.timeReferenceSamples)
        ByteBuffer.wrap(p, 346, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0)  // BWF v0
        return p
    }

    /** Copies at most [len] ASCII bytes; the rest of the field stays NUL. */
    private fun putAscii(dst: ByteArray, at: Int, len: Int, s: String) {
        val src = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, dst, at, minOf(len, src.size))
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    companion object {
        const val BEXT_PAYLOAD_BYTES = 602
        private const val BEXT_PAYLOAD_OFFSET = 44
        private const val DATA_SIZE_OFFSET = 650
        const val HEADER_BYTES = 654
        private const val BUFFER_BYTES = 64 * 1024
        private const val SCRATCH_SAMPLES = 4096

        /**
         * Recovers the size fields of a WAV left behind by a killed process,
         * inferring the data size from the file length. Mirrors the
         * recover-by-scan approach `.rawv` already uses for `frameCount`.
         *
         * Returns true when the file is one of ours and now has correct sizes
         * (including when it was already correct), false when it is too short or
         * is not one of ours.
         */
        fun repairIfTruncated(file: File): Boolean {
            if (file.length() < HEADER_BYTES) return false
            return try {
                RandomAccessFile(file, "rw").use { raf ->
                    val tag = ByteArray(4)
                    raf.seek(0); raf.readFully(tag)
                    if (String(tag, Charsets.US_ASCII) != "RIFF") return false
                    raf.seek(HEADER_BYTES - 8L); raf.readFully(tag)
                    if (String(tag, Charsets.US_ASCII) != "data") return false

                    val actualData = (file.length() - HEADER_BYTES).toInt()
                    val actualRiff = (file.length() - 8).toInt()
                    raf.seek(DATA_SIZE_OFFSET.toLong())
                    val storedData = Integer.reverseBytes(raf.readInt())
                    raf.seek(4)
                    val storedRiff = Integer.reverseBytes(raf.readInt())
                    if (storedData == actualData && storedRiff == actualRiff) return true

                    raf.seek(4)
                    raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(actualRiff).array())
                    raf.seek(DATA_SIZE_OFFSET.toLong())
                    raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(actualData).array())
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.WavWriterTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/audio/WavWriter.kt app/src/test/java/com/shez/rawcam/audio/WavWriterTest.kt
git commit -m "feat: streaming 24-bit WAV writer with BWF bext and truncation repair"
```

---

### Task 3: `.rawv` v5 audio header fields

**Files:**
- Modify: `core/include/rawcam/rawv.h`
- Modify: `core/include/rawcam/rawv_writer.h`, `core/src/rawv_writer.cpp`
- Modify: `core/src/rawv_reader.cpp:84`
- Test: `core/tests/test_rawv_audio_header.cpp` (new; `core/CMakeLists.txt` globs `tests/*.cpp`, so no build-file change is needed)

**Interfaces:**
- Consumes: nothing.
- Produces: `kVersion == 5`, `kMinReadableVersion == 4`; `FileHeader` audio fields; `struct AudioInfo`; `kAudio*` status-bit constants; `RawvWriter::setAudioInfo(const AudioInfo&)`.

- [ ] **Step 1: Write the failing test**

Create `core/tests/test_rawv_audio_header.cpp`:

```cpp
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "rawcam/rawv.h"
#include "rawcam/rawv_reader.h"
#include "rawcam/rawv_writer.h"

using namespace rawcam;

namespace {

// Fills a header-sane fixture so version handling can be exercised directly.
FileHeader baseHeader(uint32_t version) {
  FileHeader h{};
  h.magic = kMagic;
  h.version = version;
  h.width = 4;
  h.height = 2;
  h.rowStrideBytes = 8;
  h.packMode = (uint32_t)PackMode::Raw16;
  h.cfa = (uint32_t)Cfa::RGGB;
  h.whiteLevel = 1023;
  h.fpsNum = 24;
  h.fpsDen = 1;
  h.frameSizeBytes = 16;
  h.illuminant1 = 21;
  std::snprintf(h.deviceName, sizeof h.deviceName, "fixture");
  return h;
}

std::string writeFixture(uint32_t version, uint32_t frames) {
  static int counter = 0;
  std::string path = "test_audio_hdr_" + std::to_string(counter++) + ".rawv";
  FileHeader h = baseHeader(version);
  std::FILE* f = std::fopen(path.c_str(), "wb");
  REQUIRE(f != nullptr);
  std::fwrite(&h, sizeof h, 1, f);
  std::vector<uint8_t> payload(h.frameSizeBytes, 0);
  for (uint32_t i = 0; i < frames; ++i) {
    FrameMeta m{};
    m.timestampNs = 1000 + i;
    m.frameIndex = i;
    m.payloadBytes = h.frameSizeBytes;
    std::fwrite(&m, sizeof m, 1, f);
    std::fwrite(payload.data(), payload.size(), 1, f);
  }
  std::fclose(f);
  return path;
}

}  // namespace

TEST_CASE("header is still exactly 512 bytes after adding audio fields") {
  CHECK(sizeof(FileHeader) == kHeaderSize);
  CHECK(sizeof(FrameMeta) == kFrameMetaSize);
}

TEST_CASE("version is 5 and 4 is still readable") {
  CHECK(kVersion == 5u);
  CHECK(kMinReadableVersion == 4u);
}

TEST_CASE("a v4 file opens and reports no audio") {
  std::string path = writeFixture(4, 3);
  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  CHECK(r->header().version == 4u);
  CHECK(r->header().audioPresent == 0u);
  CHECK(r->frameCount() == 3u);
  std::remove(path.c_str());
}

TEST_CASE("a v5 file opens") {
  std::string path = writeFixture(5, 2);
  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  CHECK(r->header().version == 5u);
  CHECK(r->frameCount() == 2u);
  std::remove(path.c_str());
}

TEST_CASE("an unknown future version is still rejected") {
  std::string path = writeFixture(6, 1);
  CHECK(RawvReader::open(path) == nullptr);
  std::remove(path.c_str());
}

TEST_CASE("a pre-v4 version is rejected") {
  std::string path = writeFixture(3, 1);
  CHECK(RawvReader::open(path) == nullptr);
  std::remove(path.c_str());
}

TEST_CASE("audio status bits are distinct single bits") {
  const uint32_t bits[] = {
      kAudioPermissionDenied, kAudioOpenFailed, kAudioEndedEarly,
      kAudioOverruns,         kAudioSuspended,  kAudioPadded,
      kAudioDriftHigh,        kAudioProcessedSource,
  };
  uint32_t seen = 0;
  for (uint32_t b : bits) {
    CHECK((b & (b - 1)) == 0u);   // exactly one bit set
    CHECK((seen & b) == 0u);      // no duplicates
    seen |= b;
  }
  CHECK(kAudioSyncInvalidating == (kAudioOverruns | kAudioSuspended | kAudioPadded));
}

TEST_CASE("writer stores audio info into the finalized header") {
  std::string path = "test_audio_hdr_write.rawv";
  FileHeader h = baseHeader(kVersion);

  auto w = RawvWriter::create(path, h);
  REQUIRE(w != nullptr);
  FrameMeta m{};
  m.timestampNs = 1000;
  m.payloadBytes = h.frameSizeBytes;
  std::vector<uint8_t> payload(h.frameSizeBytes, 0);
  REQUIRE(w->writeFrame(m, payload.data(), h.frameSizeBytes));

  AudioInfo ai{};
  ai.present = 1;
  ai.sampleRate = 48000;
  ai.channels = 2;
  ai.bitsPerSample = 24;
  ai.offsetNs = -1234567;   // signed, negative on purpose
  ai.driftPpm = -42;        // signed
  ai.timestampSource = 1;
  ai.status = kAudioOverruns;
  ai.source = 9;
  std::snprintf(ai.fileName, sizeof ai.fileName, "clip_x.wav");
  w->setAudioInfo(ai);
  REQUIRE(w->finalize());
  w.reset();

  auto r = RawvReader::open(path);
  REQUIRE(r != nullptr);
  const FileHeader& out = r->header();
  CHECK(out.audioPresent == 1u);
  CHECK(out.audioSampleRate == 48000u);
  CHECK(out.audioChannels == 2u);
  CHECK(out.audioBitsPerSample == 24u);
  CHECK(out.audioOffsetNs == -1234567);
  CHECK(out.audioDriftPpm == -42);
  CHECK(out.audioTimestampSource == 1u);
  CHECK(out.audioStatus == kAudioOverruns);
  CHECK(out.audioSource == 9u);
  CHECK(std::string(out.audioFileName) == "clip_x.wav");
  std::remove(path.c_str());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cmake -S core -B core/build -DCMAKE_BUILD_TYPE=RelWithDebInfo && cmake --build core/build -j
```
Expected: FAIL to compile -- `kMinReadableVersion`, `AudioInfo`, `audioPresent`, `kAudioOverruns` undeclared.

- [ ] **Step 3: Write minimal implementation**

In `core/include/rawcam/rawv.h`, change the version constants:

```cpp
constexpr uint32_t kVersion = 5;
// Oldest header version this build can still read. v4 files predate audio and
// read back with every audio field zero, which is exactly audioPresent == 0.
// The reader must range-check rather than demand equality, or bumping kVersion
// silently orphans every clip already on a user's device.
constexpr uint32_t kMinReadableVersion = 4;
```

Add above `#pragma pack(push, 1)`:

```cpp
// FileHeader.audioStatus bits. A clip can report several at once, so this is a
// bitfield rather than an enum of states. "Sync is trustworthy" means
// (audioStatus & kAudioSyncInvalidating) == 0.
constexpr uint32_t kAudioPermissionDenied = 1u << 0;  // RECORD_AUDIO not granted
constexpr uint32_t kAudioOpenFailed       = 1u << 1;  // AudioRecord would not open
constexpr uint32_t kAudioEndedEarly       = 1u << 2;  // disconnect/read error/disk full
constexpr uint32_t kAudioOverruns         = 1u << 3;  // samples dropped mid-stream
constexpr uint32_t kAudioSuspended        = 1u << 4;  // clock bridge moved mid-take
constexpr uint32_t kAudioPadded           = 1u << 5;  // head is inserted silence
constexpr uint32_t kAudioDriftHigh        = 1u << 6;  // drift over the warning threshold
constexpr uint32_t kAudioProcessedSource  = 1u << 7;  // UNPROCESSED unavailable
constexpr uint32_t kAudioSyncInvalidating =
    kAudioOverruns | kAudioSuspended | kAudioPadded;

// Audio parameters and sync provenance, handed to RawvWriter before finalize.
// Mirrors the FileHeader fields below; kept as its own type so the JNI layer and
// the writer share one definition instead of ten loose arguments.
struct AudioInfo {
  uint32_t present = 0;
  uint32_t sampleRate = 0;
  uint32_t channels = 0;
  uint32_t bitsPerSample = 0;
  int64_t  offsetNs = 0;
  int32_t  driftPpm = 0;
  uint32_t timestampSource = 0;
  uint32_t status = 0;
  uint32_t source = 0;
  char     fileName[64] = {};
};
```

In `struct FileHeader`, replace `uint8_t reserved[284];` with:

```cpp
  // ---- Audio (v5+). All zero in v4 files, which reads as audioPresent == 0.
  // The sidecar WAV named by audioFileName lives beside this file and is already
  // head-trimmed, so its sample 0 coincides with frame 0's SENSOR_TIMESTAMP
  // (start of exposure). audioOffsetNs is provenance only: it is the PRE-trim
  // measurement, positive when audio started first (the normal case, since audio
  // arms before the capture session).
  uint32_t audioPresent;
  uint32_t audioSampleRate;
  uint32_t audioChannels;
  uint32_t audioBitsPerSample;
  int64_t  audioOffsetNs;
  int32_t  audioDriftPpm;
  uint32_t audioTimestampSource;  // 0 = unknown/monotonic, 1 = realtime/boottime
  uint32_t audioStatus;           // bitfield, see kAudio* above
  uint32_t audioSource;           // MediaRecorder.AudioSource actually opened
  char     audioFileName[64];     // NUL-terminated sidecar basename
  uint8_t  reserved[180];
```

In `core/src/rawv_reader.cpp:84`, replace the strict equality:

```cpp
  if (!io::readAll(fd, &h, sizeof h) || h.magic != kMagic ||
      h.version < kMinReadableVersion || h.version > kVersion ||
      h.frameSizeBytes == 0 || !headerSane(h)) {
```

In `core/include/rawcam/rawv_writer.h`, add to the public section:

```cpp
  // Records audio parameters and sync provenance into the in-memory header, for
  // finalize() to write out. Must be called BEFORE finalize(); calling it after
  // has no effect, since finalize() is what rewrites the header on disk.
  void setAudioInfo(const AudioInfo& info);
```

In `core/src/rawv_writer.cpp`, add `#include <cstring>` if absent, and:

```cpp
void RawvWriter::setAudioInfo(const AudioInfo& info) {
  hdr_.audioPresent = info.present;
  hdr_.audioSampleRate = info.sampleRate;
  hdr_.audioChannels = info.channels;
  hdr_.audioBitsPerSample = info.bitsPerSample;
  hdr_.audioOffsetNs = info.offsetNs;
  hdr_.audioDriftPpm = info.driftPpm;
  hdr_.audioTimestampSource = info.timestampSource;
  hdr_.audioStatus = info.status;
  hdr_.audioSource = info.source;
  std::memcpy(hdr_.audioFileName, info.fileName, sizeof hdr_.audioFileName);
  hdr_.audioFileName[sizeof hdr_.audioFileName - 1] = '\0';
}
```

- [ ] **Step 4: Run the full ctest suite**

Run:
```bash
cmake --build core/build -j && ctest --test-dir core/build --output-on-failure
```
Expected: PASS -- the new `test_rawv_audio_header` plus every pre-existing test. If a pre-existing test hard-codes a version literal, change it to `kVersion`, never to a new literal.

- [ ] **Step 5: Commit**

```bash
git add core/include/rawcam/rawv.h core/include/rawcam/rawv_writer.h core/src/rawv_writer.cpp core/src/rawv_reader.cpp core/tests/test_rawv_audio_header.cpp
git commit -m "feat: .rawv v5 audio header fields; reader accepts v4 and v5"
```

---

### Task 4: Native pass-through for audio info

**Files:**
- Modify: `app/src/main/cpp/capture.h`, `app/src/main/cpp/capture.cpp`
- Modify: `app/src/main/cpp/jni_bridge.cpp`
- Modify: `app/src/main/java/com/shez/rawcam/NativeBridge.kt`

**Interfaces:**
- Consumes: `AudioInfo`, `RawvWriter::setAudioInfo` (Task 3).
- Produces: `Capture::setAudioInfo(const AudioInfo&)`; `NativeBridge.nativeSetAudioInfo(present: Boolean, sampleRate: Int, channels: Int, bitsPerSample: Int, offsetNs: Long, driftPpm: Int, timestampSource: Int, status: Int, source: Int, fileName: String)`.

> **Constraint.** This is the only permitted `capture.cpp` edit. Add the method and its two members, plus one line in `stop()`. Do not touch `writerLoop`, `processImage`, `onImageAvailable`, `matchMeta`, `finishLoop`, or any queue/mutex logic. The per-frame path must be byte-identical.

- [ ] **Step 1: Add the Capture member and method**

In `capture.h`, public section, after `stop()`:

```cpp
  // Stores audio parameters/provenance for the writer to fold into the header at
  // finalize. Called from the JNI/UI thread BEFORE stop(), because stop() is what
  // finalizes. Takes queueMutex_ so it cannot race the writer thread's deferred
  // creation of writer_.
  void setAudioInfo(const AudioInfo& info);
```

private section:

```cpp
  AudioInfo audioInfo_{};
  bool audioInfoSet_ = false;
```

In `capture.cpp`, immediately before `Capture::stop`:

```cpp
void Capture::setAudioInfo(const AudioInfo& info) {
  std::lock_guard<std::mutex> lock(queueMutex_);
  audioInfo_ = info;
  audioInfoSet_ = true;
}
```

In `Capture::stop()`, on the line immediately before the existing `writer_->finalize();`:

```cpp
    if (audioInfoSet_) writer_->setAudioInfo(audioInfo_);
```

- [ ] **Step 2: Add the JNI entry point**

In `jni_bridge.cpp` (add `#include <cstdio>` if absent), after `nativePushFrameMeta`:

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_shez_rawcam_NativeBridge_nativeSetAudioInfo(
    JNIEnv* env, jobject, jboolean present, jint sampleRate, jint channels,
    jint bitsPerSample, jlong offsetNs, jint driftPpm, jint timestampSource,
    jint status, jint source, jstring jFileName) {
  rawcam::AudioInfo info{};
  info.present = present == JNI_TRUE ? 1u : 0u;
  info.sampleRate = (uint32_t)sampleRate;
  info.channels = (uint32_t)channels;
  info.bitsPerSample = (uint32_t)bitsPerSample;
  info.offsetNs = (int64_t)offsetNs;
  info.driftPpm = (int32_t)driftPpm;
  info.timestampSource = (uint32_t)timestampSource;
  info.status = (uint32_t)status;
  info.source = (uint32_t)source;
  const char* name = env->GetStringUTFChars(jFileName, nullptr);
  if (name != nullptr) {
    std::snprintf(info.fileName, sizeof info.fileName, "%s", name);
    env->ReleaseStringUTFChars(jFileName, name);
  }
  rawcam::Capture::instance().setAudioInfo(info);
}
```

- [ ] **Step 3: Declare it in Kotlin**

In `NativeBridge.kt`, after `nativePushFrameMeta`:

```kotlin
    // Records audio parameters and sync provenance into the .rawv header. MUST be
    // called before nativeStopRecording(), which is what finalizes the header --
    // calling it afterwards is silently a no-op.
    external fun nativeSetAudioInfo(
        present: Boolean, sampleRate: Int, channels: Int, bitsPerSample: Int,
        offsetNs: Long, driftPpm: Int, timestampSource: Int, status: Int,
        source: Int, fileName: String,
    )
```

- [ ] **Step 4: Verify the build and that the frame path is untouched**

Run:
```bash
./gradlew :app:assembleDebug
git diff --stat app/src/main/cpp/capture.cpp
```
Expected: build succeeds; the `capture.cpp` diff shows roughly 6 insertions and 0 deletions -- the new method plus the one line in `stop()`. Any deletion or any change elsewhere in the file is a violation; revert it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/capture.h app/src/main/cpp/capture.cpp app/src/main/cpp/jni_bridge.cpp app/src/main/java/com/shez/rawcam/NativeBridge.kt
git commit -m "feat: nativeSetAudioInfo pass-through into the .rawv header"
```

---

### Task 5: Input device catalog

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/audio/AudioDeviceCatalog.kt`
- Test: `app/src/test/java/com/shez/rawcam/audio/AudioDeviceCatalogTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class AudioInputDevice(id: Int, type: Int, productName: String, channelCounts: IntArray)` with `key: String`, `displayName: String`, `preferredChannels: Int`; `object AudioDeviceCatalog` with `keyOf(type, productName)`, `displayNameOf(type, productName)`, `isExcluded(type)`, `resolve(devices, key)`, `selectable(devices)`.

The Android-facing half (`AudioManager.getDevices`) is a thin adapter inside `AudioRecorder` (Task 6); all decision-making lives in these pure functions so it is testable here.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/audio/AudioDeviceCatalogTest.kt`:

```kotlin
package com.shez.rawcam.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceCatalogTest {

    private fun dev(id: Int, type: Int, name: String, ch: IntArray = intArrayOf(1, 2)) =
        AudioInputDevice(id = id, type = type, productName = name, channelCounts = ch)

    private val builtin = dev(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Builtin")
    private val usb = dev(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett Solo")
    private val wired = dev(3, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")
    private val sco = dev(9, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")

    @Test
    fun `key combines type and product name`() {
        assertEquals("${AudioDeviceInfo.TYPE_USB_DEVICE}:Scarlett Solo", usb.key)
    }

    @Test
    fun `bluetooth types are excluded`() {
        assertTrue(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertFalse(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(AudioDeviceCatalog.isExcluded(AudioDeviceInfo.TYPE_USB_DEVICE))
    }

    @Test
    fun `selectable drops excluded devices and keeps order`() {
        assertEquals(listOf(builtin, usb), AudioDeviceCatalog.selectable(listOf(builtin, sco, usb)))
    }

    @Test
    fun `resolve finds an exact key match`() {
        assertEquals(usb, AudioDeviceCatalog.resolve(listOf(builtin, usb), usb.key))
    }

    @Test
    fun `resolve returns null for an absent device`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin), usb.key))
    }

    @Test
    fun `resolve returns null for a renamed device`() {
        val renamed = dev(7, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett 2i2")
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin, renamed), usb.key))
    }

    @Test
    fun `resolve ignores an unstable id change`() {
        // Same type and product name, new id after a reconnect: still a match.
        val reconnected = dev(42, AudioDeviceInfo.TYPE_USB_DEVICE, "Scarlett Solo")
        assertEquals(reconnected, AudioDeviceCatalog.resolve(listOf(reconnected), usb.key))
    }

    @Test
    fun `an empty key never resolves, meaning use the system default`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(builtin, usb), ""))
    }

    @Test
    fun `resolve never returns an excluded device even on an exact key match`() {
        assertNull(AudioDeviceCatalog.resolve(listOf(sco), sco.key))
    }

    @Test
    fun `display names are human readable`() {
        assertEquals("Built-in mic", builtin.displayName)
        assertEquals("Wired headset", wired.displayName)
        assertEquals("USB: Scarlett Solo", usb.displayName)
    }

    @Test
    fun `preferred channels is stereo when offered, else mono`() {
        assertEquals(2, usb.preferredChannels)
        assertEquals(1, dev(5, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Mono", intArrayOf(1)).preferredChannels)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.AudioDeviceCatalogTest"`
Expected: FAIL -- unresolved reference `AudioInputDevice`, `AudioDeviceCatalog`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/shez/rawcam/audio/AudioDeviceCatalog.kt`:

```kotlin
package com.shez.rawcam.audio

import android.media.AudioDeviceInfo

/**
 * A selectable audio input, flattened out of [AudioDeviceInfo] so the selection
 * logic is testable without a device.
 *
 * [id] is deliberately NOT part of [key]: AudioDeviceInfo ids are not stable
 * across disconnect/reconnect, so persisting one would silently lose the user's
 * chosen mic the first time they unplugged it.
 */
data class AudioInputDevice(
    val id: Int,
    val type: Int,
    val productName: String,
    val channelCounts: IntArray,
) {
    val key: String get() = AudioDeviceCatalog.keyOf(type, productName)
    val displayName: String get() = AudioDeviceCatalog.displayNameOf(type, productName)

    /** Stereo when the device offers it, else mono. Pinned policy, not a user option. */
    val preferredChannels: Int get() = if (channelCounts.contains(2)) 2 else 1

    // channelCounts is an array, so the generated equals/hashCode would compare by
    // identity and break every list assertion this type appears in.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioInputDevice) return false
        return id == other.id && type == other.type && productName == other.productName &&
            channelCounts.contentEquals(other.channelCounts)
    }

    override fun hashCode(): Int {
        var r = id
        r = 31 * r + type
        r = 31 * r + productName.hashCode()
        r = 31 * r + channelCounts.contentHashCode()
        return r
    }
}

/**
 * Pure input-selection policy: which devices are offered, how a persisted choice
 * is encoded, and how it is resolved against the currently connected set.
 */
object AudioDeviceCatalog {

    /**
     * Bluetooth is excluded outright. SCO and LE Audio have variable,
     * uncharacterizable latency, so a Bluetooth mic would produce a clip whose
     * sync claim is false -- worse than not offering the input at all.
     */
    private val EXCLUDED_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    fun isExcluded(type: Int): Boolean = type in EXCLUDED_TYPES

    fun keyOf(type: Int, productName: String): String = "$type:$productName"

    fun displayNameOf(type: Int, productName: String): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB: $productName"
        else -> productName
    }

    /** The devices worth offering, in the order the platform reported them. */
    fun selectable(devices: List<AudioInputDevice>): List<AudioInputDevice> =
        devices.filter { !isExcluded(it.type) }

    /**
     * Resolves a persisted [key] against the live device list. Null means "use the
     * system default" -- which covers an empty key, a device since unplugged, and
     * one whose product name changed.
     */
    fun resolve(devices: List<AudioInputDevice>, key: String): AudioInputDevice? {
        if (key.isEmpty()) return null
        return selectable(devices).firstOrNull { it.key == key }
    }
}
```

`AudioDeviceInfo`'s constants are compile-time ints, so these pure functions run under plain JUnit without Robolectric.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.audio.AudioDeviceCatalogTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/audio/AudioDeviceCatalog.kt app/src/test/java/com/shez/rawcam/audio/AudioDeviceCatalogTest.kt
git commit -m "feat: audio input catalog with stable-key device resolution"
```

---

### Task 6: AudioRecorder

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/audio/AudioRecorder.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AvSync`, `ClockBridge`, `AudioAnchor` (Task 1); `WavWriter`, `BextInfo` (Task 2); `AudioInputDevice`, `AudioDeviceCatalog` (Task 5).
- Produces: `data class MeterLevels(peakDbfsL: Float, peakDbfsR: Float, clipped: Boolean)` with `companion SILENCE_DBFS = -160f`; `data class AudioResult(present, sampleRate, channels, offsetNs, driftPpm, timestampSource, status, source, fileName)`; `object AudioStatus` mirroring the C++ bits; `class AudioRecorder(context: Context)` with `listInputs(): List<AudioInputDevice>`, `hasPermission(): Boolean`, `start(wavFile: File, deviceKey: String, gainDb: Float, cameraSourceIsRealtime: Boolean): Boolean`, `onFirstFrame(sensorTimestampNs: Long)`, `stop(): AudioResult`, `val meter: StateFlow<MeterLevels>`.

- [ ] **Step 1: Add the permission**

In `app/src/main/AndroidManifest.xml`, alongside the existing `uses-permission` entries:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

No foreground-service type is needed: recording is Activity-scoped and backgrounding already finalizes the clip via `MainActivity.onStop`.

- [ ] **Step 2: Write the implementation**

Create `app/src/main/java/com/shez/rawcam/audio/AudioRecorder.kt`:

```kotlin
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
        gainLinear = 10.0.pow(gainDb.toDouble() / 20.0).toFloat()

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
            val info = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == target.id }
            if (info != null) rec.preferredDevice = info
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
        } catch (e: IllegalStateException) {
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
            } catch (e: IllegalArgumentException) {
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
            val w = writer
            if (w == null) {
                bufferPreroll(chunk)
            } else {
                try {
                    w.append(chunk, chunk.size)
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
     * onFirstFrame that never comes must not grow this without limit. */
    private fun bufferPreroll(chunk: FloatArray) {
        synchronized(preroll) {
            preroll.addLast(chunk)
            prerollSamples += chunk.size
            while (prerollSamples > MAX_PREROLL_SAMPLES && preroll.isNotEmpty()) {
                prerollSamples -= preroll.removeFirst().size
                status = status or AudioStatus.OVERRUNS
            }
        }
    }

    /**
     * Supplies frame 0's SENSOR_TIMESTAMP: computes the head trim, flushes the
     * buffered prefix through it, and switches to streaming. Only the first call
     * has any effect, since only the first frame defines t=0.
     */
    fun onFirstFrame(sensorTimestampNs: Long) {
        if (!running.get() || writer != null || frame0BootNs != 0L) return
        val bridge = firstBridge
            ?: ClockBridge(System.nanoTime(), SystemClock.elapsedRealtimeNanos())
        frame0BootNs = AvSync.toBootNs(sensorTimestampNs, sourceIsRealtime, bridge)

        // No anchor yet means getTimestamp has not succeeded on this device. Fall
        // back to "no correction" (offset 0) rather than applying a wrong one.
        val anchor = synchronized(anchors) { anchors.firstOrNull() }
        val sample0 = if (anchor != null) AvSync.sample0BootNs(anchor, sampleRate) else frame0BootNs
        offsetNs = frame0BootNs - sample0
        val trimFrames = AvSync.trimSamples(frame0BootNs, sample0, sampleRate)

        val f = wavFile ?: return
        val w = try {
            WavWriter(f, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "could not open WAV", e)
            status = status or AudioStatus.ENDED_EARLY
            running.set(false)
            return
        }

        // trimFrames counts sample FRAMES; the buffers hold interleaved samples.
        var toDrop = trimFrames * channels
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
        synchronized(preroll) {
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
        }
        writer = w
    }
```

**Post-implementation correction (whole-branch review, 2026-08-17 fix wave, Critical 1):** the
drain loop above has a defect this plan did not anticipate -- if the loop exits with `toDrop > 0`
(the trim is larger than the entire buffered preroll), that residual was silently discarded rather
than applied: the samples that should have been trimmed stay in the stream untrimmed, with no
status bit recording that alignment was not actually achieved. Fixed by (1) carrying the residual
forward as a field consumed by the streaming append path instead of dropping it, (2) guarding
implausible trims (`abs(trimFrames) > MAX_PREROLL_SAMPLES / channels`) by applying no trim and
setting a new `ALIGNMENT_UNVERIFIED` status bit rather than a nonsense one, and (3) extracting the
drain/pad decision into a pure, unit-tested function (`AvSync.planPrerollTrim`). See
`AudioRecorder.kt`'s `flushFirstFrame`/`appendTrimmed` and `AvSyncTest.kt`'s `planPrerollTrim`
cases for the corrected version; the code block above is preserved here only as the plan's original
(defective) design intent.

```kotlin

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
        driftPpm = AvSync.driftPpm(snapshot, sampleRate)
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
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        record?.release()
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
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 4: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS -- every pre-existing test plus Tasks 1, 2, 5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/audio/AudioRecorder.kt app/src/main/AndroidManifest.xml
git commit -m "feat: AudioRecorder -- UNPROCESSED capture, preroll trim, meter, clock anchors"
```

---

### Task 7: Settings fields, permission, and settings UI

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/shez/rawcam/MainActivity.kt`
- Test: `app/src/test/java/com/shez/rawcam/settings/AudioSettingsDefaultsTest.kt`

**Interfaces:**
- Consumes: `AudioInputDevice`, `AudioDeviceCatalog` (Task 5); `AudioRecorder.listInputs` (Task 6).
- Produces: `Settings.recordAudio: Boolean`, `Settings.audioInputKey: String`, `Settings.audioGainDb: Float`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shez/rawcam/settings/AudioSettingsDefaultsTest.kt`:

```kotlin
package com.shez.rawcam.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsDefaultsTest {

    @Test
    fun `audio defaults are off, system default input, unity gain`() {
        val s = Settings()
        assertEquals(false, s.recordAudio)
        assertEquals("", s.audioInputKey)
        assertEquals(0f, s.audioGainDb, 0.0001f)
    }

    @Test
    fun `gain clamp bounds match the spec`() {
        assertEquals(-20f, (-100f).coerceIn(-20f, 30f), 0.0001f)
        assertEquals(30f, (100f).coerceIn(-20f, 30f), 0.0001f)
        assertEquals(6f, (6f).coerceIn(-20f, 30f), 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.settings.AudioSettingsDefaultsTest"`
Expected: FAIL -- unresolved reference `recordAudio`.

- [ ] **Step 3: Add the settings fields**

In `SettingsRepository.kt`, add to `data class Settings` after `compressRecordings`:

```kotlin
    val recordAudio: Boolean = false,        // sidecar WAV capture; off by default so an
                                             // upgrade neither springs a mic-permission
                                             // prompt nor silently writes a second file
    val audioInputKey: String = "",          // "" = system default; "<type>:<productName>"
    val audioGainDb: Float = 0f,             // -20.0 .. +30.0 digital trim
```

Add the keys alongside the others:

```kotlin
    private val KEY_RECORD_AUDIO = booleanPreferencesKey("recordAudio")
    private val KEY_AUDIO_INPUT_KEY = stringPreferencesKey("audioInputKey")
    private val KEY_AUDIO_GAIN_DB = floatPreferencesKey("audioGainDb")
```

Add to `Preferences.toSettings()`:

```kotlin
            recordAudio = this[KEY_RECORD_AUDIO] ?: fallback.recordAudio,
            audioInputKey = this[KEY_AUDIO_INPUT_KEY] ?: fallback.audioInputKey,
            audioGainDb = this[KEY_AUDIO_GAIN_DB] ?: fallback.audioGainDb,
```

In `update()`, add to the existing `next` copy block:

```kotlin
                audioGainDb = updated.audioGainDb.coerceIn(-20f, 30f),
```

and the writes:

```kotlin
            prefs[KEY_RECORD_AUDIO] = next.recordAudio
            prefs[KEY_AUDIO_INPUT_KEY] = next.audioInputKey
            prefs[KEY_AUDIO_GAIN_DB] = next.audioGainDb
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.shez.rawcam.settings.AudioSettingsDefaultsTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Add the settings UI**

In `SettingsScreen.kt`, after the `TextFieldRow` for "Clip name prefix" (near line 232). The screen already supplies `SectionHeader`, `ToggleRow`, `EnumRow` and an `apply { ... }` helper -- follow them exactly.

```kotlin
            SectionHeader("AUDIO")
            ToggleRow(
                title = "Record audio",
                subtitle = "Writes a synced .wav beside each clip",
                checked = settings.recordAudio,
                onChange = { v ->
                    // Ask at the toggle, never at record time: a permission dialog
                    // appearing as the user hits record is how takes get lost.
                    if (v) onRequestAudioPermission()
                    apply { it.copy(recordAudio = v) }
                },
            )
            if (settings.recordAudio) {
                val inputs = remember { audioInputs() }
                EnumRow(
                    title = "Input",
                    subtitle = if (settings.audioInputKey.isNotEmpty() &&
                        AudioDeviceCatalog.resolve(inputs, settings.audioInputKey) == null
                    ) "Saved input unavailable -- using default" else null,
                    options = listOf("" to "System default") + inputs.map { d -> d.key to d.displayName },
                    selected = settings.audioInputKey,
                    onSelect = { v -> apply { it.copy(audioInputKey = v) } },
                )
                EnumRow(
                    title = "Gain", subtitle = null,
                    options = listOf(
                        -20f to "-20 dB", -12f to "-12 dB", -6f to "-6 dB", 0f to "0 dB",
                        6f to "+6 dB", 12f to "+12 dB", 20f to "+20 dB", 30f to "+30 dB",
                    ),
                    selected = settings.audioGainDb,
                    onSelect = { v -> apply { it.copy(audioGainDb = v) } },
                )
            }
```

Gain is offered as discrete stops rather than a free slider so it reuses the existing `EnumRow` and can never persist an un-representable value.

Add two parameters to the `SettingsScreen` composable signature:

```kotlin
    onRequestAudioPermission: () -> Unit,
    audioInputs: () -> List<AudioInputDevice>,
```

In `MainActivity`, register a permission launcher and supply both:

```kotlin
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result
            surfaces at record time via AudioStatus.PERMISSION_DENIED */ }
```

passing `onRequestAudioPermission = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }`
and `audioInputs = { AudioRecorder(this).listInputs() }`.

- [ ] **Step 6: Verify and commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: build and all tests PASS.

```bash
git add app/src/main/java/com/shez/rawcam/settings/SettingsRepository.kt app/src/main/java/com/shez/rawcam/ui/SettingsScreen.kt app/src/main/java/com/shez/rawcam/MainActivity.kt app/src/test/java/com/shez/rawcam/settings/AudioSettingsDefaultsTest.kt
git commit -m "feat: audio settings -- toggle, input picker, gain stops"
```

---

### Task 8: Levels meter

**Files:**
- Create: `app/src/main/java/com/shez/rawcam/ui/AudioMeter.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`

**Interfaces:**
- Consumes: `MeterLevels` (Task 6).
- Produces: `@Composable fun AudioMeter(levels: MeterLevels, channels: Int, noAudio: Boolean, modifier: Modifier)`.

- [ ] **Step 1: Write the meter composable**

Create `app/src/main/java/com/shez/rawcam/ui/AudioMeter.kt`:

```kotlin
package com.shez.rawcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shez.rawcam.audio.MeterLevels
import kotlinx.coroutines.delay

/**
 * Peak level meter. Shown whenever audio is enabled and deliberately NOT gated on
 * the stats-sidebar setting -- levels are a recording-critical indicator, not a
 * stat.
 *
 * Scale is -60..0 dBFS. The clip lamp latches for [CLIP_LATCH_MS] so a single
 * over-sample cannot flash past unnoticed between frames.
 */
@Composable
fun AudioMeter(
    levels: MeterLevels,
    channels: Int,
    noAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    if (noAudio) {
        Text(
            text = "NO AUDIO",
            color = Color(0xFFFF5252),
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
        return
    }

    var clipLatched by remember { mutableStateOf(false) }
    var peakHoldL by remember { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }
    var peakHoldR by remember { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }

    LaunchedEffect(levels.clipped) {
        if (levels.clipped) {
            clipLatched = true
            delay(CLIP_LATCH_MS)
            clipLatched = false
        }
    }
    LaunchedEffect(levels) {
        if (levels.peakDbfsL > peakHoldL) peakHoldL = levels.peakDbfsL
        if (levels.peakDbfsR > peakHoldR) peakHoldR = levels.peakDbfsR
        delay(PEAK_HOLD_MS)
        peakHoldL = levels.peakDbfsL
        peakHoldR = levels.peakDbfsR
    }

    Column(modifier = modifier) {
        MeterBar(levels.peakDbfsL, peakHoldL, clipLatched)
        if (channels == 2) MeterBar(levels.peakDbfsR, peakHoldR, clipLatched)
    }
}

@Composable
private fun MeterBar(dbfs: Float, peakHold: Float, clipped: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .padding(vertical = 1.dp)
            .background(Color(0xFF1A1A1A)),
    ) {
        val w = size.width * norm(dbfs)
        val color = when {
            dbfs >= -3f -> Color(0xFFFF5252)
            dbfs >= -12f -> Color(0xFFFFC107)
            else -> Color(0xFF4CAF50)
        }
        if (w > 0f) drawRect(color = color, size = Size(w, size.height))
        val hold = size.width * norm(peakHold)
        if (hold > 0f) {
            drawRect(
                color = Color.White,
                topLeft = Offset(hold - PEAK_TICK_PX, 0f),
                size = Size(PEAK_TICK_PX, size.height),
            )
        }
        if (clipped) {
            drawRect(
                color = Color(0xFFFF1744),
                topLeft = Offset(size.width - CLIP_LAMP_PX, 0f),
                size = Size(CLIP_LAMP_PX, size.height),
            )
        }
    }
}

/** Maps -60..0 dBFS onto 0..1, clamped. */
private fun norm(dbfs: Float): Float = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)

private const val CLIP_LATCH_MS = 2_000L
private const val PEAK_HOLD_MS = 1_500L
private const val PEAK_TICK_PX = 2f
private const val CLIP_LAMP_PX = 6f
```

- [ ] **Step 2: Wire it into RecordScreen and keep the screen on**

In `RecordScreen.kt`:

1. Add `audioChannels: Int = 1` and `audioFailed: Boolean = false` to the UI state data class.

2. Collect the meter next to the existing state collection:

```kotlin
    val meterLevels by viewModel.audioMeter.collectAsState()
```

3. Render it in the HUD, gated on the setting rather than on `showStatsSidebar`:

```kotlin
            if (uiState.settings.recordAudio) {
                AudioMeter(
                    levels = meterLevels,
                    channels = uiState.audioChannels,
                    noAudio = uiState.audioFailed,
                    modifier = Modifier.width(120.dp),
                )
            }
```

4. Keep the screen on while recording, so the device cannot sleep mid-take and move the clock bridge:

```kotlin
    val view = LocalView.current
    LaunchedEffect(uiState.recording) { view.keepScreenOn = uiState.recording }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/ui/AudioMeter.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: audio levels meter with peak hold and clip latch; keep screen on while recording"
```

---

### Task 9: Recording integration

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/camera/CameraController.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt`

**Interfaces:**
- Consumes: `AudioRecorder`, `AudioResult`, `AudioStatus`, `MeterLevels` (Task 6); `NativeBridge.nativeSetAudioInfo` (Task 4).
- Produces: `CameraController.startRecording(..., recordAudio: Boolean, audioInputKey: String, audioGainDb: Float)`; `CameraController.audioMeter: StateFlow<MeterLevels>`; `CameraController.lastAudioResult: AudioResult?`.

> **Ordering is the point of this task.** Audio arms BEFORE `nativeStartRecording`, so the buffered prefix always precedes frame 0. On stop, `nativeSetAudioInfo` runs BEFORE `nativeStopRecording`, because the latter finalizes the header. Camera quiesce still happens first, unchanged.

- [ ] **Step 1: Read the camera's timestamp source**

Add the field to `CameraController`:

```kotlin
    /** True when SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME, i.e. SENSOR_TIMESTAMP is
     * already CLOCK_BOOTTIME. Otherwise it is CLOCK_MONOTONIC and must be bridged.
     * Nothing in the app read this flag before audio existed. */
    private var sensorTimestampIsRealtime: Boolean = false
```

Set it where the active camera's `CameraCharacteristics` are read:

```kotlin
        sensorTimestampIsRealtime =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
```

- [ ] **Step 2: Arm audio first in startRecording**

Add the fields:

```kotlin
    private val audioRecorder = AudioRecorder(context)
    val audioMeter: StateFlow<MeterLevels> get() = audioRecorder.meter
    var lastAudioResult: AudioResult? = null
        private set
    private var audioArmed = false
    @Volatile private var firstFrameSeen = false
```

Extend the signature and arm audio at the top of `startRecording`, before `nativeStartRecording`:

```kotlin
    fun startRecording(
        path: String, fps: Int, iso: Int, exposureNs: Long, focusDiopters: Float,
        kelvin: Int, tint: Int, compressRecordings: Boolean = false,
        recordAudio: Boolean = false, audioInputKey: String = "", audioGainDb: Float = 0f,
    ): Boolean {
        if (recording) return false
        val preview = previewSurface ?: return false
        if (device == null) return false
        clipsDir.mkdirs()

        // Arm audio BEFORE the native writer and the session. Session configuration
        // blocks for hundreds of ms, so starting here guarantees audio is already
        // running when frame 0 arrives, which makes head alignment an exact trim
        // rather than a guessed pad. A failure never blocks the take: video wins.
        firstFrameSeen = false
        audioArmed = false
        lastAudioResult = null
        if (recordAudio) {
            val wav = File(path.removeSuffix(".rawv") + ".wav")
            audioArmed = audioRecorder.start(wav, audioInputKey, audioGainDb, sensorTimestampIsRealtime)
            if (!audioArmed) {
                Log.w(TAG, "audio failed to arm; recording video only")
                lastAudioResult = audioRecorder.stop()
            }
        }
        // ... existing body continues unchanged ...
```

On the native-start failure path, tear audio down too:

```kotlin
        val raw = NativeBridge.nativeStartRecording(
            path, spec.width, spec.height, spec.cfa, spec.whiteLevel,
            spec.blackLevel, spec.colorMatrix1, spec.illuminant1, spec.illuminant2,
            spec.colorMatrix2, /* fpsNum = */ fps, /* fpsDen = */ 1,
            spec.deviceName, compressRecordings,
        ) ?: run {
            if (audioArmed) { lastAudioResult = audioRecorder.stop(); audioArmed = false }
            return false
        }
```

Apply the same two lines in the session-configuration failure branch, next to the existing `NativeBridge.nativeStopRecording()` discard call.

- [ ] **Step 3: Feed frame 0's timestamp to the recorder**

In `captureCallback.onCaptureCompleted` (currently `CameraController.kt:1508`), immediately after the existing `val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return`:

```kotlin
            // The first frame of a take defines t=0 for audio alignment. One
            // predictable branch on the meta path in steady state.
            if (!firstFrameSeen) {
                firstFrameSeen = true
                if (audioArmed) audioRecorder.onFirstFrame(ts)
            }
```

- [ ] **Step 4: Stop audio before the header is finalized**

In `stopRecording()`, between the existing step 2 (`idleLatch = null` / `recording = false`) and step 3 (`NativeBridge.nativeStopRecording()`):

```kotlin
        // 2b. Stop audio and publish its provenance BEFORE the native stop:
        // nativeStopRecording() finalizes the header, and setAudioInfo after that
        // point is silently a no-op.
        if (audioArmed) {
            val a = audioRecorder.stop()
            lastAudioResult = a
            audioArmed = false
            NativeBridge.nativeSetAudioInfo(
                a.present, a.sampleRate, a.channels, /* bitsPerSample = */ 24,
                a.offsetNs, a.driftPpm, a.timestampSource, a.status, a.source, a.fileName,
            )
        }
```

- [ ] **Step 5: Pass settings through and surface failures**

In `RecordScreen.kt`'s `startRecordingInternal`, extend the existing call:

```kotlin
                val ok = controller.startRecording(
                    path, s.fps, s.iso, exposureNs, s.focusDiopters, s.kelvin, s.tint,
                    compressRecordings = s.settings.compressRecordings,
                    recordAudio = s.settings.recordAudio,
                    audioInputKey = s.settings.audioInputKey,
                    audioGainDb = s.settings.audioGainDb,
                )
```

Expose the meter on the ViewModel:

```kotlin
    val audioMeter: StateFlow<MeterLevels> get() = controller.audioMeter
```

In `stopRecordingInternal`'s completion block, after the existing frames toast:

```kotlin
                val audio = controller.lastAudioResult
                if (_uiState.value.settings.recordAudio && audio != null) {
                    _uiState.update {
                        it.copy(audioChannels = audio.channels, audioFailed = !audio.present)
                    }
                    audioWarning(audio)?.let { _events.tryEmit(it) }
                }
```

Add the mapper to the ViewModel:

```kotlin
    /** One short, honest cause for the user. Order matters: say why the clip has no
     * audio at all before saying its sync is merely degraded. */
    private fun audioWarning(a: AudioResult): String? = when {
        a.status and AudioStatus.PERMISSION_DENIED != 0 -> "No audio: microphone permission denied"
        a.status and AudioStatus.OPEN_FAILED != 0 -> "No audio: could not open the input"
        a.status and AudioStatus.ENDED_EARLY != 0 -> "Audio ended early; clip is short on sound"
        a.status and AudioStatus.SUSPENDED != 0 -> "Audio sync unreliable: device slept mid-take"
        a.status and AudioStatus.OVERRUNS != 0 -> "Audio dropouts; sync may drift"
        a.status and AudioStatus.PADDED != 0 -> "Audio started late; head is padded with silence"
        a.status and AudioStatus.DRIFT_HIGH != 0 -> "Audio clock drift ${a.driftPpm} ppm"
        a.status and AudioStatus.PROCESSED_SOURCE != 0 -> "Audio may be processed (UNPROCESSED unavailable)"
        else -> null
    }
```

- [ ] **Step 6: Verify**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: build and all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/camera/CameraController.kt app/src/main/java/com/shez/rawcam/ui/RecordScreen.kt
git commit -m "feat: wire audio into the recording lifecycle with correct start/stop ordering"
```

---

### Task 10: Export and clip-management pairing

**Files:**
- Modify: `app/src/main/java/com/shez/rawcam/export/ExportService.kt`
- Modify: `app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt`

**Interfaces:**
- Consumes: `WavWriter.repairIfTruncated` (Task 2).
- Produces: no new public API; behavior only.

- [ ] **Step 1: Copy the WAV on a successful export**

In `ExportService.onStartCommand`, immediately before the existing MediaStore scan block (`if (ok && ExportPaths.isPublicRoot(...))`):

```kotlin
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
                        val dst = File(outDir, "$baseName.wav")
                        srcWav.copyTo(dst, overwrite = true)
                        wavCopied = dst
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to copy sidecar WAV for $rawvPath", e)
                    }
                }
            }
```

Include it in the scan so it appears over MTP alongside the DNGs -- replace the existing `dngPaths`/`scanFile` pair with:

```kotlin
                val paths = (dngPaths.toList() + listOfNotNull(wavCopied?.absolutePath)).toTypedArray()
                if (paths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(this, paths, null, null)
                }
```

- [ ] **Step 2: Delete the WAV alongside the .rawv**

Extend the existing `if (ok && deleteAfter)` block, after the `.rawv` delete:

```kotlin
                val srcWav = File(rawvPath.removeSuffix(".rawv") + ".wav")
                if (srcWav.exists()) {
                    try {
                        if (!srcWav.delete()) Log.e(TAG, "deleteAfter: failed to delete $srcWav")
                    } catch (e: Exception) {
                        Log.e(TAG, "deleteAfter: failed to delete $srcWav", e)
                    }
                }
```

- [ ] **Step 3: Pair delete, share, and badge in ClipsScreen**

In `ClipsScreen.kt`:

1. Add a helper next to the existing `baseName`:

```kotlin
private fun wavOf(f: File) = File(f.parentFile, baseName(f) + ".wav")
```

2. In the delete action, remove both:

```kotlin
                        val wav = wavOf(file)
                        if (wav.exists() && !wav.delete()) {
                            android.util.Log.w("ClipsScreen", "failed to delete sidecar $wav")
                        }
```

3. In the share action, use `ACTION_SEND_MULTIPLE` when the sibling exists, passing both content URIs through the existing FileProvider authority; keep the single-item `ACTION_SEND` path when it does not.

4. In the clip row, show an audio badge when `wavOf(file).exists()` -- a small "A" next to the existing duration/size text.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: build and all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shez/rawcam/export/ExportService.kt app/src/main/java/com/shez/rawcam/ui/ClipsScreen.kt
git commit -m "feat: pair the sidecar WAV through export, delete, and share"
```

---

### Task 11: On-device acceptance

**Files:** none until step 9 (verification only; results are appended to this plan).

Host-green proves nothing about device behavior -- the standing lesson from the codec rounds. This task measures the sync claim rather than asserting it.

- [ ] **Step 1: Build and install**

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

- [ ] **Step 2: Confirm the settings actually took**

Enable "Record audio" in Settings and grant the permission. Verify the input picker lists the built-in mic and lists **no** Bluetooth device.

Recurring device gotcha: settings toggles have silently reverted between sessions on this project before. Confirm the toggle is on immediately before recording, not merely at the start of the session.

- [ ] **Step 3: Record the clap tests**

Record ~15 s with a sharp clap about 2 s in, hands visible in frame. Then record a 10-minute take with a clap near the start and another near the end.

- [ ] **Step 4: Verify the header before trusting any measurement**

Pull the clip and read the `FileHeader` struct properly -- do not eyeball adjacent `u32`s in a hex dump:

```bash
adb pull /sdcard/Android/data/com.shez.rawcam/files/clips/<clip>.rawv .
python3 - <<'PY'
import struct
h = open('<clip>.rawv','rb').read(512)
# Offsets derived from the struct in core/include/rawcam/rawv.h, in order:
# magic,version,width,height,rowStrideBytes,packMode,cfa,whiteLevel (8 x u32),
# blackLevel[4] (u32), colorMatrix1[9] (f32), asShotNeutral[3] (f32),
# fpsNum,fpsDen,frameSizeBytes,_pad (u32), frameCount (u64), deviceName[64],
# illuminant1,illuminant2 (u32), colorMatrix2[9] (f32), then the audio block.
off = 4*8 + 4*4 + 4*9 + 4*3 + 4*4 + 8 + 64 + 4*2 + 4*9
print('version', struct.unpack_from('<I', h, 4)[0])
print('packMode', struct.unpack_from('<I', h, 20)[0])
present, rate, ch, bits = struct.unpack_from('<IIII', h, off)
offset_ns, = struct.unpack_from('<q', h, off+16)
drift, tsrc, status, source = struct.unpack_from('<iIII', h, off+24)
name = h[off+40:off+104].split(b'\0')[0].decode()
print(dict(present=present, rate=rate, ch=ch, bits=bits, offset_ns=offset_ns,
           drift_ppm=drift, ts_source=tsrc, status=status, source=source, wav=name))
PY
```

Expect `version == 5`, `present == 1`, `rate == 48000`, `status == 0` on a clean take. Note the reported `ts_source` -- it differs by vendor and is the single most important number for interpreting everything else.

HyperOS drops the app's own logcat tags, so capture logs to a file rather than relying on `adb logcat` tag filtering.

- [ ] **Step 5: Measure the offset**

Export the clip, then in an NLE (or with a script over the DNG timestamps and the WAV) find the frame where the hands meet and the sample index of the transient. Record the difference in milliseconds.

Pass: within one frame (41.7 ms at 24 fps), per spec success criterion 1. Record the actual number either way -- "passed" without a number is not a result.

- [ ] **Step 6: Measure drift over the long take**

Compare head and tail offsets on the 10-minute clip. Record the delta, and compare it against the `audioDriftPpm` the app reported for itself; they should agree in sign and rough magnitude. Drift is not corrected in v1, so a nonzero result is expected -- the point is that the app's own measurement is honest.

- [ ] **Step 7: Exercise the failure paths**

- Revoke the microphone permission, then record: video must complete and the clip must report "No audio: microphone permission denied".
- Start a take on a USB interface and unplug it mid-take: the take must complete, the WAV must be short but valid, and `ENDED_EARLY` must be reported.
- Confirm the frame landing rate is unchanged against a video-only take of the same length and thermal state (spec success criterion 5).

- [ ] **Step 8: Repeat on the second hardware family**

Run steps 3-7 on the Samsung Galaxy S22 Ultra (SM-S908E). `UNPROCESSED` support and `SENSOR_INFO_TIMESTAMP_SOURCE` both vary by vendor, so a result on one family does not generalize.

- [ ] **Step 9: Record the results and commit**

Append an "## On-device results" section to this plan with the measured offsets, the drift, each device's reported timestamp source, and whether `UNPROCESSED` was granted on each.

```bash
git add docs/superpowers/plans/2026-08-17-audio-recording.md
git commit -m "docs: on-device audio acceptance results"
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: section 2 architecture -> Tasks 3, 4, 6; section 3 data flow -> Task 9 (with the two documented corrections); section 4 components -> Tasks 1, 2, 5, 6, 8; section 5 sync -> Tasks 1, 6, 9; section 6 on-disk format -> Tasks 2, 3; section 7 file pairing -> Task 10; section 8 settings/UI/failure -> Tasks 6, 7, 8, 9; section 9 testing -> Tasks 1, 2, 3, 5, 11; section 10 success criteria -> Task 11.

**Deviations from the spec, both deliberate and documented in File Structure above:**
1. The head trim moves from finalize to start, because RIFF's fixed data offset makes finalize-time trimming a whole-file rewrite.
2. `nativeSetAudioInfo` precedes `nativeStopRecording`, because the latter finalizes the header.

The spec should be amended to match before this plan is executed.

**Type consistency.** `AudioStatus.*` (Kotlin) mirrors `kAudio*` (C++) bit for bit. `AudioResult`'s field order matches `nativeSetAudioInfo`'s parameter order. `MeterLevels.SILENCE_DBFS` has one definition, used by `AudioRecorder` and `AudioMeter`. `WavWriter.HEADER_BYTES` has one definition, used by `WavWriterTest`, `AudioRecorder.stop`, and `repairIfTruncated`. `AudioInputDevice.preferredChannels` is defined in Task 5 and consumed in Task 6.

**Residual risk carried into execution.** `AudioRecord.getTimestamp` is not guaranteed to succeed on every device. Where it never succeeds, `onFirstFrame` falls back to treating audio sample 0 as frame 0 (offset 0) -- the pre-existing "no correction" behavior rather than a confidently wrong one. Task 11 step 5 is what detects this, which is why the measured number matters more than the pass/fail.
