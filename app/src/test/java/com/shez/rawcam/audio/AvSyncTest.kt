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
