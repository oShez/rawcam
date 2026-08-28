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

    /** 1x must be the untouched full frame -- this is what keeps the 1x capture
     *  path byte-identical to what ships today. */
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

    /** Degenerate sizes must drop stops, never emit a nonsense rectangle. */
    @Test fun degenerateSizesDropStopsInsteadOfEmittingBadRects() {
        val stops = ZoomLadder.build(8, 4, 10f, false)
        assertTrue(stops.isNotEmpty())
        assertEquals(1.0f, stops[0].ratio, 1e-6f)
        for (s in stops) {
            assertTrue(s.cropW >= 4 && s.cropH >= 2)
            assertEquals(0, s.cropW % 4)
            assertEquals(0, s.cropH % 2)
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
