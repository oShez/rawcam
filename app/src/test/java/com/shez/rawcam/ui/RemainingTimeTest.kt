package com.shez.rawcam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "LEFT" readout on the record screen.
 *
 * Diagnosed on-device 2026-08-30 (Xiaomi 14 Ultra, main cam, 4096x3072 @24fps,
 * compressed, audio on). A 30s take wrote 8.846 GB in 30.47s wall with 344.6 GB
 * free, and 103 of 705 frames dropped -- so only 20.07 fps actually landed.
 * Screen-captured ground truth over that take:
 *
 *   elapsed  frames  dropped  LEFT
 *   0:01     28      0        16:05
 *   0:05     125     0        15:55
 *   0:10     223     3        15:58
 *   0:16     341     27       15:58
 *   0:21     436     52       16:09
 *   0:30     602     103      16:15
 *
 * Two defects are visible in that table and are pinned below:
 *
 *  1. It never counts down. The old readout recomputed `free / rate` statelessly
 *     on every recomposition, with no memory of the take, so 30 seconds of
 *     recording moved it UP by 10s instead of down by 30.
 *  2. The rate was ~18% too high, because it multiplied measured bytes-per-
 *     WRITTEN-frame by the NOMINAL fps. With 14.6% of frames dropping, the disk
 *     was taking 290 MB/s while the readout modelled 343 MB/s -- true time left
 *     was 19:46, the screen said 16:15.
 */
class RemainingTimeTest {

    // ---- the measured live rate -------------------------------------------

    @Test fun measuredRateIsWallClockSoDroppedFramesDoNotInflateIt() {
        // The device take, verbatim. Wall clock is the only honest denominator:
        // whatever the frame counter says, this is what the card actually took.
        val rate = measuredBytesPerSecond(
            clipBytes = 8_846_000_000L, audioBytesPerSecond = 144_000L, elapsedMs = 30_470L,
        )!!
        assertEquals(290.4e6, rate - 144_000L, 0.5e6)
    }

    @Test fun measuredRateIsNullBeforeTheSettleWindow() {
        assertNull(measuredBytesPerSecond(300_000_000L, 144_000L, elapsedMs = 900L))
    }

    @Test fun measuredRateNeedsBytesOnDisk() {
        assertNull(measuredBytesPerSecond(0L, 144_000L, elapsedMs = 5_000L))
    }

    /** Defect 2: against the same take, the honest rate must land near 19:46,
     *  not the 16:15 the nominal-fps model produced. */
    @Test fun deviceTakeEstimateMatchesTheRateTheCardActuallyTook() {
        val rate = measuredBytesPerSecond(8_846_000_000L, 144_000L, 30_470L)!!
        val left = remainingSecondsEstimate(freeBytes = 344_600_000_000L, bytesPerSecond = rate)!!
        assertEquals(1187.0, left, 15.0) // 19:47 +/- 15s; the screen showed 975s
    }

    @Test fun estimateIsNullWithoutARate() {
        assertNull(remainingSecondsEstimate(344_600_000_000L, 0.0))
    }

    /**
     * refreshFreeSpace() swallows a failed StatFs read and publishes 0. Treating
     * that as "no time left" would be unrecoverable here specifically because the
     * countdown is monotonic: it would latch at 0:00 for the rest of the take and
     * could never climb back. No reading must mean no estimate, not zero.
     */
    @Test fun aFailedFreeSpaceReadingYieldsNoEstimateRatherThanZero() {
        assertNull(remainingSecondsEstimate(0L, 290_000_000.0))
    }

    // ---- the countdown -----------------------------------------------------

    @Test fun firstStepAdoptsTheEstimate() {
        assertEquals(1187.0, stepRemainingSeconds(null, sinceMs = 500L, estimate = 1187.0), 1e-9)
    }

