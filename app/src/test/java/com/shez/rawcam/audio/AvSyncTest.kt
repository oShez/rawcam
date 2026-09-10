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

    // One anchor per second across [seconds], with the boottime clock running
    // [ppm] parts-per-million long. Spans the full MIN_DRIFT_SPAN_NS, because
    // shorter runs are now deliberately reported as unmeasured. 35s rather than
    // exactly 30: at -100 ppm, 30s of SAMPLES spans only 29.997s of wall clock,
    // and the guard is on wall clock.
    private fun anchorsOver(seconds: Int, ppm: Long = 0L) = (0..seconds).map { i ->
        AudioAnchor(i.toLong() * 48_000L, i.toLong() * (1_000_000_000L + ppm * 1_000L))
    }

    @Test
    fun `perfect clock has zero drift`() {
        assertEquals(0, AvSync.driftPpm(anchorsOver(35), 48_000))
    }

    @Test
    fun `slow mic clock yields positive ppm`() {
        // Wall time runs 100ppm longer than the sample count implies.
        assertEquals(100, AvSync.driftPpm(anchorsOver(35, 100), 48_000))
    }

    @Test
    fun `a fast mic clock yields negative ppm`() {
        assertEquals(-100, AvSync.driftPpm(anchorsOver(35, -100), 48_000))
    }

    @Test
    fun `an anchor span too short to out-measure its own noise reports no drift`() {
        // AudioRecord.getTimestamp() carries roughly a millisecond of jitter, and
        // over a short span that jitter IS the answer: a 2 s span turns 1 ms of it
        // into ~1000 ppm of apparent drift with none present -- ten times the
        // threshold that toasts the user and latches DRIFT_HIGH. Below the span
        // where the estimate can beat its own noise floor, the honest report is
        // the same one given for too few anchors: none.
        val jittered = listOf(
            AudioAnchor(0L, 0L),
            AudioAnchor(48_000L, 1_000_000_000L),
            AudioAnchor(96_000L, 2_001_000_000L), // one anchor a millisecond late
        )
        assertEquals(0, AvSync.driftPpm(jittered, 48_000))
    }

    @Test
    fun `jitter on the first anchor alone does not tilt the whole estimate`() {
        // The fit carries an intercept instead of being forced through the first
        // anchor. Through the origin that one reading is the pivot, so its own
        // jitter tilts every estimate: with the base anchor a single millisecond
        // late over a 35 s span and no real drift at all, the pivoting fit reports
        // -42 ppm where this one reports -5.
        val baseLateByOneMs = (0..35).map { i ->
            val ns = if (i == 0) 0L else i.toLong() * 1_000_000_000L - 1_000_000L
            AudioAnchor(i.toLong() * 48_000L, ns)
        }
        assertEquals(-5, AvSync.driftPpm(baseLateByOneMs, 48_000))
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

    // -- planPrerollTrim ------------------------------------------------------
    // chunkSizes are interleaved-sample counts; trimFrames is in FRAMES (the
    // same unit trimSamples() returns), converted internally via channels.

    @Test
    fun `zero trim keeps everything, nothing padded or dropped`() {
        val plan = AvSync.planPrerollTrim(listOf(100, 100), trimFrames = 0L, channels = 1)
        assertEquals(0L, plan.padSamples)
        assertEquals(0, plan.dropChunkCount)
        assertEquals(0, plan.partialDropSamples)
        assertEquals(0L, plan.residualSamples)
    }

    @Test
    fun `negative trim yields pad samples and drops nothing`() {
        // -50 frames stereo = -100 interleaved samples to pad.
        val plan = AvSync.planPrerollTrim(listOf(100, 100), trimFrames = -50L, channels = 2)
        assertEquals(100L, plan.padSamples)
        assertEquals(0, plan.dropChunkCount)
        assertEquals(0, plan.partialDropSamples)
        assertEquals(0L, plan.residualSamples)
    }

    @Test
    fun `trim landing exactly on a chunk boundary drops whole chunks only`() {
        // 100 frames mono = 100 samples = exactly chunkSizes[0].
        val plan = AvSync.planPrerollTrim(listOf(100, 100, 100), trimFrames = 100L, channels = 1)
        assertEquals(0L, plan.padSamples)
        assertEquals(1, plan.dropChunkCount)
        assertEquals(0, plan.partialDropSamples)
        assertEquals(0L, plan.residualSamples)
    }

    @Test
    fun `trim spanning multiple whole chunks drops all of them`() {
        val plan = AvSync.planPrerollTrim(listOf(100, 100, 100, 100), trimFrames = 250L, channels = 1)
        assertEquals(0L, plan.padSamples)
        assertEquals(2, plan.dropChunkCount)
        assertEquals(50, plan.partialDropSamples)
        assertEquals(0L, plan.residualSamples)
    }

    @Test
    fun `trim landing mid-chunk drops a partial chunk`() {
        val plan = AvSync.planPrerollTrim(listOf(100, 100), trimFrames = 30L, channels = 1)
        assertEquals(0L, plan.padSamples)
        assertEquals(0, plan.dropChunkCount)
        assertEquals(30, plan.partialDropSamples)
        assertEquals(0L, plan.residualSamples)
    }

    @Test
    fun `trim larger than the whole preroll consumes it and carries a residual`() {
        val plan = AvSync.planPrerollTrim(listOf(100, 100), trimFrames = 350L, channels = 1)
        assertEquals(0L, plan.padSamples)
        assertEquals(2, plan.dropChunkCount)
        assertEquals(0, plan.partialDropSamples)
        assertEquals(150L, plan.residualSamples)
    }

    @Test
    fun `an empty preroll with a positive trim is pure residual`() {
        val plan = AvSync.planPrerollTrim(emptyList(), trimFrames = 40L, channels = 2)
        assertEquals(0L, plan.padSamples)
        assertEquals(0, plan.dropChunkCount)
        assertEquals(0, plan.partialDropSamples)
        assertEquals(80L, plan.residualSamples)
    }
}
