package com.shez.rawcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLadderTest {

    /** Shared across the alignment sweep and the strictly-increasing sweep so
     *  both exercise the same sizes -- including 16x12, where nominal 1.4 and
     *  nominal 2.0 both round to cropW=8 (same actual ratio) if not de-duplicated. */
    private val sweepSizes = listOf(
        4096 to 3072, 4000 to 3000, 3840 to 2160, 2048 to 1536,
        1998 to 1123, 1279 to 719, 977 to 541, 64 to 48, 16 to 12,
        // 17x12 is not filler: it is the smallest width at which the R5 cap
        // clamp fires on a NON-top nominal (2.8x floors to cropW=4 -> 4.25x).
        17 to 12,
    )

    /** The 14 Ultra main camera, the reference case from the spec. This is the
     *  regression guard for the R5 4x-cap round-up fix: 4096/4=1024 is already a
     *  multiple of 4, so the clamp never triggers here and this table must stay
     *  byte-identical to what round-down alone already produced. */
    @Test fun mainCameraLadderMatchesSpecTable() {
        val stops = ZoomLadder.build(4096, 3072, maxRatio = 10f, activeArrayDefaulted = false)
        assertEquals(5, stops.size)

        assertEquals(listOf(4096, 3072, 0, 0), stops[0].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(2924, 2192, 586, 440), stops[1].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(2048, 1536, 1024, 768), stops[2].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(1460, 1094, 1318, 988), stops[3].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(1024, 768, 1536, 1152), stops[4].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })

        assertEquals(1.0f, stops[0].ratio, 1e-6f)
        assertEquals(2.0f, stops[2].ratio, 1e-6f)
        assertEquals(4.0f, stops[4].ratio, 1e-6f)
        // 2.8x lands on an actual ratio a touch above nominal because cropW rounds down.
        assertEquals(4096f / 1460f, stops[3].ratio, 1e-6f)
    }

    /** Fix round 2 [Important]: pins exact numbers at a size where R5's round-up
     *  clamp actually fires. Without this, the round-up path was covered only by
     *  generic sweep invariants (aligned, in bounds, increasing, <=4x) -- an R5
     *  that was subtly wrong but still happened to satisfy all of those would
     *  pass every other test here.
     *
     *  1998 is chosen precisely because 1998/4 = 499.5 is NOT a multiple of 4:
     *  flooring the 4x stop's cropW gives 496, actual ratio 1998/496 ~= 4.028,
     *  over the cap -- so the clamp rounds cropW back UP to 500 (ratio 3.996).
     *  Contrast with 4096, where 4096/4=1024 is already aligned and the clamp
     *  never triggers (dead code there) -- that contrast is the point of this
     *  test, and why [mainCameraLadderMatchesSpecTable] alone can't catch a
     *  broken R5. */
    @Test fun capClampProducesExactRectangleWhenItFires() {
        val stops = ZoomLadder.build(1998, 1123, maxRatio = 10f, activeArrayDefaulted = false)
        assertEquals(5, stops.size)

        assertEquals(listOf(1998, 1123, 0, 0), stops[0].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(1424, 800, 286, 160), stops[1].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(996, 558, 500, 282), stops[2].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(712, 400, 642, 360), stops[3].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        // The clamp-fired stop: floored cropW 496 (ratio 4.028, over cap) rounds
        // UP one step to 500 (ratio 3.996) -- the exact numbers from RULING R5.
        assertEquals(listOf(500, 280, 748, 420), stops[4].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })

        assertEquals(1.0f, stops[0].ratio, 1e-6f)
        assertEquals(1998f / 1424f, stops[1].ratio, 1e-6f)
        assertEquals(1998f / 996f, stops[2].ratio, 1e-6f)
        assertEquals(1998f / 712f, stops[3].ratio, 1e-6f)
        assertEquals(1998f / 500f, stops[4].ratio, 1e-6f)
    }

    /** 1x must be the untouched full frame -- this is what keeps the 1x capture
     *  path byte-identical to what ships today. */
    /** The R5 clamp is written against the ACTUAL ratio, not the nominal, and this
     *  is the case that proves the difference matters. At fullW=17 the 2.8x stop --
     *  NOT the top rung -- floors to cropW=4 and would realize 17/4 = 4.25x, over
     *  the hard 4x product cap. Clamping lifts it to cropW=8 (2.125x), which then
     *  collides with the already-accepted 2.0x stop and is dropped by the R1(a)
     *  dedup, so the ladder ends at 2.0x rather than exposing an over-cap rung. */
    @Test fun nonTopStopAlsoGetsCapClamped() {
        val stops = ZoomLadder.build(17, 12, 10f, false)
        assertEquals(listOf(1.0f, 1.4f, 2.0f), stops.map { it.nominal })
        assertEquals(listOf(17, 12, 0, 0), stops[0].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(12, 8, 2, 2), stops[1].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(8, 4, 4, 4), stops[2].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        // The unclamped 2.8x rung would have been 4.25x. Nothing here exceeds 4x.
        for (s in stops) assertTrue("ratio ${s.ratio} exceeds the 4x cap", s.ratio <= 4.0f)
    }

    /** [ZoomStop.label] is what the rail shows, and its integer/decimal branch is
     *  the kind of thing that silently renders "2.0x" instead of "2x". Pins every
     *  nominal the ladder can actually emit. */
    @Test fun labelDropsTheTrailingZeroOnWholeStops() {
        val stops = ZoomLadder.build(4096, 3072, 10f, false)
        assertEquals(listOf("1x", "1.4x", "2x", "2.8x", "4x"), stops.map { it.label })
    }

    @Test fun oneXIsAlwaysTheWholeFrame() {
        for ((w, h) in listOf(4096 to 3072, 4000 to 3000, 2048 to 1536, 1998 to 1123)) {
            val first = ZoomLadder.build(w, h, 10f, false).first()
            assertEquals(1.0f, first.nominal, 1e-6f)
            assertEquals(1.0f, first.ratio, 1e-6f)
            assertEquals(w, first.cropW)
            assertEquals(h, first.cropH)
            assertEquals(0, first.cropX)
            assertEquals(0, first.cropY)
        }
    }

    /** The alignment rules, over a sweep that includes odd and prime-ish sizes.
     *  An odd cropX/cropY shifts the Bayer phase and silently wrecks colour;
     *  a cropW not divisible by 4 silently breaks the Packed10 gate. */
    @Test fun everyStopIsAlignedAndInBounds() {
        for ((w, h) in sweepSizes) {
            for (s in ZoomLadder.build(w, h, 10f, false)) {
                val where = "${w}x$h @ ${s.nominal}x"
                assertEquals("$where cropX even", 0, s.cropX % 2)
                assertEquals("$where cropY even", 0, s.cropY % 2)
                // RULING R6: alignment binds CROPPED stops only. 1x is not a crop --
                // it is the unmodified sensor frame, and "1x is the exact full
                // frame" outranks the alignment rule. This is safe because
                // capture.cpp already copes with an unaligned full width today:
                // its `width % 4 == 0` / `width % 2 == 0` gates fall back to
                // Packed12 or Raw16, so 1x on a sensor like 1998-wide behaves
                // byte-for-byte as it already ships -- this is not a bug being
                // papered over, it is the documented, pre-existing fallback path.
                if (s.nominal != 1.0f) {
                    assertEquals("$where cropW % 4", 0, s.cropW % 4)
                    assertEquals("$where cropH % 2", 0, s.cropH % 2)
                }
                assertTrue("$where within bounds", s.cropX + s.cropW <= w && s.cropY + s.cropH <= h)
                assertTrue("$where positive", s.cropW >= 4 && s.cropH >= 2)
            }
        }
    }

    /** Ratios are strictly increasing and never exceed the 4x product cap --
     *  swept over every size in [sweepSizes], not just the 4096x3072 reference.
     *
     *  Two independent rounding failure modes hide behind "just test 4096":
     *   - Small sensors (e.g. 16x12) can round two different nominal stops down
     *     to the same cropW/actual-ratio; build() must de-duplicate those rather
     *     than emit a repeated or non-increasing ratio (RULING R1(a)).
     *   - Flooring cropW for the top (4x) nominal stop always pushes the ACTUAL
     *     ratio ABOVE nominal, so on a width not divisible by 16 (e.g. 1998) the
     *     top stop can silently overshoot the spec's hard 4x cap; build() must
     *     round back up rather than let it through (RULING R5). This is invisible
     *     at 4096 purely because 4096/4=1024 is already a multiple of 4 -- which
     *     is exactly why this test must sweep sizes, not just the reference case.
     */
    @Test fun ratiosStrictlyIncreaseAndNeverExceedFourX() {
        for ((w, h) in sweepSizes) {
            val stops = ZoomLadder.build(w, h, 10f, false)
            for (i in 1 until stops.size) {
                assertTrue(
                    "${w}x$h: stop $i must zoom further in",
                    stops[i].ratio > stops[i - 1].ratio,
                )
            }
            assertTrue("${w}x$h: last stop must not exceed 4x", stops.last().ratio <= 4.0f + 1e-6f)
        }
    }

    /** Clamp rule 1: a device that will not preview past 3.2x must not offer a
     *  4x stop, or the preview would hold while the RAW crop kept going. */
    @Test fun stopsBeyondDeviceMaxAreRemoved() {
        val stops = ZoomLadder.build(4096, 3072, maxRatio = 3.2f, activeArrayDefaulted = false)
        assertEquals(listOf(1.0f, 1.4f, 2.0f, 2.8f), stops.map { it.nominal })
        assertTrue(stops.all { it.ratio <= 3.2f })
    }

    /** A device advertising no zoom at all gets 1x only, never an empty list. */
    @Test fun noZoomSupportLeavesOnlyOneX() {
        val stops = ZoomLadder.build(4096, 3072, maxRatio = 1.0f, activeArrayDefaulted = false)
        assertEquals(1, stops.size)
        assertEquals(1.0f, stops[0].ratio, 1e-6f)
    }

    /** Clamp rule 2: crop math on a guessed active array is not trustworthy. */
    @Test fun defaultedActiveArrayCollapsesToOneX() {
        val stops = ZoomLadder.build(4096, 3072, maxRatio = 10f, activeArrayDefaulted = true)
        assertEquals(1, stops.size)
        assertEquals(4096, stops[0].cropW)
    }

    /** Degenerate sizes must drop stops, never emit a nonsense rectangle.
     *  Fix round 2 [Minor]: pins the actual resulting ladder (not just generic
     *  bounds) -- 8x4 keeps 1.0x and 1.4x (both floor/round to a valid rect) and
     *  drops 2.0x (dedups to the same ratio as 1.4x, per R1(a)), 2.8x and 4.0x
     *  (floor to cropW<4). Also applies the same R6 1x exemption that
     *  [everyStopIsAlignedAndInBounds] uses, rather than asserting %4/%2 on the
     *  1x stop too -- that only passed before because 8x4 happens to already be
     *  aligned, which contradicts R6's actual invariant. */
    @Test fun degenerateSizesDropStopsInsteadOfEmittingBadRects() {
        val stops = ZoomLadder.build(8, 4, 10f, false)
        assertEquals(listOf(1.0f, 1.4f), stops.map { it.nominal })
        assertEquals(listOf(8, 4, 0, 0), stops[0].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(listOf(4, 2, 2, 0), stops[1].let { listOf(it.cropW, it.cropH, it.cropX, it.cropY) })
        assertEquals(1.0f, stops[0].ratio, 1e-6f)
        assertEquals(2.0f, stops[1].ratio, 1e-6f)
        for (s in stops) {
            assertTrue(s.cropW >= 4 && s.cropH >= 2)
            if (s.nominal != 1.0f) {
                assertEquals(0, s.cropW % 4)
                assertEquals(0, s.cropH % 2)
            }
        }
    }

    /** Documents the accepted consequence of the uniform 4x cap on a binned
     *  ("LOW") size: it really does land sub-VGA, and the UI's resolution
     *  readout is what makes that visible before the take. */
    @Test fun fourXOnABinnedSizeLandsSubVga() {
        val stops = ZoomLadder.build(2048, 1536, 10f, false)
        assertEquals(512, stops.last().cropW)
        assertEquals(384, stops.last().cropH)
    }
}