    /**
     * Defect 1, the headline. With the rate estimate holding steady, the readout
     * must fall one second per second of wall clock. The old code, given a steady
     * estimate, simply re-displayed the estimate -- flat, or drifting upward with
     * the estimate's own noise.
     */
    @Test fun steadyEstimateCountsDownOneSecondPerSecond() {
        var left = stepRemainingSeconds(null, 500L, 1187.0)
        repeat(20) { left = stepRemainingSeconds(left, 500L, 1187.0) } // 10s of wall clock
        assertEquals(1177.0, left, 1e-6)
    }

    /** Monotonic during a take: a scene that compresses better must not make the
     *  number jump back up. Agreed behaviour 2026-08-30. */
    @Test fun neverRisesWhenTheEstimateImproves() {
        val left = stepRemainingSeconds(previous = 1000.0, sinceMs = 500L, estimate = 1400.0)
        assertEquals(999.5, left, 1e-6)
    }

    /** A genuinely heavier scene has to be able to overtake the 1s/s countdown,
     *  or the readout would lie all the way to zero. */
    @Test fun fallsFasterThanRealTimeWhenTheEstimateDrops() {
        val left = stepRemainingSeconds(previous = 1000.0, sinceMs = 500L, estimate = 600.0)
        assertTrue("expected to fall by more than the 0.5s of wall clock, got $left", left < 999.0)
    }

    /** ...but by slewing, not snapping: a single noisy sample must never yank the
     *  display hundreds of seconds in one 500ms tick. That snap IS the jitter. */
    @Test fun aCollapsingEstimateIsSlewedRatherThanSnappedTo() {
        val left = stepRemainingSeconds(previous = 1000.0, sinceMs = 500L, estimate = 0.0)
        assertTrue("expected a slew-limited step, got a snap to $left", left > 995.0)
    }

    @Test fun repeatedLowEstimatesConvergeOnIt() {
        var left = 1000.0
        repeat(200) { left = stepRemainingSeconds(left, 500L, 600.0) } // 100s of wall clock
        assertEquals(600.0, left, 1.0)
    }

    @Test fun neverGoesNegative() {
        assertEquals(0.0, stepRemainingSeconds(previous = 0.2, sinceMs = 500L, estimate = 0.0), 1e-9)
    }

    @Test fun aTickWithNoElapsedTimeHoldsTheValue() {
        assertEquals(1000.0, stepRemainingSeconds(previous = 1000.0, sinceMs = 0L, estimate = 500.0), 1e-9)
    }

    // ---- the two together, over the real take ------------------------------

    /**
     * Replays the device take and asserts the readout only ever falls.
     *
     * The wobble matters: with a PERFECTLY constant rate even the old stateless
     * recompute looks like a countdown, because free space falls at exactly the
     * rate you divide by. The bug only surfaces once the rate estimate carries its
     * real +/-2% -- then `free / rate` swings ~25s either way and the readout
     * wanders. That is how the captured screen went 16:05 -> 16:15 across these
     * same 30 seconds. Deterministic wobble, so this cannot flake.
     */
    @Test fun readoutOnlyEverFallsAcrossTheDeviceTake() {
        val rate = measuredBytesPerSecond(8_846_000_000L, 144_000L, 30_470L)!!
        var free = 353_400_000_000L // start of take; 344.6 GB by the end
        var left = remainingSecondsEstimate(free, rate)!!
        val first = left
        repeat(60) { tick -> // 30s at the 500ms poll
            free -= (rate / 2).toLong()
            // +/-2%, the drift the cumulative rate actually showed on-device.
            val wobbled = rate * (1.0 + 0.02 * kotlin.math.sin(tick * 0.7))
            val next = stepRemainingSeconds(left, 500L, remainingSecondsEstimate(free, wobbled)!!)
            assertTrue("readout rose at tick $tick: $left -> $next", next <= left + 1e-9)
            left = next
        }
        assertTrue("expected ~30s of countdown, got ${first - left}", first - left >= 28.0)
    }
}
