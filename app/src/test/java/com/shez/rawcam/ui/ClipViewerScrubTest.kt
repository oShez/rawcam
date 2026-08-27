package com.shez.rawcam.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrub bar's touch-x -> proxy-ordinal mapping. Pure arithmetic, so it is
 * worth pinning here rather than re-checking by hand on a phone every time the
 * bar is touched.
 */
class ClipViewerScrubTest {

    private val width = 1000

    @Test fun `the left edge is the first frame`() {
        assertEquals(0, ordinalAt(0f, width, 100))
    }

    @Test fun `the right edge is the last frame`() {
        // Truncation used to make the final ordinal reachable only on the exact
        // right-hand pixel; rounding makes the whole last half-step land on it.
        assertEquals(99, ordinalAt(width.toFloat(), width, 100))
        assertEquals(99, ordinalAt(width * 0.996f, width, 100))
    }

    @Test fun `the midpoint is the middle frame`() {
        assertEquals(5, ordinalAt(width * 0.5f, width, 11))
    }

    @Test fun `positions round to the nearest frame rather than truncating`() {
        // 11 proxies = 10 intervals, so each frame is 0.1 of the track. Truncation
        // returned 1 for both of these; only the second is actually nearer to 2.
        assertEquals(1, ordinalAt(width * 0.14f, width, 11))
        assertEquals(2, ordinalAt(width * 0.15f, width, 11))
    }

    @Test fun `a touch beyond either end clamps to the take`() {
        assertEquals(0, ordinalAt(-250f, width, 100))
        assertEquals(99, ordinalAt(width * 2f, width, 100))
    }

    @Test fun `a single frame always maps to itself`() {
        assertEquals(0, ordinalAt(width * 0.75f, width, 1))
    }

    @Test fun `nothing to seek yields the first ordinal`() {
        assertEquals(0, ordinalAt(width * 0.5f, width, 0))
        // A zero-width track would divide by zero.
        assertEquals(0, ordinalAt(10f, 0, 100))
    }
}
